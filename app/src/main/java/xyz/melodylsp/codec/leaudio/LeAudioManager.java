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

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import xyz.melodylsp.codec.util.MLog;

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
 *       {@code PUT_LEA_MODE_INFO} broadcast + the wirelesssettings bridge reply
 *       ({@link LeAudioIpc#ACTION_LE_AUDIO_STATE}) to hot-refresh;</li>
 *   <li>fire {@link LeAudioIpc#ACTION_SET_LE_AUDIO} so the wirelesssettings bridge shows the
 *       official COUI warning dialog and performs {@code LeAudioProfile.setEnabled}.</li>
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

    private final Context appContext;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Map<String, Boolean> supportedByMac = new ConcurrentHashMap<>();
    private final Map<String, Boolean> enabledByMac = new ConcurrentHashMap<>();

    private BroadcastReceiver receiver;

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

    /**
     * Begin tracking a MAC: read the local best-effort state immediately and ask the
     * wirelesssettings bridge for the authoritative state. Safe to call repeatedly.
     */
    public void ensureTracking(String mac) {
        if (mac == null) return;
        boolean sup = probeSupport(mac);
        boolean en = readEnabledFromPrefs(mac, isEnabled(mac));
        supportedByMac.put(mac, sup);
        enabledByMac.put(mac, en);
        notifyChanged(mac);
        queryBridge(mac);
    }

    /**
     * Request a toggle. Fires the broadcast to the wirelesssettings bridge, which shows the
     * official COUI warning dialog and only flips LE Audio on user confirmation. We do not
     * mutate local state here — the authoritative state arrives via the reply broadcast.
     */
    public void requestToggle(String mac, boolean enable) {
        if (mac == null) return;
        Intent intent = new Intent(LeAudioIpc.ACTION_SET_LE_AUDIO);
        intent.setPackage(LeAudioIpc.WIRELESS_SETTINGS_PKG);
        intent.putExtra(LeAudioIpc.EXTRA_TOKEN, LeAudioIpc.TOKEN);
        intent.putExtra(LeAudioIpc.EXTRA_MAC, mac);
        intent.putExtra(LeAudioIpc.EXTRA_ENABLE, enable);
        try {
            appContext.sendBroadcast(intent);
            MLog.event("le.melody.set.sent", "enable", enable);
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
    }

    private void queryBridge(String mac) {
        Intent intent = new Intent(LeAudioIpc.ACTION_QUERY_LE_AUDIO);
        intent.setPackage(LeAudioIpc.WIRELESS_SETTINGS_PKG);
        intent.putExtra(LeAudioIpc.EXTRA_TOKEN, LeAudioIpc.TOKEN);
        intent.putExtra(LeAudioIpc.EXTRA_MAC, mac);
        try {
            appContext.sendBroadcast(intent);
        } catch (Throwable t) {
            MLog.w("LE Audio query broadcast failed", t);
        }
    }

    private void registerReceiver() {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent != null ? intent.getAction() : null;
                if (LeAudioIpc.ACTION_LE_AUDIO_STATE.equals(action)) {
                    handleBridgeState(intent);
                } else if (MELODY_PUT_LEA_MODE_INFO.equals(action)) {
                    // melody updated its own LE state; re-read every tracked MAC from prefs.
                    mainHandler.postDelayed(() -> {
                        for (String mac : enabledByMac.keySet()) {
                            boolean en = readEnabledFromPrefs(mac, isEnabled(mac));
                            if (en != isEnabled(mac)) {
                                enabledByMac.put(mac, en);
                                notifyChanged(mac);
                            }
                        }
                    }, 300L);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(LeAudioIpc.ACTION_LE_AUDIO_STATE);
        filter.addAction(MELODY_PUT_LEA_MODE_INFO);
        try {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            appContext.registerReceiver(receiver, filter);
        }
    }

    private void handleBridgeState(Intent intent) {
        if (!LeAudioIpc.TOKEN.equals(intent.getStringExtra(LeAudioIpc.EXTRA_TOKEN))) return;
        String mac = intent.getStringExtra(LeAudioIpc.EXTRA_MAC);
        if (mac == null) return;
        boolean sup = intent.getBooleanExtra(LeAudioIpc.EXTRA_SUPPORTED, isSupported(mac));
        boolean en = intent.getBooleanExtra(LeAudioIpc.EXTRA_ENABLED, isEnabled(mac));
        supportedByMac.put(mac, sup);
        enabledByMac.put(mac, en);
        MLog.event("le.melody.state.recv", "supported", sup, "enabled", en);
        notifyChanged(mac);
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
