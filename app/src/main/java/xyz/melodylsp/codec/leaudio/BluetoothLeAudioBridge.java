package xyz.melodylsp.codec.leaudio;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.ParcelUuid;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import xyz.melodylsp.codec.util.MLog;
import xyz.melodylsp.codec.util.TrustedBroadcasts;

/**
 * LE Audio toggle endpoint running inside {@code com.android.bluetooth}.
 *
 * <p>Using {@code com.oplus.wirelesssettings} as the privileged endpoint is too fragile on
 * ColorOS because the process is aggressively frozen while in the background. The bluetooth
 * process is already hot for the connected headset, so the same user-confirmed request can be
 * applied immediately here.</p>
 */
public final class BluetoothLeAudioBridge {

    /** Sink for the module's game-latency switch state; set by the bluetooth-side controller. */
    public interface GameLatencyListener {
        void onGameLatencyStateChanged(boolean lowLatencyEnabled, boolean autoLeaEnabled);

        default void onManualLeAudioChanged(String mac, boolean enable) { }
    }

    private static final int PROFILE_LE_AUDIO = 22;
    private static final int PROFILE_A2DP = 2;
    private static final int CONNECTION_POLICY_FORBIDDEN = 0;
    private static final int CONNECTION_POLICY_ALLOWED = 100;
    private static final String ACTION_CHANGE_LEA_CONN_STATE =
            "oplus.bluetooth.device.action.CHANGE_LEA_CONN_STATE";
    private static final String EXTRA_CONN_STATE = "conn_state";
    private static final String OPLUS_COMPONENT_SAFE = "oplus.permission.OPLUS_COMPONENT_SAFE";
    private static final long[] LE_RECONNECT_DELAYS_MS = {600L, 2_500L, 6_000L};
    private static final long LE_RECONNECT_COOLDOWN_MS = 12_000L;
    private static final UUID UUID_LE_AUDIO =
            UUID.fromString("0000184e-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_HAS =
            UUID.fromString("00001854-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private volatile boolean registered;
    private volatile Object leAudioProxy;
    private volatile Object a2dpProxy;
    private volatile GameLatencyListener gameLatencyListener;
    private final Object writeLock = new Object();
    private final ReconnectGate reconnectGate = new ReconnectGate();
    private final A2dpRestoreGate a2dpRestoreGate = new A2dpRestoreGate();
    private final ExecutorService requestExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread worker = new Thread(r, "MelodyCodecLsp-leaudio");
        worker.setDaemon(true);
        return worker;
    });

    public BluetoothLeAudioBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setGameLatencyListener(GameLatencyListener listener) {
        this.gameLatencyListener = listener;
    }

    public synchronized void register() {
        if (registered) return;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                TrustedBroadcasts.SenderIdentity sender =
                        TrustedBroadcasts.captureSender(this);
                handleAsync(intent, sender);
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(LeAudioIpc.ACTION_SET_LE_AUDIO);
        filter.addAction(LeAudioIpc.ACTION_QUERY_LE_AUDIO);
        filter.addAction(LeAudioIpc.ACTION_SET_GAME_LATENCY_STATE);
        if (!TrustedBroadcasts.registerExportedReceiver(
                context,
                receiver,
                filter,
                TrustedBroadcasts.PERMISSION_OPLUS_COMPONENT_SAFE,
                null)) {
            MLog.w("le.bt.receiver registration rejected: signature permission unavailable");
            return;
        }
        registered = true;
        MLog.event("le.bt.receiver.registered");
    }

    private void handleAsync(
            Intent intent, TrustedBroadcasts.SenderIdentity sender) {
        requestExecutor.execute(() -> handle(intent, sender));
    }

