package xyz.melodylsp.codec.leaudio;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import xyz.melodylsp.codec.util.MLog;

/**
 * LE Audio toggle endpoint running inside {@code com.android.bluetooth}.
 *
 * <p>Using {@code com.oplus.wirelesssettings} as the privileged endpoint is too fragile on
 * ColorOS because the process is aggressively frozen while in the background. The bluetooth
 * process is already hot for the connected headset, so the same user-confirmed request can be
 * applied immediately here.</p>
 */
public final class BluetoothLeAudioBridge {

    private static final int PROFILE_LE_AUDIO = 22;
    private static final int PROFILE_A2DP = 2;
    private static final int CONNECTION_POLICY_FORBIDDEN = 0;
    private static final int CONNECTION_POLICY_ALLOWED = 100;
    private static final String ACTION_CHANGE_LEA_CONN_STATE =
            "oplus.bluetooth.device.action.CHANGE_LEA_CONN_STATE";
    private static final String EXTRA_CONN_STATE = "conn_state";
    private static final String OPLUS_COMPONENT_SAFE = "oplus.permission.OPLUS_COMPONENT_SAFE";

    private final Context context;
    private volatile boolean registered;
    private volatile Object leAudioProxy;
    private volatile Object a2dpProxy;

    public BluetoothLeAudioBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void register() {
        if (registered) return;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                handleAsync(intent);
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(LeAudioIpc.ACTION_SET_LE_AUDIO);
        filter.addAction(LeAudioIpc.ACTION_QUERY_LE_AUDIO);
        try {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            context.registerReceiver(receiver, filter);
        }
        registered = true;
        MLog.event("le.bt.receiver.registered");
    }

    private void handleAsync(Intent intent) {
        new Thread(() -> handle(intent), "MelodyCodecLsp-leaudio").start();
    }

    private void handle(Intent intent) {
        try {
            if (intent == null) return;
            if (!LeAudioIpc.TOKEN.equals(intent.getStringExtra(LeAudioIpc.EXTRA_TOKEN))) {
                MLog.w("LE Audio bluetooth request rejected: bad token");
                return;
            }
            String mac = intent.getStringExtra(LeAudioIpc.EXTRA_MAC);
            if (mac == null || mac.isEmpty()) return;
            String action = intent.getAction();
            if (LeAudioIpc.ACTION_SET_LE_AUDIO.equals(action)) {
                boolean enable = intent.getBooleanExtra(LeAudioIpc.EXTRA_ENABLE, false);
                boolean ok = applyLeAudio(mac, enable);
                MLog.event("le.bt.apply", "enable", enable, "ok", ok);
                replyLater(mac, ok, 400L);
                replyLater(mac, ok, 1800L);
                replyLater(mac, ok, 4000L);
            } else if (LeAudioIpc.ACTION_QUERY_LE_AUDIO.equals(action)) {
                replyState(mac, true);
            }
        } catch (Throwable t) {
            MLog.e("LE Audio bluetooth request failed", t);
        }
    }

    private boolean applyLeAudio(String mac, boolean enable) {
        Object proxy = acquireProxyBlocking();
        BluetoothDevice device = resolveDevice(mac);
        if (proxy == null || device == null) return false;
        boolean ok = setConnectionPolicy(proxy, device,
                enable ? CONNECTION_POLICY_ALLOWED : CONNECTION_POLICY_FORBIDDEN);
        if (!ok) {
            ok = setEnabled(proxy, device, enable);
        }
        if (ok) {
            sendTransportSwitch(device, enable);
            if (!enable) {
                reconnectA2dpLater(device, 1200L);
                reconnectA2dpLater(device, 3200L);
            }
        }
        return ok;
    }

    private synchronized Object acquireProxyBlocking() {
        return acquireProfileProxyBlocking(PROFILE_LE_AUDIO);
    }

    private synchronized Object acquireA2dpProxyBlocking() {
        return acquireProfileProxyBlocking(PROFILE_A2DP);
    }

