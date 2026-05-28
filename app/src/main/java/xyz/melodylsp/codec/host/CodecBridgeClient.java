package xyz.melodylsp.codec.host;

import android.bluetooth.BluetoothA2dp;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import xyz.melodylsp.codec.bridge.CodecRequest;
import xyz.melodylsp.codec.bridge.CodecSnapshot;
import xyz.melodylsp.codec.bridge.ICodecBridge;
import xyz.melodylsp.codec.bridge.ICodecBridgeListener;
import xyz.melodylsp.codec.bt.BluetoothCodecReflect;
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

    private final Context context;
    private final BluetoothCodecReflect reflect;
    private final SettingsGlobalFallback settingsFallback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final CopyOnWriteArrayList<SnapshotListener> listeners = new CopyOnWriteArrayList<>();
    private volatile ICodecBridge cachedBridge;
    private volatile ICodecBridgeListener registeredListener;

    public CodecBridgeClient(
            Context context,
            BluetoothCodecReflect reflect,
            SettingsGlobalFallback settingsFallback) {
        this.context = context.getApplicationContext();
        this.reflect = reflect;
        this.settingsFallback = settingsFallback;
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
            return reflect.readStatus(mac);
        } catch (Throwable t) {
            MLog.w("getStatus(" + mac + ") failed via direct API, trying bridge", t);
            ICodecBridge bridge = ensureBridge();
            if (bridge != null) {
                try {
                    return bridge.getStatus(mac);
                } catch (RemoteException re) {
                    MLog.w("bridge.getStatus failed", re);
                }
            }
            return null;
        }
    }

    /** Writes the request and resolves with the eventual outcome (confirmed / rolled back). */
    public CompletableFuture<WriteResult> setCodec(CodecRequest request) {
        // Path A.
        try {
            reflect.setCodec(request);
            return awaitConfirmation(request, WriteResult.Path.DIRECT_API);
        } catch (BluetoothCodecReflect.BluetoothCodecReflectException e) {
            MLog.w("Path-A setCodec failed: " + e.className + "#" + e.methodName);
        } catch (Throwable t) {
            MLog.w("Path-A setCodec failed", t);
        }

        // Path B.
        ICodecBridge bridge = ensureBridge();
        if (bridge != null) {
            try {
                int code = bridge.setCodec(request);
                if (code == CodecRequest.RESULT_OK) {
                    return awaitConfirmation(request, WriteResult.Path.SYSTEM_BRIDGE);
                }
                MLog.w("Path-B bridge.setCodec returned " + code);
            } catch (RemoteException re) {
                MLog.w("Path-B bridge.setCodec RemoteException", re);
            }
        }

        // Path C.
        boolean wrote = settingsFallback.apply(request);
        if (wrote) {
            MLog.event("write.path", "path", "SETTINGS_GLOBAL");
            return CompletableFuture.completedFuture(WriteResult.confirmed(WriteResult.Path.SETTINGS_GLOBAL));
        }
        MLog.e("All write paths failed; nothing was applied");
        return CompletableFuture.completedFuture(
                WriteResult.failed(WriteResult.Path.SETTINGS_GLOBAL,
                        new IllegalStateException("no usable write path")));
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
            return reflect.readStatus(mac);
        } catch (Throwable t) {
            MLog.w("safeReadStatus failed", t);
            return null;
        }
    }

    private static boolean matches(CodecSnapshot snapshot, CodecRequest request) {
        return snapshot.activeCodecType == request.codecType
                && snapshot.activeSampleRate == request.sampleRate
                && snapshot.activeCodecSpecific1 == request.codecSpecific1;
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

    // Package-private accessor exposed only for testing helpers.
    BluetoothA2dp peekProxy() {
        return reflect.acquireProxyBlocking();
    }
}