    private void handle(Intent intent, TrustedBroadcasts.SenderIdentity sender) {
        try {
            if (intent == null) return;
            if (!LeAudioIpc.TOKEN.equals(intent.getStringExtra(LeAudioIpc.EXTRA_TOKEN))) {
                MLog.w("LE Audio bluetooth request rejected: protocol mismatch");
                return;
            }
            String action = intent.getAction();
            if (!isAuthorizedRequest(action, sender)) return;
            if (LeAudioIpc.ACTION_SET_GAME_LATENCY_STATE.equals(action)) {
                boolean lowLatency = intent.getBooleanExtra(
                        LeAudioIpc.EXTRA_GAME_LOW_LATENCY_ENABLED, true);
                boolean autoLea = intent.getBooleanExtra(
                        LeAudioIpc.EXTRA_GAME_AUTO_LEA_ENABLED, false);
                GameLatencyListener listener = gameLatencyListener;
                if (listener != null) {
                    listener.onGameLatencyStateChanged(lowLatency, autoLea);
                }
                MLog.event("le.bt.game_state", "lowLatency", lowLatency,
                        "autoLea", autoLea, "listener", listener != null);
                return;
            }
            String mac = intent.getStringExtra(LeAudioIpc.EXTRA_MAC);
            if (mac == null || mac.isEmpty()) return;
            if (LeAudioIpc.ACTION_SET_LE_AUDIO.equals(action)) {
                boolean enable = intent.getBooleanExtra(LeAudioIpc.EXTRA_ENABLE, false);
                GameLatencyListener listener = gameLatencyListener;
                if (listener != null) {
                    BluetoothDevice requested = resolveDevice(mac);
                    String manualMac = requested == null
                            ? mac : resolveBrDevice(requested).getAddress();
                    listener.onManualLeAudioChanged(manualMac, enable);
                }
                boolean ok = applyLeAudio(mac, enable);
                MLog.event("le.bt.apply", "enable", enable, "ok", ok);
                scheduleReply(mac, ok, 400L);
                scheduleReply(mac, ok, 1800L);
                scheduleReply(mac, ok, 4000L);
            } else if (LeAudioIpc.ACTION_QUERY_LE_AUDIO.equals(action)) {
                replyState(mac, true);
                if (intent.getBooleanExtra(
                        LeAudioIpc.EXTRA_REPAIR_CONNECTION, false)) {
                    scheduleLeReconnect(mac, "state_event", false);
                }
            }
        } catch (Throwable t) {
            MLog.e("LE Audio bluetooth request failed", t);
        }
    }

    private boolean isAuthorizedRequest(
            String action, TrustedBroadcasts.SenderIdentity sender) {
        if (!TrustedBroadcasts.supportsSenderIdentity()) {
            // Android 12/13 rely on the OPlus signature permission required at registration time.
            return LeAudioIpc.ACTION_SET_LE_AUDIO.equals(action)
                    || LeAudioIpc.ACTION_QUERY_LE_AUDIO.equals(action)
                    || LeAudioIpc.ACTION_SET_GAME_LATENCY_STATE.equals(action);
        }
        boolean trusted = TrustedBroadcasts.isTrustedSender(
                context, sender, LeAudioIpc.MELODY_PKG);
        if (trusted) return true;

        if (LeAudioIpc.ACTION_SET_LE_AUDIO.equals(action)
                || LeAudioIpc.ACTION_SET_GAME_LATENCY_STATE.equals(action)) {
            MLog.w("LE Audio bluetooth write rejected: sender identity unavailable or untrusted");
            return false;
        }
        MLog.w("LE Audio bluetooth query rejected: untrusted sender");
        return false;
    }

    private boolean applyLeAudio(String mac, boolean enable) {
        synchronized (writeLock) {
            return applyLeAudioLocked(mac, enable, () -> true);
        }
    }

