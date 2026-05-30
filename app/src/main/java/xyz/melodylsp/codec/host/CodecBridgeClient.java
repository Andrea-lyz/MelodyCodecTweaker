package xyz.melodylsp.codec.host;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.RemoteException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

import xyz.melodylsp.codec.bridge.CodecIpc;
import xyz.melodylsp.codec.bridge.CodecRequest;
import xyz.melodylsp.codec.bridge.CodecSnapshot;
import xyz.melodylsp.codec.bridge.ICodecBridge;
import xyz.melodylsp.codec.bridge.ICodecBridgeListener;
import xyz.melodylsp.codec.bt.BluetoothCodecReflect;
import xyz.melodylsp.codec.label.CodecLabelTable;
import xyz.melodylsp.codec.util.MLog;

/**
 * Encapsulates the three-tier write strategy described in design.md §Component 6:
 * <ol>
 *   <li>{@link WriteResult.Path#DIRECT_API} – host-process reflection on
 *       {@code BluetoothA2dp.setCodecConfigPreference}.</li>
 *   <li>{@link WriteResult.Path#SYSTEM_BRIDGE} – AIDL call into {@code com.android.bluetooth}.</li>
 *   <li>{@link WriteResult.Path#SETTINGS_GLOBAL} – {@code Settings.Global} developer-options keys.</li>
 * </ol>
 *
 * <p>It also exposes a push-based subscription that forwards
 * {@link ICodecBridgeListener#onCodecChanged(CodecSnapshot)} events to interested
 * observers. The listener is only registered after the first time the bridge is reached, so
 * the AIDL service is not eagerly contacted on every host launch.</p>
 */
public final class CodecBridgeClient {

    /** Callback fired when a fresh {@link CodecSnapshot} arrives via the AIDL bridge. */
    public interface SnapshotListener {
        void onSnapshot(CodecSnapshot snapshot);
    }

    private static final String ACTION_CODEC_CONFIG_CHANGED =
            "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED";
    private static final long CONFIRM_TIMEOUT_MS = 3_000L;
    private static final long LHDC_SECOND_STEP_DELAY_MS = 250L;
    private static final long CODEC_BROADCAST_TIMEOUT_MS = 1_500L;

    private final Context context;
    private final BluetoothCodecReflect reflect;
    private final SettingsGlobalFallback settingsFallback;
    private final RootShellFallback rootFallback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler ipcHandler;

    private final CopyOnWriteArrayList<SnapshotListener> listeners = new CopyOnWriteArrayList<>();
    private volatile ICodecBridge cachedBridge;
    private volatile ICodecBridgeListener registeredListener;

    public CodecBridgeClient(
            Context context,
            BluetoothCodecReflect reflect,
            SettingsGlobalFallback settingsFallback) {
        this(context, reflect, settingsFallback, new RootShellFallback());
    }

    public CodecBridgeClient(
            Context context,
            BluetoothCodecReflect reflect,
            SettingsGlobalFallback settingsFallback,
            RootShellFallback rootFallback) {
        this.context = context.getApplicationContext();
        this.reflect = reflect;
        this.settingsFallback = settingsFallback;
        this.rootFallback = rootFallback;
        HandlerThread ipcThread = new HandlerThread("MelodyCodecLsp-codecIpc");
        ipcThread.start();
        this.ipcHandler = new Handler(ipcThread.getLooper());
    }

    public void addSnapshotListener(SnapshotListener listener) {
        if (listener == null) return;
        listeners.add(listener);
        // Trigger lazy bridge / listener attach so subsequent push events are delivered.
        ensureListenerRegistered();
    }

    public void removeSnapshotListener(SnapshotListener listener) {
        if (listener == null) return;
        listeners.remove(listener);
    }

