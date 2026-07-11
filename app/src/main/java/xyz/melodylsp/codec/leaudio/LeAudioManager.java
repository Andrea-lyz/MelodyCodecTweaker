package xyz.melodylsp.codec.leaudio;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import xyz.melodylsp.codec.util.MLog;
import xyz.melodylsp.codec.util.TrustedBroadcasts;

/**
 * Central, process-wide LE Audio state owner on the melody side (TODO B1 + B2 redesign).
 *
 * <p>Unlike a per-switch helper, this lives for the whole host process and tracks LE Audio
 * support + enabled state keyed by MAC. That matters because the codec block must react to LE
 * Audio on <strong>both</strong> surfaces — DetailMain (which hosts the toggle) and OneSpace
 * (which does not) — so the state cannot live inside a single switch widget.</p>
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>probe support (melody's {@code le-device-info} prefs, then ASCS / PACS / BASS GATT
 *       UUIDs);</li>
 *   <li>read enabled state from {@code le-device-info} and listen for melody's
 *       {@code PUT_LEA_MODE_INFO} broadcast + the privileged bridge reply
 *       ({@link LeAudioIpc#ACTION_LE_AUDIO_STATE}) to hot-refresh;</li>
 *   <li>fire {@link LeAudioIpc#ACTION_SET_LE_AUDIO} after the Melody-side confirmation dialog,
 *       so the privileged bridge can perform the LE Audio profile write.</li>
 * </ul>
 * On any change it invokes {@link Listener#onLeAudioStateChanged(String)} so the controller
 * can re-render every subscription bound to that MAC.</p>
 */
public final class LeAudioManager {

    /** Callback into the controller when a MAC's LE Audio state changes. */
    public interface Listener {
        void onLeAudioStateChanged(String mac);
    }

    private static final ParcelUuid UUID_ASCS =
            ParcelUuid.fromString("0000184e-0000-1000-8000-00805f9b34fb");
    private static final ParcelUuid UUID_PACS =
            ParcelUuid.fromString("0000184f-0000-1000-8000-00805f9b34fb");
    private static final ParcelUuid UUID_BASS =
            ParcelUuid.fromString("00001850-0000-1000-8000-00805f9b34fb");

    private static final String MELODY_PUT_LEA_MODE_INFO =
            "oplus.bluetooth.device.action.PUT_LEA_MODE_INFO";
    private static final String ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED =
            "android.bluetooth.action.LE_AUDIO_CONNECTION_STATE_CHANGED";
    private static final String ACTION_A2DP_CONNECTION_STATE_CHANGED =
            "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED";
    private static final String EXTRA_CONNECTION_STATE = "android.bluetooth.profile.extra.STATE";
    private static final long QUERY_DEBOUNCE_MS = 500L;

    private final Context appContext;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Map<String, Boolean> supportedByMac = new ConcurrentHashMap<>();
    private final Map<String, Boolean> enabledByMac = new ConcurrentHashMap<>();
    /** Authoritative LE Audio profile state; never inferred from connection policy or A2DP. */
    private final Map<String, Boolean> connectedByMac = new ConcurrentHashMap<>();
    private final Map<String, Long> lastQueryElapsedByMac = new ConcurrentHashMap<>();

    private BroadcastReceiver receiver;
    private BroadcastReceiver bridgeReceiver;

    public LeAudioManager(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
        registerReceiver();
    }

    /** True when the device is known / probed to support LE Audio. */
    public boolean isSupported(String mac) {
        Boolean v = mac != null ? supportedByMac.get(mac) : null;
        return v != null && v;
    }

    /** True when LE Audio is currently enabled for the device. */
    public boolean isEnabled(String mac) {
        Boolean v = mac != null ? enabledByMac.get(mac) : null;
        return v != null && v;
    }

    /** True only when the LE Audio profile itself is connected. */
    public boolean isConnected(String mac) {
        Boolean v = mac != null ? connectedByMac.get(mac) : null;
        return v != null && v;
    }

    /**
     * Begin tracking a MAC: read the local best-effort state immediately and ask the
     * bluetooth bridge for the authoritative state. Safe to call repeatedly.
     */
    public void ensureTracking(String mac) {
        if (mac == null) return;
        boolean en = readEnabledFromPrefs(mac, isEnabled(mac));
        boolean sup = probeSupport(mac) || en || isConnected(mac);
        supportedByMac.put(mac, sup);
        enabledByMac.put(mac, en);
        connectedByMac.putIfAbsent(mac, false);
        notifyChanged(mac);
        queryBridge(mac, false);
    }