    private boolean applyLeAudioLocked(
            String mac, boolean enable, BooleanSupplier stillCurrent) {
        BluetoothDevice requested = resolveDevice(mac);
        if (requested == null) return false;
        BluetoothDevice brDevice = resolveBrDevice(requested);
        long restoreGeneration = a2dpRestoreGate.advance(brDevice.getAddress());
        Object proxy = acquireProxyBlocking();
        if (proxy == null) return false;
        // Settings checks any enabled LE member. Resolve before disconnecting the first one,
        // using the vendor BR/LE mapping and the stack's group rather than guessing MACs.
        List<BluetoothDevice> devices = resolveLeAudioDevices(requested);
        // Proxy acquisition and member resolution can block. Recheck immediately before writing.
        if (!stillCurrent.getAsBoolean()) return false;
        boolean ok = true;
        for (BluetoothDevice device : devices) {
            String targetMac = device.getAddress();
            if (!enable) reconnectGate.cancel(targetMac);
            boolean applied = setConnectionPolicy(proxy, device,
                    enable ? CONNECTION_POLICY_ALLOWED : CONNECTION_POLICY_FORBIDDEN);
            if (!applied) applied = setEnabled(proxy, device, enable);
            ok &= applied;
            if (applied && enable) {
                int stateBefore = getProfileConnectionState(proxy, device);
                Boolean connectOk = null;
                boolean transportSent = false;
                if (stateBefore != BluetoothProfile.STATE_CONNECTED
                        && stateBefore != BluetoothProfile.STATE_CONNECTING) {
                    connectOk = invokeBoolean(proxy, "connect",
                            new Class[]{BluetoothDevice.class}, new Object[]{device});
                    if (!Boolean.TRUE.equals(connectOk)) {
                        // Only use the vendor transport action when the profile API is absent or
                        // explicitly refuses the request. Broadcasting it unconditionally can
                        // wake ColorOS Nearby/Live discovery UI.
                        transportSent = sendTransportSwitch(device, true);
                    }
                }
                MLog.event("le.bt.connect.initial",
                        "stateBefore", stateBefore,
                        "connectOk", connectOk,
                        "transportSent", transportSent);
                scheduleLeReconnect(targetMac, "explicit_enable", true);
            }
        }
        if (ok && !enable) {
            MLog.event("le.bt.transport.skipped",
                    "connect", false,
                    "reason", "policy_disconnect");
            reconnectA2dpLater(brDevice, restoreGeneration, 2400L, 1);
            reconnectA2dpLater(brDevice, restoreGeneration, 4600L, 2);
            reconnectA2dpLater(brDevice, restoreGeneration, 8000L, 3);
        }
        return ok;
    }

    /** In-process entry point for the game auto-LEA controller (no broadcast hop). */
    public boolean setLeAudioEnabledForGame(String mac, boolean enable) {
        return setLeAudioEnabledForGame(mac, enable, () -> true);
    }

    /** Recheck the controller's session/ticket only after acquiring the common writer lock. */
    public boolean setLeAudioEnabledForGame(
            String mac, boolean enable, BooleanSupplier stillCurrent) {
        boolean ok;
        synchronized (writeLock) {
            if (stillCurrent == null || !stillCurrent.getAsBoolean()) return false;
            ok = applyLeAudioLocked(mac, enable, stillCurrent);
        }
        MLog.event("le.bt.game.apply", "enable", enable, "ok", ok);
        scheduleReply(mac, ok, 400L);
        scheduleReply(mac, ok, 1800L);
        scheduleReply(mac, ok, 4000L);
        return ok;
    }

    /** Authoritative profile enablement read used by the game auto-LEA controller. */
    public boolean isLeAudioEnabledNow(String mac) {
        return Boolean.TRUE.equals(readLeAudioEnabledForGame(mac));
    }

    /** Null means unavailable/unknown; connection state must not stand in for user policy. */
    public Boolean readLeAudioEnabledForGame(String mac) {
        synchronized (writeLock) {
            try {
                Object proxy = acquireProxyBlocking();
                BluetoothDevice device = resolveDevice(mac);
                if (proxy == null || device == null) return null;
                List<Boolean> states = new ArrayList<>();
                for (BluetoothDevice member : resolveLeAudioDevices(device)) {
                    states.add(readLeAudioEnabled(proxy, member));
                }
                return anyLeAudioEnabled(states);
            } catch (Throwable t) {
                MLog.w("le.bt.game.read failed", t);
                return null;
            }
        }
    }

    /** Conservative positive check matching OPlus isLeaDevice: LE/dual type plus ASCS or HAS. */
    public boolean isLeAudioSupportedForGame(String mac) {
        synchronized (writeLock) {
            try {
                BluetoothDevice device = resolveDevice(mac);
                if (device == null || acquireProxyBlocking() == null) return false;
                for (BluetoothDevice member : resolveLeAudioDevices(device)) {
                    try {
                        ParcelUuid[] parcelUuids = member.getUuids();
                        if (parcelUuids == null) continue;
                        UUID[] uuids = new UUID[parcelUuids.length];
                        for (int i = 0; i < parcelUuids.length; i++) {
                            uuids[i] = parcelUuids[i] == null ? null : parcelUuids[i].getUuid();
                        }
                        if (hasLeAudioCapability(member.getType(), uuids)) return true;
                    } catch (Throwable ignored) { }
                }
            } catch (Throwable ignored) {
                // Missing proxy, mapping or capability read must never unlock an unknown device.
            }
            return false;
        }
    }