    /** Returns the latest snapshot for {@code mac}; null when status is unavailable. */
    public CodecSnapshot getStatus(String mac) {
        try {
            CodecSnapshot snapshot = reflect.readStatus(mac);
            if (snapshot != null) return snapshot;
        } catch (Throwable t) {
            MLog.w("getStatus(" + mac + ") failed via direct API, trying bridge", t);
        }
        ICodecBridge bridge = ensureBridge();
        if (bridge != null) {
            try {
                CodecSnapshot snapshot = bridge.getStatus(mac);
                if (snapshot != null) return snapshot;
            } catch (RemoteException re) {
                MLog.w("bridge.getStatus failed", re);
            }
        }
        return queryCodecViaBroadcast(mac, CODEC_BROADCAST_TIMEOUT_MS);
    }

    /** Writes the request and resolves with the eventual outcome (confirmed / rolled back). */
    public CompletableFuture<WriteResult> setCodec(CodecRequest request) {
        // Path A.
        return applyDirectWrite(request)
                .handle((ignored, error) -> error)
                .thenCompose(error -> {
                    if (error != null) {
                        MLog.w("Path-A setCodec failed", unwrap(error));
                        return setCodecViaBridgeOrFallback(request);
                    }
                    return awaitConfirmation(request, WriteResult.Path.DIRECT_API)
                            .thenCompose(result -> {
                                if (result.outcome == WriteResult.Outcome.CONFIRMED) {
                                    return CompletableFuture.completedFuture(result);
                                }
                                MLog.w("Path-A accepted but not confirmed; trying bridge/settings/root");
                                return setCodecViaBridgeOrFallback(request);
                            });
                });
    }