    private synchronized Object acquireProfileProxyBlocking(int targetProfile) {
        Object current = targetProfile == PROFILE_LE_AUDIO ? leAudioProxy : a2dpProxy;
        if (current != null) return current;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return null;
        CompletableFuture<Object> future = new CompletableFuture<>();
        BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile == targetProfile && proxy != null) {
                    future.complete(proxy);
                }
            }

            @Override
            public void onServiceDisconnected(int profile) {
                if (profile == PROFILE_LE_AUDIO) {
                    leAudioProxy = null;
                } else if (profile == PROFILE_A2DP) {
                    a2dpProxy = null;
                }
            }
        };
        try {
            if (!adapter.getProfileProxy(context, listener, targetProfile)) {
                MLog.w("le.bt.getProfileProxy returned false profile=" + targetProfile);
                return null;
            }
            Object proxy = future.get(2000L, TimeUnit.MILLISECONDS);
            if (targetProfile == PROFILE_LE_AUDIO) {
                leAudioProxy = proxy;
            } else if (targetProfile == PROFILE_A2DP) {
                a2dpProxy = proxy;
            }
            return proxy;
        } catch (Throwable t) {
            MLog.w("le.bt.acquireProxy failed profile=" + targetProfile, t);
            return null;
        }
    }

    private static BluetoothDevice resolveDevice(String mac) {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            return adapter != null ? adapter.getRemoteDevice(mac) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean setConnectionPolicy(Object proxy, BluetoothDevice device, int policy) {
        try {
            Method m = findMethod(proxy.getClass(), "setConnectionPolicy",
                    BluetoothDevice.class, int.class);
            if (m == null) return false;
            m.setAccessible(true);
            Object out = m.invoke(proxy, device, policy);
            boolean ok = !(out instanceof Boolean) || (Boolean) out;
            MLog.event("le.bt.setConnectionPolicy", "policy", policy, "ok", ok);
            return ok;
        } catch (Throwable t) {
            MLog.w("le.bt.setConnectionPolicy failed", t);
            return false;
        }
    }

    private static boolean setEnabled(Object proxy, BluetoothDevice device, boolean enable) {
        try {
            Method m = findMethod(proxy.getClass(), "setEnabled",
                    BluetoothDevice.class, boolean.class);
            if (m == null) return false;
            m.setAccessible(true);
            Object out = m.invoke(proxy, device, enable);
            boolean ok = !(out instanceof Boolean) || (Boolean) out;
            MLog.event("le.bt.setEnabled", "enable", enable, "ok", ok);
            return ok;
        } catch (Throwable t) {
            MLog.w("le.bt.setEnabled failed", t);
            return false;
        }
    }

    private void sendTransportSwitch(BluetoothDevice device, boolean connect) {
        Intent intent = new Intent(ACTION_CHANGE_LEA_CONN_STATE);
        intent.setPackage(LeAudioIpc.BLUETOOTH_PKG);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(EXTRA_CONN_STATE, connect ? "connect" : "disconnect");
        try {
            context.sendBroadcast(intent, OPLUS_COMPONENT_SAFE);
            MLog.event("le.bt.transport.sent", "connect", connect);
        } catch (Throwable t) {
            try {
                context.sendBroadcast(intent);
                MLog.event("le.bt.transport.sent", "connect", connect, "permission", "fallback");
            } catch (Throwable t2) {
                MLog.w("le.bt.transport send failed", t2);
            }
        }
    }

    private void reconnectA2dpLater(BluetoothDevice device, long delayMs) {
        new Thread(() -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            reconnectA2dp(device);
        }, "MelodyCodecLsp-a2dp-reconnect").start();
    }

    private void reconnectA2dp(BluetoothDevice device) {
        Object proxy = acquireA2dpProxyBlocking();
        if (proxy == null || device == null) {
            MLog.w("le.bt.a2dp.reconnect skipped");
            return;
        }
        boolean policyOk = setConnectionPolicy(proxy, device, CONNECTION_POLICY_ALLOWED);
        Boolean connectOk = invokeBoolean(proxy, "connect",
                new Class[]{BluetoothDevice.class}, new Object[]{device});
        int state = getProfileConnectionState(proxy, device);
        MLog.event("le.bt.a2dp.reconnect",
                "policyOk", policyOk,
                "connectOk", connectOk,
                "state", state);
    }

    private void replyLater(String mac, boolean ok, long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        replyState(mac, ok);
    }

    private void replyState(String mac, boolean ok) {
        Object proxy = acquireProxyBlocking();
        boolean supported = proxy != null;
        boolean enabled = supported && isLeAudioEnabled(proxy, mac);
        boolean connected = supported && isLeAudioConnected(proxy, mac);
        Intent reply = new Intent(LeAudioIpc.ACTION_LE_AUDIO_STATE);
        reply.setPackage(LeAudioIpc.MELODY_PKG);
        reply.putExtra(LeAudioIpc.EXTRA_TOKEN, LeAudioIpc.TOKEN);
        reply.putExtra(LeAudioIpc.EXTRA_MAC, mac);
        reply.putExtra(LeAudioIpc.EXTRA_SUPPORTED, supported);
        reply.putExtra(LeAudioIpc.EXTRA_ENABLED, enabled);
        reply.putExtra(LeAudioIpc.EXTRA_CONNECTED, connected);
        reply.putExtra(LeAudioIpc.EXTRA_OK, ok);
        try {
            context.sendBroadcast(reply);
            MLog.event("le.bt.reply",
                    "supported", supported,
                    "enabled", enabled,
                    "connected", connected,
                    "ok", ok);
        } catch (Throwable t) {
            MLog.w("le.bt.reply send failed", t);
        }
    }

    private static boolean isLeAudioEnabled(Object proxy, String mac) {
        BluetoothDevice device = resolveDevice(mac);
        if (proxy == null || device == null) return false;
        Integer policy = getConnectionPolicy(proxy, device);
        if (policy != null) return policy >= CONNECTION_POLICY_ALLOWED;
        Boolean enabled = invokeBoolean(proxy, "isEnabled",
                new Class[]{BluetoothDevice.class}, new Object[]{device});
        if (enabled != null) return enabled;
        try {
            return getProfileConnectionState(proxy, device) == BluetoothProfile.STATE_CONNECTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isLeAudioConnected(Object proxy, String mac) {
        BluetoothDevice device = resolveDevice(mac);
        return proxy != null
                && device != null
                && getProfileConnectionState(proxy, device) == BluetoothProfile.STATE_CONNECTED;
    }

    private static int getProfileConnectionState(Object proxy, BluetoothDevice device) {
        try {
            Method m = findMethod(proxy.getClass(), "getConnectionState", BluetoothDevice.class);
            if (m == null) return BluetoothProfile.STATE_DISCONNECTED;
            m.setAccessible(true);
            Object out = m.invoke(proxy, device);
            return out instanceof Integer ? (Integer) out : BluetoothProfile.STATE_DISCONNECTED;
        } catch (Throwable t) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }
    }

    private static Integer getConnectionPolicy(Object proxy, BluetoothDevice device) {
        try {
            Method m = findMethod(proxy.getClass(), "getConnectionPolicy", BluetoothDevice.class);
            if (m == null) return null;
            m.setAccessible(true);
            Object out = m.invoke(proxy, device);
            return out instanceof Integer ? (Integer) out : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Boolean invokeBoolean(
            Object target, String name, Class<?>[] params, Object[] args) {
        try {
            Method m = findMethod(target.getClass(), name, params);
            if (m == null) return null;
            m.setAccessible(true);
            Object out = m.invoke(target, args);
            return out instanceof Boolean ? (Boolean) out : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findMethod(Class<?> startCls, String name, Class<?>... params) {
        Class<?> cls = startCls;
        while (cls != null && cls != Object.class) {
            try {
                return cls.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
                cls = cls.getSuperclass();
            }
        }
        return null;
    }
}