    /**
     * X4-class "LE Audio + classic coexistence" support for this device.
     *
     * <p>The SmartAudio callback reports feature bit 0x4000 and the vendor dual-mode manager
     * mirrors it into the {@code dual_mode_support} preference (key = device address, absent =
     * not supported, and the system removes the key when a headset reports no support). That
     * same signal drives the official game LE Audio chain, so the module stays hands-off while
     * it is set.</p>
     *
     * @return {@code TRUE} when confirmed, {@code FALSE} when readable but not reported,
     *         {@code null} when the check itself failed.
     */
    public Boolean readLeaCoexistSupportedForGame(String mac) {
        synchronized (writeLock) {
            try {
                BluetoothDevice device = resolveDevice(mac);
                if (device == null) return null;
                LinkedHashSet<String> candidates = new LinkedHashSet<>();
                candidates.add(device.getAddress());
                BluetoothDevice br = resolveBrDevice(device);
                if (br != null) candidates.add(br.getAddress());
                for (BluetoothDevice member : resolveLeAudioDevices(device)) {
                    if (member != null) candidates.add(member.getAddress());
                }
                SharedPreferences prefs = context.getSharedPreferences(
                        "dual_mode_support", Context.MODE_PRIVATE);
                return coexistFromPrefs(prefs.getAll(), candidates);
            } catch (Throwable t) {
                MLog.w("le.bt.coexist read failed", t);
                return null;
            }
        }
    }

    /** Pure helper: an explicit {@code true} entry for any candidate address wins. */
    static Boolean coexistFromPrefs(Map<String, ?> values, Collection<String> addresses) {
        if (values == null || addresses == null || addresses.isEmpty()) return null;
        for (String address : addresses) {
            if (address == null) continue;
            Object value = values.get(address);
            if (value instanceof Boolean && (Boolean) value) return Boolean.TRUE;
        }
        return Boolean.FALSE;
    }