    /**
     * Request a toggle. The user confirmation already happened in melody's Activity; send the
     * privileged work to the bluetooth process, which is not app-freeze throttled like
     * wirelesssettings. We do not mutate local state here — the authoritative state arrives via
     * the reply broadcast.
     */
    public void requestToggle(String mac, boolean enable) {
        if (mac == null) return;
        Intent intent = new Intent(LeAudioIpc.ACTION_SET_LE_AUDIO);
        intent.setPackage(LeAudioIpc.BLUETOOTH_PKG);
        intent.putExtra(LeAudioIpc.EXTRA_TOKEN, LeAudioIpc.TOKEN);
        intent.putExtra(LeAudioIpc.EXTRA_MAC, mac);
        intent.putExtra(LeAudioIpc.EXTRA_ENABLE, enable);
        try {
            if (TrustedBroadcasts.send(appContext, intent)) {
                MLog.event("le.melody.set.sent", "enable", enable);
            }
        } catch (Throwable t) {
            MLog.e("LE Audio set broadcast failed", t);
        }
    }

    public void release() {
        if (receiver != null) {
            try {
                appContext.unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
            }
            receiver = null;
        }
        if (bridgeReceiver != null) {
            try {
                appContext.unregisterReceiver(bridgeReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            bridgeReceiver = null;
        }
    }

    private void queryBridge(String mac, boolean repairConnection) {
        if (mac == null) return;
        long now = SystemClock.elapsedRealtime();
        Long previous = lastQueryElapsedByMac.get(mac);
        if (!repairConnection
                && previous != null
                && now >= previous
                && now - previous < QUERY_DEBOUNCE_MS) {
            return;
        }
        lastQueryElapsedByMac.put(mac, now);
        Intent intent = new Intent(LeAudioIpc.ACTION_QUERY_LE_AUDIO);
        intent.setPackage(LeAudioIpc.BLUETOOTH_PKG);
        intent.putExtra(LeAudioIpc.EXTRA_TOKEN, LeAudioIpc.TOKEN);
        intent.putExtra(LeAudioIpc.EXTRA_MAC, mac);
        intent.putExtra(LeAudioIpc.EXTRA_REPAIR_CONNECTION, repairConnection);
        try {
            if (!TrustedBroadcasts.send(appContext, intent)) {
                MLog.w("LE Audio query broadcast was not delivered");
            }
        } catch (Throwable t) {
            MLog.w("LE Audio query broadcast failed", t);
        }
    }

    private void registerReceiver() {
        if (receiver != null) return;
        bridgeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent != null ? intent.getAction() : null;
                if (LeAudioIpc.ACTION_LE_AUDIO_STATE.equals(action)) {
                    handleBridgeState(intent, TrustedBroadcasts.captureSender(this));
                } else if (ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED.equals(action)
                        || ACTION_A2DP_CONNECTION_STATE_CHANGED.equals(action)
                        || BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                    handleBluetoothProfileState(intent);
                }
            }
        };
        IntentFilter bridgeFilter = new IntentFilter(LeAudioIpc.ACTION_LE_AUDIO_STATE);
        bridgeFilter.addAction(ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED);
        bridgeFilter.addAction(ACTION_A2DP_CONNECTION_STATE_CHANGED);
        bridgeFilter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        if (!TrustedBroadcasts.registerExportedReceiver(
                appContext,
                bridgeReceiver,
                bridgeFilter,
                TrustedBroadcasts.PERMISSION_BLUETOOTH_PRIVILEGED,
                mainHandler)) {
            bridgeReceiver = null;
            MLog.w("LE Audio state receiver registration failed");
        }

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent != null ? intent.getAction() : null;
                if (MELODY_PUT_LEA_MODE_INFO.equals(action)) {
                    refreshTrackedFromPrefsAndBridge(300L, false);
                }
            }
        };
        IntentFilter filter = new IntentFilter(MELODY_PUT_LEA_MODE_INFO);
        // This OEM action is emitted by com.android.bluetooth, not by Melody itself. It does
        // not opt into Android 14 sender-identity sharing, so the OPlus signature permission is
        // the authentication boundary on every supported release.
        if (!TrustedBroadcasts.registerExportedReceiver(
                appContext,
                receiver,
                filter,
                TrustedBroadcasts.PERMISSION_OPLUS_COMPONENT_SAFE,
                mainHandler)) {
            receiver = null;
            MLog.w("Melody LE Audio state receiver registration failed");
        }
    }

    private void handleBridgeState(
            Intent intent, TrustedBroadcasts.SenderIdentity sender) {
        if (!LeAudioIpc.TOKEN.equals(intent.getStringExtra(LeAudioIpc.EXTRA_TOKEN))) return;
        if (TrustedBroadcasts.supportsSenderIdentity()
                && !TrustedBroadcasts.isTrustedSender(
                appContext,
                sender,
                LeAudioIpc.BLUETOOTH_PKG,
                LeAudioIpc.WIRELESS_SETTINGS_PKG)) {
            MLog.w("LE Audio state rejected: untrusted sender");
            return;
        }
        String mac = intent.getStringExtra(LeAudioIpc.EXTRA_MAC);
        if (mac == null) return;
        boolean bridgeSup = intent.getBooleanExtra(LeAudioIpc.EXTRA_SUPPORTED, false);
        boolean wasEnabled = isEnabled(mac);
        boolean en = intent.getBooleanExtra(LeAudioIpc.EXTRA_ENABLED, wasEnabled);
        boolean connected = intent.getBooleanExtra(
                LeAudioIpc.EXTRA_CONNECTED, isConnected(mac));
        boolean localSup = probeSupport(mac);
        // The bluetooth process can only tell us whether the system has a LE Audio profile
        // proxy. That is not the same thing as "this headset exposes LE Audio"; otherwise every
        // phone with LE Audio support would show the switch for unsupported earbuds. Treat the
        // bridge reply as authoritative for enabled/connected state, but require a per-device
        // signal for support.
        boolean sup = localSup || en || connected || wasEnabled;
        if (bridgeSup && !sup) {
            MLog.event("le.melody.support.bridge_ignored");
        }
        supportedByMac.put(mac, sup);
        enabledByMac.put(mac, en);
        connectedByMac.put(mac, connected);
        MLog.event("le.melody.state.recv",
                "supported", sup,
                "enabled", en,
                "connected", connected);
        notifyChanged(mac);
    }

    private void refreshTrackedFromPrefsAndBridge(long delayMs, boolean repairConnection) {
        mainHandler.postDelayed(() -> {
            for (String mac : enabledByMac.keySet()) {
                boolean oldSup = isSupported(mac);
                boolean oldEn = isEnabled(mac);
                boolean en = readEnabledFromPrefs(mac, isEnabled(mac));
                boolean sup = probeSupport(mac) || en || isConnected(mac) || oldEn;
                boolean changed = false;
                if (sup != oldSup) {
                    supportedByMac.put(mac, sup);
                    changed = true;
                }
                if (en != oldEn) {
                    enabledByMac.put(mac, en);
                    changed = true;
                }
                if (changed) {
                    notifyChanged(mac);
                }
                queryBridge(mac, repairConnection && en && !isConnected(mac));
            }
        }, delayMs);
    }

    @SuppressWarnings("deprecation")
    private void handleBluetoothProfileState(Intent intent) {
        BluetoothDevice device = null;
        try {
            device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        } catch (Throwable ignored) {
        }
        String mac = device != null ? device.getAddress() : null;
        if (mac == null || !enabledByMac.containsKey(mac)) {
            refreshTrackedFromPrefsAndBridge(800L, false);
            return;
        }
        int state = intent.getIntExtra(EXTRA_CONNECTION_STATE, -1);
        String action = intent.getAction();
        boolean changed = false;
        boolean repairConnection = false;
        if (ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED.equals(action)) {
            if (state == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                supportedByMac.put(mac, true);
                enabledByMac.put(mac, true);
                connectedByMac.put(mac, true);
                changed = true;
            } else if (state == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                connectedByMac.put(mac, false);
                changed = true;
            }
        } else if (ACTION_A2DP_CONNECTION_STATE_CHANGED.equals(action)) {
            // A2DP state says nothing about whether the LE Audio profile is connected. A fresh
            // classic connection while LE policy is enabled is, however, a useful repair signal.
            if (state == android.bluetooth.BluetoothProfile.STATE_CONNECTED
                    && isEnabled(mac)
                    && !isConnected(mac)) {
                repairConnection = true;
            }
        } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)
                && isEnabled(mac)
                && !isConnected(mac)) {
            repairConnection = true;
        }
        if (changed) {
            notifyChanged(mac);
        }
        final boolean repairHint = repairConnection;
        mainHandler.postDelayed(() -> queryBridge(
                mac,
                repairHint && isEnabled(mac) && !isConnected(mac)),
                repairHint ? 250L : 1200L);
    }

    private void notifyChanged(String mac) {
        if (listener == null) return;
        mainHandler.post(() -> {
            try {
                listener.onLeAudioStateChanged(mac);
            } catch (Throwable t) {
                MLog.w("LE Audio listener threw", t);
            }
        });
    }

    /** Primary signal: MAC present in melody's {@code le-device-info}; secondary: GATT UUIDs. */
    private boolean probeSupport(String mac) {
        if (macInLeDeviceInfo(mac)) return true;
        return advertisesLeAudioUuids(mac);
    }

    private boolean macInLeDeviceInfo(String mac) {
        try {
            SharedPreferences sp = openLeDeviceInfoPrefs();
            if (sp == null) return false;
            if (sp.contains(mac)) return true;
            for (String key : sp.getAll().keySet()) {
                if (key != null && key.equalsIgnoreCase(mac)) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean readEnabledFromPrefs(String mac, boolean fallback) {
        try {
            SharedPreferences sp = openLeDeviceInfoPrefs();
            if (sp == null) return fallback;
            String json = sp.getString(mac, null);
            if (json == null) {
                for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                    if (e.getKey() != null && e.getKey().equalsIgnoreCase(mac)
                            && e.getValue() instanceof String) {
                        json = (String) e.getValue();
                        break;
                    }
                }
            }
            if (json == null) return fallback;
            return jsonBool(json, "isLeOpen");
        } catch (Throwable t) {
            return fallback;
        }
    }

    /**
     * Obtain melody's {@code le-device-info} store reflectively through
     * {@code MelodyAlivePreferencesHelper}. That store is a ContentProvider-backed
     * SharedPreferences (not a plain file), so we must go through the helper. Only the method
     * name is R8-minified; we resolve the static {@code String -> SharedPreferences} method by
     * signature. Returns null on failure — the bridge reply then supplies authoritative state.
     */
    private SharedPreferences openLeDeviceInfoPrefs() {
        try {
            Class<?> helper = Class.forName(
                    "com.oplus.melody.common.helper.MelodyAlivePreferencesHelper",
                    false, appContext.getClassLoader());
            for (Method m : helper.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0] != String.class) continue;
                if (!SharedPreferences.class.isAssignableFrom(m.getReturnType())) continue;
                m.setAccessible(true);
                Object sp = m.invoke(null, "le-device-info");
                if (sp instanceof SharedPreferences) {
                    return (SharedPreferences) sp;
                }
            }
            MLog.w("LE Audio: no MelodyAlivePreferencesHelper String->SharedPreferences method");
        } catch (Throwable t) {
            MLog.w("openLeDeviceInfoPrefs reflection failed", t);
        }
        return null;
    }

    @android.annotation.SuppressLint("MissingPermission")
    private boolean advertisesLeAudioUuids(String mac) {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) return false;
            BluetoothDevice device = adapter.getRemoteDevice(mac);
            if (device == null) return false;
            ParcelUuid[] uuids = device.getUuids();
            if (uuids == null) return false;
            for (ParcelUuid uuid : uuids) {
                if (uuid == null) continue;
                if (uuid.equals(UUID_ASCS) || uuid.equals(UUID_PACS) || uuid.equals(UUID_BASS)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Minimal JSON boolean reader: finds {@code "field":true|false} without a JSON lib. */
    private static boolean jsonBool(String json, String field) {
        if (json == null) return false;
        String needle = "\"" + field + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) return false;
        int colon = json.indexOf(':', idx + needle.length());
        if (colon < 0) return false;
        String rest = json.substring(colon + 1).trim();
        return rest.startsWith("true") || rest.startsWith("1");
    }
}