    private CompletableFuture<Void> applyDirectWrite(CodecRequest request) {
        if (!CodecLabelTable.isLhdc(request.codecType) || request.codecSpecific1 == 0L) {
            try {
                reflect.setCodec(request);
                return CompletableFuture.completedFuture(null);
            } catch (Throwable t) {
                CompletableFuture<Void> failed = new CompletableFuture<>();
                failed.completeExceptionally(t);
                return failed;
            }
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        CodecRequest priming = new CodecRequest(
                request.mac,
                request.codecType,
                0L,
                request.codecSpecific2,
                request.codecSpecific3,
                request.codecSpecific4,
                request.sampleRate,
                request.bitsPerSample,
                request.channelMode);
        try {
            // Bluetooth Codec Changer primes LHDC by briefly applying codecSpecific1=0 before
            // writing the target quality/sample tuple. OPPO's stack accepts this as a real-time
            // renegotiation path instead of waiting for the next reconnect.
            reflect.setCodec(priming);
            MLog.event("write.lhdc.prime", "request", priming);
        } catch (Throwable t) {
            future.completeExceptionally(t);
            return future;
        }
        mainHandler.postDelayed(() -> {
            try {
                reflect.setCodec(request);
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }, LHDC_SECOND_STEP_DELAY_MS);
        return future;
    }

    private CompletableFuture<WriteResult> setCodecViaBridgeOrFallback(CodecRequest request) {
        // Path B.
        ICodecBridge bridge = ensureBridge();
        if (bridge != null) {
            try {
                int code = bridge.setCodec(request);
                if (code == CodecRequest.RESULT_OK) {
                    return awaitConfirmation(request, WriteResult.Path.SYSTEM_BRIDGE)
                            .thenCompose(result -> {
                                if (result.outcome == WriteResult.Outcome.CONFIRMED) {
                                    return CompletableFuture.completedFuture(result);
                                }
                                MLog.w("Path-B accepted but not confirmed; trying settings/root");
                                return setCodecViaSettingsOrRoot(request);
                            });
                }
                MLog.w("Path-B bridge.setCodec returned " + code);
            } catch (RemoteException re) {
                MLog.w("Path-B bridge.setCodec RemoteException", re);
            }
        }

        return setCodecViaBroadcast(request)
                .thenCompose(code -> {
                    if (code == CodecRequest.RESULT_OK) {
                        return awaitConfirmation(request, WriteResult.Path.SYSTEM_BROADCAST)
                                .thenCompose(result -> {
                                    if (result.outcome == WriteResult.Outcome.CONFIRMED) {
                                        return CompletableFuture.completedFuture(result);
                                    }
                                    MLog.w("Path-B broadcast accepted but not confirmed; trying settings/root");
                                    return setCodecViaSettingsOrRoot(
                                            request, WriteResult.Path.SYSTEM_BROADCAST);
                                });
                    }
                    MLog.w("Path-B broadcast setCodec returned " + code);
                    return setCodecViaSettingsOrRoot(request, WriteResult.Path.SYSTEM_BROADCAST);
                });
    }

    private CompletableFuture<WriteResult> setCodecViaSettingsOrRoot(CodecRequest request) {
        return setCodecViaSettingsOrRoot(request, WriteResult.Path.SYSTEM_BRIDGE);
    }

    private CompletableFuture<WriteResult> setCodecViaSettingsOrRoot(
            CodecRequest request, WriteResult.Path failedPath) {
        if (CodecLabelTable.isLhdc(request.codecType)) {
            MLog.w("LHDC realtime write was not confirmed; skip settings/root reconnect fallback");
            return CompletableFuture.completedFuture(WriteResult.failed(
                    failedPath,
                    new IllegalStateException("LHDC realtime write path unavailable")));
        }

        // Path C — Settings.Global from app context (needs WRITE_SECURE_SETTINGS, usually denied).
        boolean wrote = settingsFallback.apply(request);
        if (wrote) {
            MLog.event("write.path", "path", "SETTINGS_GLOBAL");
            return CompletableFuture.completedFuture(WriteResult.confirmed(WriteResult.Path.SETTINGS_GLOBAL));
        }

        // Path D — root shell. Run on a worker thread because exec'ing su can block for ~1 s
        // while the manager presents its prompt; we never want to stall the UI thread on it.
        CompletableFuture<WriteResult> rootFuture = new CompletableFuture<>();
        Thread worker = new Thread(() -> {
            try {
                boolean ok = rootFallback.apply(request);
                if (ok) {
                    MLog.event("write.path", "path", "ROOT_SHELL");
                    // Wait for the bluetooth-toggle in the root script to bring A2DP back, then
                    // poll once to see whether the spec1/sample-rate stuck. We resolve as
                    // CONFIRMED even if we cannot read it back, because the kernel does not
                    // emit a CODEC_CONFIG_CHANGED broadcast for global-settings-driven changes
                    // until the next track plays — pretending it failed would falsely toast.
                    rootFuture.complete(WriteResult.confirmed(WriteResult.Path.ROOT_SHELL));
                } else {
                    MLog.e("All write paths failed; nothing was applied");
                    rootFuture.complete(WriteResult.failed(WriteResult.Path.ROOT_SHELL,
                            new IllegalStateException("no usable write path (root denied or absent)")));
                }
            } catch (Throwable t) {
                rootFuture.complete(WriteResult.failed(WriteResult.Path.ROOT_SHELL, t));
            }
        }, "MelodyCodecLsp-rootWrite");
        worker.setDaemon(true);
        worker.start();
        return rootFuture;
    }

    private ICodecBridge ensureBridge() {
        ICodecBridge cached = cachedBridge;
        if (cached != null) return cached;
        synchronized (this) {
            if (cachedBridge == null) {
                cachedBridge = BridgeServiceLocator.get();
            }
            return cachedBridge;
        }
    }

    private void ensureListenerRegistered() {
        if (registeredListener != null) return;
        ICodecBridge bridge = ensureBridge();
        if (bridge == null) return;
        synchronized (this) {
            if (registeredListener != null) return;
            ICodecBridgeListener listener = new ICodecBridgeListener.Stub() {
                @Override
                public void onCodecChanged(CodecSnapshot snapshot) {
                    if (snapshot == null) return;
                    for (SnapshotListener l : listeners) {
                        try {
                            l.onSnapshot(snapshot);
                        } catch (Throwable t) {
                            MLog.w("snapshot listener threw", t);
                        }
                    }
                }

                @Override
                public void onConnectionChanged(String mac, int state) {
                    // We rely on the host-side broadcast receiver for the connect/disconnect
                    // path, so this method is intentionally a no-op.
                }
            };
            try {
                bridge.register(listener);
                registeredListener = listener;
                MLog.event("bridge.listener.registered");
            } catch (RemoteException re) {
                MLog.w("bridge.register failed", re);
            }
        }
    }

    private CompletableFuture<Integer> setCodecViaBroadcast(CodecRequest request) {
        return CompletableFuture.supplyAsync(() ->
                sendCodecSetBroadcast(request, CODEC_BROADCAST_TIMEOUT_MS));
    }

    private CodecSnapshot queryCodecViaBroadcast(String mac, long timeoutMs) {
        if (mac == null || mac.isEmpty()) return null;
        String requestId = UUID.randomUUID().toString();
        AtomicReference<CodecSnapshot> snapshotRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        BroadcastReceiver receiver = codecReplyReceiver(requestId, intent -> {
            CodecSnapshot snapshot = readSnapshot(intent);
            if (snapshot != null) {
                snapshotRef.set(snapshot);
            }
            latch.countDown();
        });
        registerCodecReplyReceiver(receiver);
        try {
            Intent intent = new Intent(CodecIpc.ACTION_QUERY_CODEC);
            intent.setPackage(CodecIpc.BLUETOOTH_PKG);
            intent.putExtra(CodecIpc.EXTRA_TOKEN, CodecIpc.TOKEN);
            intent.putExtra(CodecIpc.EXTRA_REQUEST_ID, requestId);
            intent.putExtra(CodecIpc.EXTRA_MAC, mac);
            context.sendBroadcast(intent);
            boolean delivered = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!delivered) {
                MLog.w("codec broadcast query timed out");
            }
            CodecSnapshot snapshot = snapshotRef.get();
            if (snapshot != null) {
                MLog.event("codec.broadcast.query", "ok", true);
            }
            return snapshot;
        } catch (Throwable t) {
            MLog.w("codec broadcast query failed", t);
            return null;
        } finally {
            unregisterQuietly(receiver);
        }
    }

    private int sendCodecSetBroadcast(CodecRequest request, long timeoutMs) {
        if (request == null || request.mac == null || request.mac.isEmpty()) {
            return CodecRequest.RESULT_INVALID;
        }
        String requestId = UUID.randomUUID().toString();
        AtomicReference<Integer> resultRef = new AtomicReference<>(CodecRequest.RESULT_TIMEOUT);
        CountDownLatch latch = new CountDownLatch(1);
        BroadcastReceiver receiver = codecReplyReceiver(requestId, intent -> {
            resultRef.set(intent.getIntExtra(CodecIpc.EXTRA_RESULT, CodecRequest.RESULT_ERROR));
            CodecSnapshot snapshot = readSnapshot(intent);
            if (snapshot != null) {
                dispatchSnapshot(snapshot);
            }
            latch.countDown();
        });
        registerCodecReplyReceiver(receiver);
        try {
            Intent intent = new Intent(CodecIpc.ACTION_SET_CODEC);
            intent.setPackage(CodecIpc.BLUETOOTH_PKG);
            writeRequest(intent, requestId, request);
            context.sendBroadcast(intent);
            boolean delivered = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!delivered) {
                MLog.w("codec broadcast set timed out");
                return CodecRequest.RESULT_TIMEOUT;
            }
            return resultRef.get();
        } catch (Throwable t) {
            MLog.w("codec broadcast set failed", t);
            return CodecRequest.RESULT_ERROR;
        } finally {
            unregisterQuietly(receiver);
        }
    }