    /** Authoritative profile connection read used by the game auto-LEA controller. */
    public boolean isLeAudioConnectedNow(String mac) {
        synchronized (writeLock) {
            Object proxy = acquireProxyBlocking();
            BluetoothDevice device = resolveDevice(mac);
            if (proxy == null || device == null) return false;
            for (BluetoothDevice member : resolveLeAudioDevices(device)) {
                if (getProfileConnectionState(proxy, member) == BluetoothProfile.STATE_CONNECTED) {
                    return true;
                }
            }
            return false;
        }
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

    private Object resolveOplusLeAdapterService() {
        try {
            Class<?> cls = Class.forName("com.android.bluetooth.btservice.AdapterService",
                    false, context.getClassLoader());
            return cls.getField("mOplusLeAdapterService").get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private BluetoothDevice resolveBrDevice(BluetoothDevice device) {
        Object br = invokeObject(resolveOplusLeAdapterService(), "findBrDevice",
                new Class[]{String.class}, new Object[]{device.getAddress()});
        return br instanceof BluetoothDevice ? (BluetoothDevice) br : device;
    }

    private Object resolveLeAudioService() {
        try {
            ClassLoader loader = context.getClassLoader();
            Class<?> cls = Class.forName("com.android.bluetooth.le_audio.LeAudioService",
                    false, loader);
            Method getter = findMethod(cls, "getLeAudioService");
            if (getter != null) {
                getter.setAccessible(true);
                return getter.invoke(null);
            }
            // Android 17 obtains profiles from AdapterService as Optional<Profile>.
            Class<?> adapterCls = Class.forName("com.android.bluetooth.btservice.AdapterService",
                    false, loader);
            getter = findMethod(adapterCls, "deprecatedGetAdapterService");
            if (getter == null) getter = findMethod(adapterCls, "getAdapterService");
            if (getter == null) return null;
            getter.setAccessible(true);
            Object service = invokeObject(getter.invoke(null), "getLeAudioService",
                    new Class[0], new Object[0]);
            return service instanceof Optional ? ((Optional<?>) service).orElse(null) : service;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private List<BluetoothDevice> resolveLeAudioDevices(BluetoothDevice device) {
        Map<String, BluetoothDevice> devices = new LinkedHashMap<>();
        devices.put(device.getAddress(), device);
        BluetoothDevice br = resolveBrDevice(device);
        addLeAudioDevices(devices, invokeObject(resolveOplusLeAdapterService(),
                "findLeAudioDevices", new Class[]{String.class},
                new Object[]{br.getAddress()}));
        Object service = resolveLeAudioService();
        // Take a copy because adding peers can grow the map while iterating.
        for (BluetoothDevice member : new ArrayList<>(devices.values())) {
            Object group = invokeObject(service, "getGroupId",
                    new Class[]{BluetoothDevice.class}, new Object[]{member});
            if (group instanceof Integer && (Integer) group >= 0) {
                addLeAudioDevices(devices, invokeObject(service, "getGroupDevices",
                        new Class[]{int.class}, new Object[]{group}));
            }
        }
        return new ArrayList<>(devices.values());
    }

    private static void addLeAudioDevices(Map<String, BluetoothDevice> devices, Object members) {
        if (!(members instanceof Collection)) return;
        for (Object member : (Collection<?>) members) {
            if (!(member instanceof BluetoothDevice)) continue;
            BluetoothDevice device = (BluetoothDevice) member;
            String address = device.getAddress();
            if (BluetoothAdapter.checkBluetoothAddress(address)
                    && !"00:00:00:00:00:00".equals(address)) {
                devices.put(address, device);
            }
        }
    }

    private static Object invokeObject(
            Object target, String name, Class<?>[] params, Object[] args) {
        if (target == null) return null;
        try {
            Method method = findMethod(target.getClass(), name, params);
            if (method == null) return null;
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (Throwable ignored) {
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

    private boolean sendTransportSwitch(BluetoothDevice device, boolean connect) {
        Intent intent = new Intent(ACTION_CHANGE_LEA_CONN_STATE);
        intent.setPackage(LeAudioIpc.BLUETOOTH_PKG);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(EXTRA_CONN_STATE, connect ? "connect" : "disconnect");
        try {
            context.sendBroadcast(intent, OPLUS_COMPONENT_SAFE);
            MLog.event("le.bt.transport.sent", "connect", connect);
            return true;
        } catch (Throwable t) {
            try {
                context.sendBroadcast(intent);
                MLog.event("le.bt.transport.sent", "connect", connect, "permission", "fallback");
                return true;
            } catch (Throwable t2) {
                MLog.w("le.bt.transport send failed", t2);
                return false;
            }
        }
    }

    private void scheduleLeReconnect(String mac, String reason, boolean force) {
        synchronized (writeLock) {
            scheduleLeReconnectLocked(mac, reason, force);
        }
    }

    private void scheduleLeReconnectLocked(String mac, String reason, boolean force) {
        if (mac == null) return;
        Object proxy = acquireProxyBlocking();
        if (proxy == null
                || !isLeAudioEnabled(proxy, mac)
                || isLeAudioConnected(proxy, mac)) {
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        long generation = reconnectGate.tryStart(mac, now, force);
        if (generation < 0L) return;
        MLog.event("le.bt.reconnect.schedule",
                "reason", reason,
                "generation", generation,
                "attempts", LE_RECONNECT_DELAYS_MS.length);
        for (int i = 0; i < LE_RECONNECT_DELAYS_MS.length; i++) {
            final int attempt = i + 1;
            final boolean lastAttempt = i == LE_RECONNECT_DELAYS_MS.length - 1;
            Thread worker = new Thread(() -> {
                sleepQuietly(LE_RECONNECT_DELAYS_MS[attempt - 1]);
                reconnectLeAudio(mac, generation, attempt, lastAttempt);
            }, "MelodyCodecLsp-le-reconnect-" + attempt);
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void reconnectLeAudio(
            String mac, long generation, int attempt, boolean lastAttempt) {
        synchronized (writeLock) {
            reconnectLeAudioLocked(mac, generation, attempt, lastAttempt);
        }
    }

    private void reconnectLeAudioLocked(
            String mac, long generation, int attempt, boolean lastAttempt) {
        if (!reconnectGate.isCurrent(mac, generation)) return;
        Object proxy = acquireProxyBlocking();
        BluetoothDevice device = resolveDevice(mac);
        if (proxy == null || device == null) {
            finishReconnectIfLast(mac, generation, attempt, lastAttempt, "proxy_unavailable");
            return;
        }
        Integer policy = getConnectionPolicy(proxy, device);
        int stateBefore = getProfileConnectionState(proxy, device);
        if (policy != null && policy < CONNECTION_POLICY_ALLOWED) {
            reconnectGate.cancel(mac);
            MLog.event("le.bt.reconnect.cancel",
                    "attempt", attempt,
                    "reason", "policy_disabled",
                    "policy", policy);
            return;
        }
        if (stateBefore == BluetoothProfile.STATE_CONNECTED) {
            reconnectGate.finish(mac, generation,
                    android.os.SystemClock.elapsedRealtime(), LE_RECONNECT_COOLDOWN_MS);
            MLog.event("le.bt.reconnect",
                    "attempt", attempt,
                    "stateBefore", stateBefore,
                    "action", "already_connected");
            replyState(mac, true);
            return;
        }
        if (stateBefore == BluetoothProfile.STATE_DISCONNECTING) {
            finishReconnectIfLast(mac, generation, attempt, lastAttempt, "disconnecting");
            return;
        }

        Boolean connectOk = invokeBoolean(proxy, "connect",
                new Class[]{BluetoothDevice.class}, new Object[]{device});
        int stateAfter = getProfileConnectionState(proxy, device);
        boolean transportSent = false;
        if (lastAttempt
                && stateAfter != BluetoothProfile.STATE_CONNECTED
                && stateAfter != BluetoothProfile.STATE_CONNECTING) {
            // Last-resort compatibility for stacks without a callable profile connect method.
            transportSent = sendTransportSwitch(device, true);
        }
        MLog.event("le.bt.reconnect",
                "attempt", attempt,
                "policy", policy,
                "stateBefore", stateBefore,
                "connectOk", connectOk,
                "stateAfter", stateAfter,
                "transportSent", transportSent,
                "last", lastAttempt);
        replyState(mac, true);
        if (stateAfter == BluetoothProfile.STATE_CONNECTED) {
            reconnectGate.finish(mac, generation,
                    android.os.SystemClock.elapsedRealtime(), LE_RECONNECT_COOLDOWN_MS);
        } else if (lastAttempt) {
            reconnectGate.finish(mac, generation,
                    android.os.SystemClock.elapsedRealtime(), LE_RECONNECT_COOLDOWN_MS);
        }
    }

    private void finishReconnectIfLast(
            String mac, long generation, int attempt, boolean lastAttempt, String reason) {
        MLog.event("le.bt.reconnect",
                "attempt", attempt,
                "action", reason,
                "last", lastAttempt);
        if (lastAttempt) {
            reconnectGate.finish(mac, generation,
                    android.os.SystemClock.elapsedRealtime(), LE_RECONNECT_COOLDOWN_MS);
        }
    }

    private void reconnectA2dpLater(
            BluetoothDevice device, long generation, long delayMs, int attempt) {
        Thread worker = new Thread(() -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
            synchronized (writeLock) {
                reconnectA2dp(device, generation, attempt);
            }
        }, "MelodyCodecLsp-a2dp-reconnect");
        worker.setDaemon(true);
        worker.start();
    }

    private void reconnectA2dp(BluetoothDevice device, long generation, int attempt) {
        Object proxy = acquireA2dpProxyBlocking();
        if (proxy == null || device == null) {
            MLog.w("le.bt.a2dp.reconnect skipped");
            return;
        }
        if (!canRestoreA2dp(device, generation)) return;
        int stateBefore = getProfileConnectionState(proxy, device);
        Integer policyBefore = getConnectionPolicy(proxy, device);
        if (stateBefore == BluetoothProfile.STATE_CONNECTED) {
            MLog.event("le.bt.a2dp.reconnect",
                    "attempt", attempt,
                    "policyBefore", policyBefore,
                    "stateBefore", stateBefore,
                    "action", "already_connected");
            return;
        }
        if (stateBefore == BluetoothProfile.STATE_CONNECTING) {
            MLog.event("le.bt.a2dp.reconnect",
                    "attempt", attempt,
                    "policyBefore", policyBefore,
                    "stateBefore", stateBefore,
                    "action", "already_connecting");
            return;
        }
        if (stateBefore == BluetoothProfile.STATE_DISCONNECTING) {
            MLog.event("le.bt.a2dp.reconnect",
                    "attempt", attempt,
                    "policyBefore", policyBefore,
                    "stateBefore", stateBefore,
                    "action", "disconnecting");
            return;
        }
        Boolean policyOk = null;
        if (policyBefore != null && policyBefore < CONNECTION_POLICY_ALLOWED) {
            if (!canRestoreA2dp(device, generation)) return;
            policyOk = setConnectionPolicy(proxy, device, CONNECTION_POLICY_ALLOWED);
            sleepQuietly(250L);
            int stateAfterPolicy = getProfileConnectionState(proxy, device);
            if (stateAfterPolicy == BluetoothProfile.STATE_CONNECTED
                    || stateAfterPolicy == BluetoothProfile.STATE_CONNECTING) {
                MLog.event("le.bt.a2dp.reconnect",
                        "attempt", attempt,
                        "policyBefore", policyBefore,
                        "policyOk", policyOk,
                        "stateBefore", stateBefore,
                        "stateAfterPolicy", stateAfterPolicy,
                        "action", "policy_restored");
                return;
            }
        }
        if (!canRestoreA2dp(device, generation)) return;
        Boolean connectOk = invokeBoolean(proxy, "connect",
                new Class[]{BluetoothDevice.class}, new Object[]{device});
        int stateAfter = getProfileConnectionState(proxy, device);
        MLog.event("le.bt.a2dp.reconnect",
                "attempt", attempt,
                "policyBefore", policyBefore,
                "policyOk", policyOk,
                "connectOk", connectOk,
                "stateBefore", stateBefore,
                "stateAfter", stateAfter,
                "action", "connect");
    }

    private boolean canRestoreA2dp(BluetoothDevice device, long generation) {
        String mac = device.getAddress();
        if (!a2dpRestoreGate.isCurrent(mac, generation)) return false;
        Boolean enabled = readLeAudioEnabledForGame(mac);
        boolean allowed = a2dpRestoreGate.mayRestore(mac, generation, enabled);
        if (!allowed) {
            MLog.event("le.bt.a2dp.reconnect.skipped",
                    "reason", enabled == null ? "le_state_unknown" : "le_enabled",
                    "generation", generation);
        }
        return allowed;
    }

    private static void sleepQuietly(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void scheduleReply(String mac, boolean ok, long delayMs) {
        Thread worker = new Thread(() -> {
            sleepQuietly(delayMs);
            replyState(mac, ok);
        }, "MelodyCodecLsp-le-reply");
        worker.setDaemon(true);
        worker.start();
    }

    private void replyState(String mac, boolean ok) {
        boolean supported;
        boolean enabled;
        boolean connected;
        Boolean coexist;
        synchronized (writeLock) {
            supported = acquireProxyBlocking() != null;
            enabled = supported && Boolean.TRUE.equals(readLeAudioEnabledForGame(mac));
            connected = supported && isLeAudioConnectedNow(mac);
            coexist = readLeaCoexistSupportedForGame(mac);
        }
        Intent reply = new Intent(LeAudioIpc.ACTION_LE_AUDIO_STATE);
        reply.setPackage(LeAudioIpc.MELODY_PKG);
        reply.putExtra(LeAudioIpc.EXTRA_TOKEN, LeAudioIpc.TOKEN);
        reply.putExtra(LeAudioIpc.EXTRA_MAC, mac);
        reply.putExtra(LeAudioIpc.EXTRA_SUPPORTED, supported);
        reply.putExtra(LeAudioIpc.EXTRA_ENABLED, enabled);
        reply.putExtra(LeAudioIpc.EXTRA_CONNECTED, connected);
        reply.putExtra(LeAudioIpc.EXTRA_LEA_COEXIST, Boolean.TRUE.equals(coexist));
        reply.putExtra(LeAudioIpc.EXTRA_OK, ok);
        try {
            if (!TrustedBroadcasts.send(context, reply)) {
                MLog.w("le.bt.reply identity send failed");
            }
            MLog.event("le.bt.reply",
                    "supported", supported,
                    "enabled", enabled,
                    "connected", connected,
                    "coexist", Boolean.TRUE.equals(coexist),
                    "ok", ok);
        } catch (Throwable t) {
            MLog.w("le.bt.reply send failed", t);
        }
    }

    private static boolean isLeAudioEnabled(Object proxy, String mac) {
        BluetoothDevice device = resolveDevice(mac);
        if (proxy == null || device == null) return false;
        Boolean enabled = readLeAudioEnabled(proxy, device);
        if (enabled != null) return enabled;
        return getProfileConnectionState(proxy, device) == BluetoothProfile.STATE_CONNECTED;
    }

    private static Boolean readLeAudioEnabled(Object proxy, BluetoothDevice device) {
        Integer policy = getConnectionPolicy(proxy, device);
        if (policy != null && policy >= 0) return enabledFromReadback(policy, null);
        Boolean enabled = invokeBoolean(proxy, "isEnabled",
                new Class[]{BluetoothDevice.class}, new Object[]{device});
        return enabledFromReadback(policy, enabled);
    }

    static Boolean enabledFromReadback(Integer policy, Boolean enabled) {
        if (policy != null && policy >= 0) return policy > 0;
        return enabled;
    }

    static Boolean anyLeAudioEnabled(List<Boolean> states) {
        if (states.isEmpty()) return null;
        boolean unknown = false;
        for (Boolean state : states) {
            if (Boolean.TRUE.equals(state)) return true;
            if (state == null) unknown = true;
        }
        return unknown ? null : Boolean.FALSE;
    }

    static boolean hasLeAudioCapability(int deviceType, UUID[] uuids) {
        if (deviceType != BluetoothDevice.DEVICE_TYPE_LE
                && deviceType != BluetoothDevice.DEVICE_TYPE_DUAL) return false;
        if (uuids == null) return false;
        for (UUID uuid : uuids) {
            if (UUID_LE_AUDIO.equals(uuid) || UUID_HAS.equals(uuid)) return true;
        }
        return false;
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

    /** Each new request invalidates A2DP work from all previous requests for the BR device. */
    static final class A2dpRestoreGate {
        private final Map<String, Long> generations = new HashMap<>();

        synchronized long advance(String mac) {
            long generation = generations.getOrDefault(mac, 0L) + 1L;
            generations.put(mac, generation);
            return generation;
        }

        synchronized boolean isCurrent(String mac, long generation) {
            Long current = generations.get(mac);
            return current != null && current == generation;
        }

        synchronized boolean mayRestore(String mac, long generation, Boolean leAudioEnabled) {
            return isCurrent(mac, generation) && Boolean.FALSE.equals(leAudioEnabled);
        }
    }

    /** Per-device generation/cooldown gate preventing duplicate Melody processes from storming. */
    static final class ReconnectGate {
        private final Map<String, Long> generations = new HashMap<>();
        private final Map<String, Long> running = new HashMap<>();
        private final Map<String, Long> cooldownUntil = new HashMap<>();

        synchronized long tryStart(String mac, long nowElapsed, boolean force) {
            if (mac == null || mac.isEmpty() || running.containsKey(mac)) return -1L;
            Long cooldown = cooldownUntil.get(mac);
            if (!force && cooldown != null && nowElapsed >= 0L && nowElapsed < cooldown) {
                return -1L;
            }
            long generation = generations.getOrDefault(mac, 0L) + 1L;
            generations.put(mac, generation);
            running.put(mac, generation);
            return generation;
        }

        synchronized boolean isCurrent(String mac, long generation) {
            Long active = running.get(mac);
            return active != null && active == generation;
        }

        synchronized void finish(
                String mac, long generation, long nowElapsed, long cooldownMs) {
            if (!isCurrent(mac, generation)) return;
            running.remove(mac);
            cooldownUntil.put(mac, Math.max(0L, nowElapsed) + Math.max(0L, cooldownMs));
        }

        synchronized void cancel(String mac) {
            if (mac == null) return;
            running.remove(mac);
            cooldownUntil.remove(mac);
            generations.put(mac, generations.getOrDefault(mac, 0L) + 1L);
        }
    }
}
