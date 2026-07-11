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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.Locale;
import java.util.UUID;

import xyz.melodylsp.codec.bridge.CodecIpc;
import xyz.melodylsp.codec.bridge.CodecRequest;
import xyz.melodylsp.codec.bridge.CodecSnapshot;
import xyz.melodylsp.codec.bridge.ICodecBridge;
import xyz.melodylsp.codec.bridge.ICodecBridgeListener;
import xyz.melodylsp.codec.bt.BluetoothCodecReflect;
import xyz.melodylsp.codec.label.CodecLabelTable;
import xyz.melodylsp.codec.util.MLog;
import xyz.melodylsp.codec.util.TrustedBroadcasts;

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
    private static final long DEFAULT_CONFIRM_TIMEOUT_MS = 3_000L;
    private static final long LHDC_CONFIRM_TIMEOUT_MS = 5_000L;
    private static final long OPTIONAL_CONFIRM_TIMEOUT_MS = 4_000L;
    private static final long CODEC_BROADCAST_TIMEOUT_MS = 1_500L;

    private final Context context;
    private final BluetoothCodecReflect reflect;
    private final SettingsGlobalFallback settingsFallback;
    private final RootShellFallback rootFallback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler ipcHandler;
    private final Handler statusHandler;

    private final CopyOnWriteArrayList<SnapshotListener> listeners = new CopyOnWriteArrayList<>();
    private volatile ICodecBridge cachedBridge;
    private volatile ICodecBridgeListener registeredListener;
    private final AtomicBoolean directStatusBlocked = new AtomicBoolean(false);

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
        HandlerThread statusThread = new HandlerThread("MelodyCodecLsp-codecStatus");
        statusThread.start();
        this.statusHandler = new Handler(statusThread.getLooper());
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
        CodecSnapshot snapshot = getStatusViaBridge(mac);
        if (snapshot != null) return snapshot;

        snapshot = queryCodecViaBroadcast(mac, CODEC_BROADCAST_TIMEOUT_MS);
        if (snapshot != null) return snapshot;

        if (!directStatusBlocked.get()) {
            snapshot = getStatusViaDirectApi(mac);
            if (snapshot != null) return snapshot;
        }

        return null;
    }

    private CodecSnapshot getStatusViaBridge(String mac) {
        ICodecBridge bridge = ensureBridge();
        if (bridge == null) return null;
        try {
            CodecSnapshot snapshot = bridge.getStatus(mac);
            if (snapshot != null) return snapshot;
        } catch (RemoteException re) {
            MLog.w("bridge.getStatus failed", re);
        }
        return null;
    }

    private CodecSnapshot getStatusViaDirectApi(String mac) {
        try {
            return reflect.readStatus(mac);
        } catch (Throwable t) {
            if (isCdmStatusBlock(t)) {
                if (directStatusBlocked.compareAndSet(false, true)) {
                    MLog.w("direct codec status API blocked by CDM; using bridge/broadcast status path");
                }
            } else {
                MLog.w("getStatus(" + mac + ") failed via direct API", t);
            }
            return null;
        }
    }

    /** Writes the request and resolves with the eventual outcome (confirmed / rolled back). */
    public CompletableFuture<WriteResult> setCodec(CodecRequest request) {
        return setCodec(request, () -> true);
    }

    /**
     * Writes the request and only advances to slower fallback paths while {@code shouldContinue}
     * remains true. The first direct request is never delayed; the guard only prevents stale
     * operations from reviving after their confirmation window timed out.
     */
    public CompletableFuture<WriteResult> setCodec(
            CodecRequest request, BooleanSupplier shouldContinue) {
        // Path A.
        return applyDirectWrite(request)
                .handle((ignored, error) -> error)
                .thenCompose(error -> {
                    if (error != null) {
                        logPathAFallback("setCodec", request, unwrap(error),
                                "bridge_settings_root");
                        if (!shouldContinue(shouldContinue)) {
                            return staleWriteResult(request, WriteResult.Path.DIRECT_API);
                        }
                        return setCodecViaBridgeOrFallback(request, shouldContinue);
                    }
                    return awaitConfirmation(request, WriteResult.Path.DIRECT_API)
                            .thenCompose(result -> {
                                if (result.outcome == WriteResult.Outcome.CONFIRMED) {
                                    return CompletableFuture.completedFuture(result);
                                }
                                if (!shouldContinue(shouldContinue)) {
                                    return staleWriteResult(request, result.path);
                                }
                                if (CodecLabelTable.isLhdc(request.codecType)) {
                                    logUnconfirmedWrite("write.path_a.unconfirmed",
                                            request, "skip_bridge_retry_lhdc_guard");
                                    return CompletableFuture.completedFuture(result);
                                }
                                logUnconfirmedWrite("write.path_a.unconfirmed",
                                        request, "bridge_settings_root");
                                return setCodecViaBridgeOrFallback(request, shouldContinue);
                            });
                });
    }

    /** Toggle the platform high-quality audio preference (official SettingsLib path). */
    public CompletableFuture<WriteResult> setOptionalCodecs(String mac, boolean enable) {
        return setOptionalCodecs(mac, enable, () -> true);
    }

    public CompletableFuture<WriteResult> setOptionalCodecs(
            String mac, boolean enable, BooleanSupplier shouldContinue) {
        return applyDirectOptionalCodecs(mac, enable)
                .handle((ignored, error) -> error)
                .thenCompose(error -> {
                    if (error != null) {
                        logPathAOptionalFallback(mac, enable, unwrap(error),
                                "bridge_broadcast");
                        if (!shouldContinue(shouldContinue)) {
                            return staleWriteResult(mac, enable, WriteResult.Path.DIRECT_API);
                        }
                        return setOptionalCodecsViaBridgeOrBroadcast(
                                mac, enable, shouldContinue);
                    }
                    return awaitOptionalConfirmation(mac, enable, WriteResult.Path.DIRECT_API)
                            .thenCompose(result -> {
                                if (result.outcome == WriteResult.Outcome.CONFIRMED) {
                                    return CompletableFuture.completedFuture(result);
                                }
                                if (!shouldContinue(shouldContinue)) {
                                    return staleWriteResult(mac, enable, result.path);
                                }
                                logUnconfirmedOptional("write.path_a.optional_unconfirmed",
                                        mac, enable, "bridge_broadcast");
                                return setOptionalCodecsViaBridgeOrBroadcast(
                                        mac, enable, shouldContinue);
                            });
                });
    }

    private CompletableFuture<Void> applyDirectWrite(CodecRequest request) {
        try {
            reflect.setCodec(request);
            return CompletableFuture.completedFuture(null);
        } catch (Throwable t) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(t);
            return failed;
        }
    }

    private CompletableFuture<Void> applyDirectOptionalCodecs(String mac, boolean enable) {
        try {
            reflect.setOptionalCodecs(mac, enable);
            return CompletableFuture.completedFuture(null);
        } catch (Throwable t) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(t);
            return failed;
        }
    }

    private CompletableFuture<WriteResult> setOptionalCodecsViaBridgeOrBroadcast(
            String mac, boolean enable) {
        return setOptionalCodecsViaBridgeOrBroadcast(mac, enable, () -> true);
    }

    private CompletableFuture<WriteResult> setOptionalCodecsViaBridgeOrBroadcast(
            String mac, boolean enable, BooleanSupplier shouldContinue) {
        if (!shouldContinue(shouldContinue)) {
            return staleWriteResult(mac, enable, WriteResult.Path.SYSTEM_BRIDGE);
        }
        ICodecBridge bridge = ensureBridge();
        if (bridge != null) {
            try {
                int code = bridge.setOptionalCodecs(mac, enable);
                if (code == CodecRequest.RESULT_OK) {
                    return awaitOptionalConfirmation(mac, enable, WriteResult.Path.SYSTEM_BRIDGE)
                            .thenCompose(result -> {
                                if (result.outcome == WriteResult.Outcome.CONFIRMED) {
                                    return CompletableFuture.completedFuture(result);
                                }
                                if (!shouldContinue(shouldContinue)) {
                                    return staleWriteResult(mac, enable, result.path);
                                }
                                logUnconfirmedOptional("write.path_b.optional_unconfirmed",
                                        mac, enable, "broadcast");
                                return setOptionalCodecsViaBroadcast(
                                        mac, enable, shouldContinue);
                            });
                }
                MLog.w("Path-B bridge.setOptionalCodecs returned " + code);
            } catch (RemoteException re) {
                MLog.w("Path-B bridge.setOptionalCodecs RemoteException", re);
            }
        }
        return setOptionalCodecsViaBroadcast(mac, enable, shouldContinue);
    }

    private CompletableFuture<WriteResult> setOptionalCodecsViaBroadcast(
            String mac, boolean enable) {
        return setOptionalCodecsViaBroadcast(mac, enable, () -> true);
    }

    private CompletableFuture<WriteResult> setOptionalCodecsViaBroadcast(
            String mac, boolean enable, BooleanSupplier shouldContinue) {
        if (!shouldContinue(shouldContinue)) {
            return staleWriteResult(mac, enable, WriteResult.Path.SYSTEM_BROADCAST);
        }
        return CompletableFuture.supplyAsync(() ->
                        sendOptionalCodecSetBroadcast(mac, enable, CODEC_BROADCAST_TIMEOUT_MS))
                .thenCompose(code -> {
                    if (!shouldContinue(shouldContinue)) {
                        return staleWriteResult(mac, enable, WriteResult.Path.SYSTEM_BROADCAST);
                    }
                    if (code == CodecRequest.RESULT_OK) {
                        return awaitOptionalConfirmation(
                                mac, enable, WriteResult.Path.SYSTEM_BROADCAST);
                    }
                    MLog.w("Path-B broadcast setOptionalCodecs returned " + code);
                    return CompletableFuture.completedFuture(WriteResult.failed(
                            WriteResult.Path.SYSTEM_BROADCAST,
                            new IllegalStateException("optional codecs bridge unavailable")));
                });
    }

    private CompletableFuture<WriteResult> setCodecViaBridgeOrFallback(CodecRequest request) {
        return setCodecViaBridgeOrFallback(request, () -> true);
    }

    private CompletableFuture<WriteResult> setCodecViaBridgeOrFallback(
            CodecRequest request, BooleanSupplier shouldContinue) {
        if (!shouldContinue(shouldContinue)) {
            return staleWriteResult(request, WriteResult.Path.SYSTEM_BRIDGE);
        }
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
                                if (!shouldContinue(shouldContinue)) {
                                    return staleWriteResult(request, result.path);
                                }
                                logUnconfirmedWrite("write.path_b.unconfirmed",
                                        request, "settings_root");
                                return setCodecViaSettingsOrRoot(
                                        request, WriteResult.Path.SYSTEM_BRIDGE, shouldContinue);
                            });
                }
                MLog.w("Path-B bridge.setCodec returned " + code);
            } catch (RemoteException re) {
                MLog.w("Path-B bridge.setCodec RemoteException", re);
            }
        }

        if (!shouldContinue(shouldContinue)) {
            return staleWriteResult(request, WriteResult.Path.SYSTEM_BROADCAST);
        }
        return setCodecViaBroadcast(request)
                .thenCompose(code -> {
                    if (!shouldContinue(shouldContinue)) {
                        return staleWriteResult(request, WriteResult.Path.SYSTEM_BROADCAST);
                    }
                    if (code == CodecRequest.RESULT_OK) {
                        return awaitConfirmation(request, WriteResult.Path.SYSTEM_BROADCAST)
                                .thenCompose(result -> {
                                    if (result.outcome == WriteResult.Outcome.CONFIRMED) {
                                        return CompletableFuture.completedFuture(result);
                                    }
                                    if (!shouldContinue(shouldContinue)) {
                                        return staleWriteResult(request, result.path);
                                    }
                                    logUnconfirmedWrite("write.path_b.broadcast_unconfirmed",
                                            request, "settings_root");
                                    return setCodecViaSettingsOrRoot(
                                            request, WriteResult.Path.SYSTEM_BROADCAST,
                                            shouldContinue);
                                });
                    }
                    MLog.w("Path-B broadcast setCodec returned " + code);
                    return setCodecViaSettingsOrRoot(
                            request, WriteResult.Path.SYSTEM_BROADCAST, shouldContinue);
                });
    }

    private CompletableFuture<WriteResult> setCodecViaSettingsOrRoot(CodecRequest request) {
        return setCodecViaSettingsOrRoot(request, WriteResult.Path.SYSTEM_BRIDGE);
    }

    private CompletableFuture<WriteResult> setCodecViaSettingsOrRoot(
            CodecRequest request, WriteResult.Path failedPath) {
        return setCodecViaSettingsOrRoot(request, failedPath, () -> true);
    }

    private CompletableFuture<WriteResult> setCodecViaSettingsOrRoot(
            CodecRequest request,
            WriteResult.Path failedPath,
            BooleanSupplier shouldContinue) {
        if (!shouldContinue(shouldContinue)) {
            return staleWriteResult(request, failedPath);
        }
        if (CodecLabelTable.isLhdc(request.codecType)) {
            logUnconfirmedWrite("write.lhdc.unconfirmed",
                    request, "skip_settings_root_reconnect_guard");
            return CompletableFuture.completedFuture(WriteResult.failed(
                    failedPath,
                    new IllegalStateException("LHDC realtime write path unavailable")));
        }

        // Path C — Settings.Global from app context (needs WRITE_SECURE_SETTINGS, usually denied).
        boolean wrote = settingsFallback.apply(request);
        if (wrote) {
            MLog.event("write.path", "path", "SETTINGS_GLOBAL");
            return awaitConfirmation(request, WriteResult.Path.SETTINGS_GLOBAL);
        }

        // Path D — root shell. Run on a worker thread because exec'ing su can block for ~1 s
        // while the manager presents its prompt; we never want to stall the UI thread on it.
        CompletableFuture<WriteResult> rootFuture = new CompletableFuture<>();
        Thread worker = new Thread(() -> {
            try {
                if (!shouldContinue(shouldContinue)) {
                    rootFuture.complete(staleWriteResult(
                            request, WriteResult.Path.ROOT_SHELL).join());
                    return;
                }
                RootShellFallback.ApplyResult applyResult =
                        rootFallback.stageSettings(request, shouldContinue);
                if (applyResult == RootShellFallback.ApplyResult.SETTINGS_STAGED) {
                    MLog.event("write.path", "path", "ROOT_SHELL");
                    // Root only staged developer-option values. Confirm against the live codec;
                    // no successful shell exit is treated as proof that A2DP renegotiated.
                    if (!shouldContinue(shouldContinue)) {
                        rootFuture.complete(staleWriteResult(
                                request, WriteResult.Path.ROOT_SHELL).join());
                    } else {
                        awaitConfirmation(request, WriteResult.Path.ROOT_SHELL)
                                .whenComplete((confirmed, confirmError) -> {
                                    if (confirmError != null) {
                                        rootFuture.complete(WriteResult.failed(
                                                WriteResult.Path.ROOT_SHELL,
                                                unwrap(confirmError)));
                                    } else {
                                        rootFuture.complete(confirmed);
                                    }
                                });
                    }
                } else {
                    if (!shouldContinue(shouldContinue)) {
                        rootFuture.complete(staleWriteResult(
                                request, WriteResult.Path.ROOT_SHELL).join());
                        return;
                    }
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
        if (!registerCodecReplyReceiver(receiver)) return null;
        try {
            Intent intent = new Intent(CodecIpc.ACTION_QUERY_CODEC);
            intent.setPackage(CodecIpc.BLUETOOTH_PKG);
            intent.putExtra(CodecIpc.EXTRA_TOKEN, CodecIpc.TOKEN);
            intent.putExtra(CodecIpc.EXTRA_REQUEST_ID, requestId);
            intent.putExtra(CodecIpc.EXTRA_MAC, mac);
            if (!TrustedBroadcasts.send(context, intent)) {
                return null;
            }
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
        if (!registerCodecReplyReceiver(receiver)) return CodecRequest.RESULT_DENIED;
        try {
            Intent intent = new Intent(CodecIpc.ACTION_SET_CODEC);
            intent.setPackage(CodecIpc.BLUETOOTH_PKG);
            writeRequest(intent, requestId, request);
            if (!TrustedBroadcasts.send(context, intent)) {
                return CodecRequest.RESULT_ERROR;
            }
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

    private int sendOptionalCodecSetBroadcast(String mac, boolean enable, long timeoutMs) {
        if (mac == null || mac.isEmpty()) {
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
        if (!registerCodecReplyReceiver(receiver)) return CodecRequest.RESULT_DENIED;
        try {
            Intent intent = new Intent(CodecIpc.ACTION_SET_OPTIONAL_CODECS);
            intent.setPackage(CodecIpc.BLUETOOTH_PKG);
            intent.putExtra(CodecIpc.EXTRA_TOKEN, CodecIpc.TOKEN);
            intent.putExtra(CodecIpc.EXTRA_REQUEST_ID, requestId);
            intent.putExtra(CodecIpc.EXTRA_MAC, mac);
            intent.putExtra(CodecIpc.EXTRA_OPTIONAL_CODECS_ENABLE, enable);
            if (!TrustedBroadcasts.send(context, intent)) {
                return CodecRequest.RESULT_ERROR;
            }
            boolean delivered = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!delivered) {
                MLog.w("optional codec broadcast set timed out");
                return CodecRequest.RESULT_TIMEOUT;
            }
            return resultRef.get();
        } catch (Throwable t) {
            MLog.w("optional codec broadcast set failed", t);
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
                if (TrustedBroadcasts.supportsSenderIdentity()
                        && !TrustedBroadcasts.isTrustedSender(
                        context,
                        TrustedBroadcasts.captureSender(this),
                        CodecIpc.BLUETOOTH_PKG)) {
                    MLog.w("codec broadcast reply rejected: untrusted sender");
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

    private boolean registerCodecReplyReceiver(BroadcastReceiver receiver) {
        IntentFilter filter = new IntentFilter(CodecIpc.ACTION_CODEC_STATE);
        return TrustedBroadcasts.registerExportedReceiver(
                context,
                receiver,
                filter,
                TrustedBroadcasts.PERMISSION_BLUETOOTH_PRIVILEGED,
                ipcHandler);
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
        int[] selectableCodecTypes =
                intent.getIntArrayExtra(CodecIpc.EXTRA_SELECTABLE_CODEC_TYPES);
        int[] selectableCodecSampleRates =
                intent.getIntArrayExtra(CodecIpc.EXTRA_SELECTABLE_CODEC_SAMPLE_RATES);
        int[] selectableCodecBitsPerSample =
                intent.getIntArrayExtra(CodecIpc.EXTRA_SELECTABLE_CODEC_BITS_PER_SAMPLE);
        int[] selectableCodecChannelModes =
                intent.getIntArrayExtra(CodecIpc.EXTRA_SELECTABLE_CODEC_CHANNEL_MODES);
        long[] selectableCodecSpecific1Values =
                intent.getLongArrayExtra(CodecIpc.EXTRA_SELECTABLE_CODEC_SPECIFIC_1_VALUES);
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
                selectableCodecTypes,
                selectableCodecSampleRates,
                selectableCodecBitsPerSample,
                selectableCodecChannelModes,
                selectableCodecSpecific1Values,
                intent.getIntExtra(CodecIpc.EXTRA_OPTIONAL_CODECS_SUPPORTED, -1),
                intent.getIntExtra(CodecIpc.EXTRA_OPTIONAL_CODECS_ENABLED, -1),
                intent.getLongExtra(CodecIpc.EXTRA_READ_TIMESTAMP_MS, 0L));
    }

    /**
     * Wait for a {@code ACTION_CODEC_CONFIG_CHANGED} broadcast; if the active config matches the
     * request within the codec-specific confirmation window, resolve as
     * {@link WriteResult.Outcome#CONFIRMED};
     * otherwise re-read status and resolve with {@link WriteResult.Outcome#TIMEOUT_ROLLED_BACK}.
     */
    private CompletableFuture<WriteResult> awaitConfirmation(
            CodecRequest request, WriteResult.Path path) {
        CompletableFuture<WriteResult> future = new CompletableFuture<>();
        AtomicReference<BroadcastReceiver> receiverRef = new AtomicReference<>();
        AtomicBoolean readInFlight = new AtomicBoolean(false);
        AtomicBoolean timeoutExpired = new AtomicBoolean(false);

        Runnable probe = () -> {
            if (future.isDone() || !readInFlight.compareAndSet(false, true)) return;
            readStatusAsync(request.mac, s -> {
                readInFlight.set(false);
                if (future.isDone()) return;
                if (s != null && matches(s, request)) {
                    completeWith(future, WriteResult.confirmed(path, s), receiverRef);
                } else if (timeoutExpired.get()) {
                    MLog.event("write.timeout", "request", request, "live", String.valueOf(s));
                    completeWith(future, WriteResult.rolledBack(path, s), receiverRef);
                }
            });
        };

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (!ACTION_CODEC_CONFIG_CHANGED.equals(intent.getAction())) return;
                if (future.isDone()) return;
                mainHandler.post(probe);
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
            timeoutExpired.set(true);
            probe.run();
        }, confirmTimeoutMs(request));

        return future;
    }

    private static long confirmTimeoutMs(CodecRequest request) {
        if (request != null && CodecLabelTable.isLhdc(request.codecType)) {
            return LHDC_CONFIRM_TIMEOUT_MS;
        }
        return DEFAULT_CONFIRM_TIMEOUT_MS;
    }

    private CompletableFuture<WriteResult> awaitOptionalConfirmation(
            String mac, boolean enable, WriteResult.Path path) {
        CompletableFuture<WriteResult> future = new CompletableFuture<>();
        AtomicReference<BroadcastReceiver> receiverRef = new AtomicReference<>();
        AtomicBoolean readInFlight = new AtomicBoolean(false);
        AtomicBoolean timeoutExpired = new AtomicBoolean(false);

        Runnable probe = () -> {
            if (future.isDone() || !readInFlight.compareAndSet(false, true)) return;
            readStatusAsync(mac, s -> {
                readInFlight.set(false);
                if (future.isDone()) return;
                if (s != null && optionalMatches(s, enable)) {
                    completeWith(future, WriteResult.confirmed(path), receiverRef);
                } else if (timeoutExpired.get()) {
                    MLog.event("write.optional.timeout",
                            "mac", mac, "enable", enable, "live", String.valueOf(s));
                    completeWith(future, WriteResult.rolledBack(path, s), receiverRef);
                }
            });
        };

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (!ACTION_CODEC_CONFIG_CHANGED.equals(intent.getAction())) return;
                if (future.isDone()) return;
                mainHandler.post(probe);
            }
        };
        receiverRef.set(receiver);
        try {
            context.registerReceiver(receiver, new IntentFilter(ACTION_CODEC_CONFIG_CHANGED),
                    Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            context.registerReceiver(receiver, new IntentFilter(ACTION_CODEC_CONFIG_CHANGED));
        }

        mainHandler.postDelayed(() -> {
            if (future.isDone()) return;
            timeoutExpired.set(true);
            probe.run();
        }, OPTIONAL_CONFIRM_TIMEOUT_MS);

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

    private void readStatusAsync(String mac, StatusReadCallback callback) {
        statusHandler.post(() -> {
            CodecSnapshot snapshot = safeReadStatus(mac);
            mainHandler.post(() -> callback.onStatusRead(snapshot));
        });
    }

    private interface StatusReadCallback {
        void onStatusRead(CodecSnapshot snapshot);
    }

    private static boolean matches(CodecSnapshot snapshot, CodecRequest request) {
        if (snapshot.activeCodecType != request.codecType) {
            return false;
        }
        if (request.sampleRate != 0 && snapshot.activeSampleRate != request.sampleRate) {
            return false;
        }
        if (request.codecType == CodecLabelTable.CODEC_AAC) return true;
        if (CodecLabelTable.isLhdc(request.codecType)) {
            long active = snapshot.activeCodecSpecific1 & 0xFFL;
            long requested = request.codecSpecific1 & 0xFFL;
            if (active == requested) return true;
            if (lhdcCeilingMatch(requested, active)) {
                MLog.event("write.lhdc.ceiling.accepted",
                        "requested", requested,
                        "active", active,
                        "request", request,
                        "live", snapshot);
                return true;
            }
            return false;
        }
        return snapshot.activeCodecSpecific1 == request.codecSpecific1;
    }

    private static boolean lhdcCeilingMatch(long requested, long active) {
        return requested == CodecLabelTable.LHDC_QUALITY_FIXED_1000
                && active == CodecLabelTable.LHDC_QUALITY_FIXED_900;
    }

    private static boolean optionalMatches(CodecSnapshot snapshot, boolean enable) {
        if (snapshot == null) return false;
        if (enable) {
            if (snapshot.optionalCodecsEnabled == 0) return false;
            return snapshot.activeCodecType != CodecLabelTable.CODEC_SBC
                    && snapshot.activeCodecType != CodecLabelTable.CODEC_AAC;
        }
        if (snapshot.activeCodecType == CodecLabelTable.CODEC_SBC) {
            return true;
        }
        return snapshot.optionalCodecsEnabled == 0
                && snapshot.activeCodecType != CodecLabelTable.CODEC_AAC;
    }

    private static boolean shouldContinue(BooleanSupplier shouldContinue) {
        if (shouldContinue == null) return true;
        try {
            return shouldContinue.getAsBoolean();
        } catch (Throwable t) {
            MLog.w("write continuation guard failed", t);
            return false;
        }
    }

    private static CompletableFuture<WriteResult> staleWriteResult(
            CodecRequest request, WriteResult.Path path) {
        MLog.event("write.stale.skip_fallback", "path", path, "request", request);
        return CompletableFuture.completedFuture(WriteResult.failed(
                path, new IllegalStateException("stale codec write")));
    }

    private static CompletableFuture<WriteResult> staleWriteResult(
            String mac, boolean enable, WriteResult.Path path) {
        MLog.event("write.optional.stale.skip_fallback",
                "path", path, "mac", mac, "enable", enable);
        return CompletableFuture.completedFuture(WriteResult.failed(
                path, new IllegalStateException("stale optional codec write")));
    }

    private static void logPathAFallback(
            String op,
            CodecRequest request,
            Throwable cause,
            String next) {
        MLog.event("write.path_a.fallback",
                "op", op,
                "mac", A2dpRouteReadiness.redactMac(request.mac),
                "codec", hex(request.codecType),
                "rate", hex(request.sampleRate),
                "cause", compactThrowable(cause),
                "next", next);
    }

    private static void logPathAOptionalFallback(
            String mac,
            boolean enable,
            Throwable cause,
            String next) {
        MLog.event("write.path_a.fallback",
                "op", "setOptionalCodecs",
                "mac", A2dpRouteReadiness.redactMac(mac),
                "enable", enable,
                "cause", compactThrowable(cause),
                "next", next);
    }

    private static void logUnconfirmedWrite(
            String event,
            CodecRequest request,
            String next) {
        MLog.event(event,
                "mac", A2dpRouteReadiness.redactMac(request.mac),
                "codec", hex(request.codecType),
                "rate", hex(request.sampleRate),
                "next", next);
    }

    private static void logUnconfirmedOptional(
            String event,
            String mac,
            boolean enable,
            String next) {
        MLog.event(event,
                "mac", A2dpRouteReadiness.redactMac(mac),
                "enable", enable,
                "next", next);
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

    private static String compactThrowable(Throwable t) {
        if (t == null) return "unknown";
        if (t instanceof BluetoothCodecReflect.BluetoothCodecReflectException) {
            BluetoothCodecReflect.BluetoothCodecReflectException reflectError =
                    (BluetoothCodecReflect.BluetoothCodecReflectException) t;
            return sanitizeEventValue("reflect:"
                    + simpleClassName(reflectError.className)
                    + '#'
                    + reflectError.methodName
                    + ':'
                    + MLog.compactThrowable(t));
        }
        return MLog.compactThrowable(t);
    }

    private static String simpleClassName(String name) {
        if (name == null || name.isEmpty()) return "unknown";
        int idx = name.lastIndexOf('.');
        return idx >= 0 && idx + 1 < name.length() ? name.substring(idx + 1) : name;
    }

    private static String sanitizeEventValue(String value) {
        if (value == null || value.isEmpty()) return "unknown";
        String compact = value.replaceAll("\\s+", "_");
        return compact.length() <= 96 ? compact : compact.substring(0, 96);
    }

    private static String hex(int value) {
        return "0x" + Integer.toHexString(value);
    }

    private static boolean isCdmStatusBlock(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            String className = cur.getClass().getName();
            String message = cur.getMessage();
            if (className.contains("SecurityException")
                    && message != null
                    && message.toLowerCase(Locale.ROOT).contains("cdm association")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
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