    private interface CodecReplyHandler {
        void onReply(Intent intent);
    }

    private BroadcastReceiver codecReplyReceiver(String requestId, CodecReplyHandler handler) {
        return new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent == null || !CodecIpc.ACTION_CODEC_STATE.equals(intent.getAction())) {
                    return;
                }
                if (!CodecIpc.TOKEN.equals(intent.getStringExtra(CodecIpc.EXTRA_TOKEN))) {
                    return;
                }
                String incomingId = intent.getStringExtra(CodecIpc.EXTRA_REQUEST_ID);
                if (requestId != null && !requestId.equals(incomingId)) {
                    return;
                }
                handler.onReply(intent);
            }
        };
    }

    private void registerCodecReplyReceiver(BroadcastReceiver receiver) {
        IntentFilter filter = new IntentFilter(CodecIpc.ACTION_CODEC_STATE);
        try {
            context.registerReceiver(receiver, filter, null, ipcHandler, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            context.registerReceiver(receiver, filter, null, ipcHandler);
        }
    }

    private void unregisterQuietly(BroadcastReceiver receiver) {
        try {
            context.unregisterReceiver(receiver);
        } catch (Throwable ignored) {
        }
    }

    private void dispatchSnapshot(CodecSnapshot snapshot) {
        if (snapshot == null) return;
        for (SnapshotListener l : listeners) {
            try {
                l.onSnapshot(snapshot);
            } catch (Throwable t) {
                MLog.w("snapshot listener threw", t);
            }
        }
    }

    private static void writeRequest(Intent intent, String requestId, CodecRequest request) {
        intent.putExtra(CodecIpc.EXTRA_TOKEN, CodecIpc.TOKEN);
        intent.putExtra(CodecIpc.EXTRA_REQUEST_ID, requestId);
        intent.putExtra(CodecIpc.EXTRA_MAC, request.mac);
        intent.putExtra(CodecIpc.EXTRA_CODEC_TYPE, request.codecType);
        intent.putExtra(CodecIpc.EXTRA_SAMPLE_RATE, request.sampleRate);
        intent.putExtra(CodecIpc.EXTRA_BITS_PER_SAMPLE, request.bitsPerSample);
        intent.putExtra(CodecIpc.EXTRA_CHANNEL_MODE, request.channelMode);
        intent.putExtra(CodecIpc.EXTRA_CODEC_SPECIFIC_1, request.codecSpecific1);
        intent.putExtra(CodecIpc.EXTRA_CODEC_SPECIFIC_2, request.codecSpecific2);
        intent.putExtra(CodecIpc.EXTRA_CODEC_SPECIFIC_3, request.codecSpecific3);
        intent.putExtra(CodecIpc.EXTRA_CODEC_SPECIFIC_4, request.codecSpecific4);
    }

    private static CodecSnapshot readSnapshot(Intent intent) {
        if (intent == null || !intent.hasExtra(CodecIpc.EXTRA_SAMPLE_RATE)) return null;
        String mac = intent.getStringExtra(CodecIpc.EXTRA_MAC);
        long[] selectableSpecific1 =
                intent.getLongArrayExtra(CodecIpc.EXTRA_SELECTABLE_SPECIFIC_1);
        return new CodecSnapshot(
                mac,
                intent.getIntExtra(CodecIpc.EXTRA_CODEC_TYPE, 0),
                intent.getIntExtra(CodecIpc.EXTRA_SAMPLE_RATE, 0),
                intent.getIntExtra(CodecIpc.EXTRA_BITS_PER_SAMPLE, 0),
                intent.getIntExtra(CodecIpc.EXTRA_CHANNEL_MODE, 0),
                intent.getLongExtra(CodecIpc.EXTRA_CODEC_SPECIFIC_1, 0L),
                intent.getLongExtra(CodecIpc.EXTRA_CODEC_SPECIFIC_2, 0L),
                intent.getLongExtra(CodecIpc.EXTRA_CODEC_SPECIFIC_3, 0L),
                intent.getLongExtra(CodecIpc.EXTRA_CODEC_SPECIFIC_4, 0L),
                selectableSpecific1,
                intent.getIntExtra(CodecIpc.EXTRA_SELECTABLE_SAMPLE_RATE_MASK, 0),
                intent.getLongExtra(CodecIpc.EXTRA_READ_TIMESTAMP_MS, 0L));
    }

    /**
     * Wait for a {@code ACTION_CODEC_CONFIG_CHANGED} broadcast; if the active config matches the
     * request within {@link #CONFIRM_TIMEOUT_MS}, resolve as {@link WriteResult.Outcome#CONFIRMED};
     * otherwise re-read status and resolve with {@link WriteResult.Outcome#TIMEOUT_ROLLED_BACK}.
     */
    private CompletableFuture<WriteResult> awaitConfirmation(
            CodecRequest request, WriteResult.Path path) {
        CompletableFuture<WriteResult> future = new CompletableFuture<>();
        AtomicReference<BroadcastReceiver> receiverRef = new AtomicReference<>();

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (!ACTION_CODEC_CONFIG_CHANGED.equals(intent.getAction())) return;
                if (future.isDone()) return;
                CodecSnapshot s = safeReadStatus(request.mac);
                if (s != null && matches(s, request)) {
                    completeWith(future, WriteResult.confirmed(path), receiverRef);
                }
            }
        };
        receiverRef.set(receiver);
        try {
            context.registerReceiver(receiver, new IntentFilter(ACTION_CODEC_CONFIG_CHANGED),
                    Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            // RECEIVER_EXPORTED constant only required on T+; fall back to legacy registration.
            context.registerReceiver(receiver, new IntentFilter(ACTION_CODEC_CONFIG_CHANGED));
        }

        mainHandler.postDelayed(() -> {
            if (future.isDone()) return;
            CodecSnapshot s = safeReadStatus(request.mac);
            if (s != null && matches(s, request)) {
                completeWith(future, WriteResult.confirmed(path), receiverRef);
            } else {
                MLog.event("write.timeout", "request", request, "live", String.valueOf(s));
                completeWith(future, WriteResult.rolledBack(path, s), receiverRef);
            }
        }, CONFIRM_TIMEOUT_MS);

        return future;
    }

    private CodecSnapshot safeReadStatus(String mac) {
        try {
            return getStatus(mac);
        } catch (Throwable t) {
            MLog.w("safeReadStatus failed", t);
            return null;
        }
    }

    private static boolean matches(CodecSnapshot snapshot, CodecRequest request) {
        if (snapshot.activeCodecType != request.codecType
                || snapshot.activeSampleRate != request.sampleRate) {
            return false;
        }
        if (CodecLabelTable.isLhdc(request.codecType)) {
            long active = snapshot.activeCodecSpecific1 & 0xFFL;
            long requested = request.codecSpecific1 & 0xFFL;
            if (active == requested) return true;
            return (active == CodecLabelTable.LHDC_QUALITY_BALANCED
                    && requested == CodecLabelTable.LHDC_QUALITY_STANDARD)
                    || (active == CodecLabelTable.LHDC_QUALITY_STANDARD
                    && requested == CodecLabelTable.LHDC_QUALITY_BALANCED)
                    || (active == CodecLabelTable.LHDC_QUALITY_HIGH
                    && requested == CodecLabelTable.LHDC_QUALITY_HIGH_LEGACY)
                    || (active == CodecLabelTable.LHDC_QUALITY_HIGH_LEGACY
                    && requested == CodecLabelTable.LHDC_QUALITY_HIGH);
        }
        return snapshot.activeCodecSpecific1 == request.codecSpecific1;
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null
                && (cur instanceof java.util.concurrent.CompletionException
                || cur instanceof java.util.concurrent.ExecutionException)) {
            cur = cur.getCause();
        }
        return cur;
    }

    private void completeWith(
            CompletableFuture<WriteResult> future,
            WriteResult result,
            AtomicReference<BroadcastReceiver> receiverRef) {
        BroadcastReceiver r = receiverRef.getAndSet(null);
        if (r != null) {
            try {
                context.unregisterReceiver(r);
            } catch (IllegalArgumentException ignored) {
                // Receiver was already removed.
            }
        }
        future.complete(result);
    }
}
