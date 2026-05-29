package xyz.melodylsp.codec.leaudio;

import android.app.AlertDialog;
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
import android.widget.Toast;

import java.lang.reflect.Proxy;
import java.lang.reflect.Method;

import xyz.melodylsp.codec.host.PrefRef;
import xyz.melodylsp.codec.host.Strings;
import xyz.melodylsp.codec.util.MLog;

/**
 * Melody-side LE Audio switch driver (TODO B1 + B2).
 *
 * <p>Owns a single {@code COUISwitchPreference} injected into the DetailMain codec block. It:
 * <ol>
 *   <li>probes device support (melody's own {@code le-device-info} prefs, then ASCS / PACS /
 *       BASS GATT UUIDs) and hides the row when unsupported;</li>
 *   <li>reads the current enabled state from {@code le-device-info} and listens for melody's
 *       {@code PUT_LEA_MODE_INFO} broadcast to hot-refresh;</li>
 *   <li>on tap, shows a confirmation dialog and — Phase 2 — fires the
 *       {@link LeAudioIpc#ACTION_SET_LE_AUDIO} broadcast to the wirelesssettings bridge for a
 *       one-tap switch, listening for {@link LeAudioIpc#ACTION_LE_AUDIO_STATE} to confirm.</li>
 * </ol>
 * Everything reflective; no compile-time androidx.preference / settingslib symbols.</p>
 */
public final class LeAudioUiController {

    // LE Audio GATT service UUIDs (16-bit, expanded to the Bluetooth base UUID).
    private static final ParcelUuid UUID_ASCS =
            ParcelUuid.fromString("0000184e-0000-1000-8000-00805f9b34fb");
    private static final ParcelUuid UUID_PACS =
            ParcelUuid.fromString("0000184f-0000-1000-8000-00805f9b34fb");
    private static final ParcelUuid UUID_BASS =
            ParcelUuid.fromString("00001850-0000-1000-8000-00805f9b34fb");

    private static final String MELODY_PUT_LEA_MODE_INFO =
            "oplus.bluetooth.device.action.PUT_LEA_MODE_INFO";

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final String mac;
    private final Object leSwitch;
    private final Object fragment;
    private final Context uiContext;

    private BroadcastReceiver receiver;
    private boolean supported;
    private boolean enabled;
    private long lastRequestAt;

    public LeAudioUiController(
            Context appContext, String mac, Object leSwitch, Object fragment, Context uiContext) {
        this.appContext = appContext.getApplicationContext();
        this.mac = mac;
        this.leSwitch = leSwitch;
        this.fragment = fragment;
        this.uiContext = uiContext;
    }

    /** Wire the switch, probe support, read initial state, and start listening. */
    public void start() {
        if (leSwitch == null) return;
        wireChangeListener();
        registerReceivers();
        refreshSupportAndState();
        // Ask the privileged bridge for the authoritative support/state (Phase 2). Harmless if
        // wirelesssettings does not answer — we already rendered the local best-effort state.
        queryBridge();
    }

    /** Tear down listeners. Called when the host fragment / activity is destroyed. */
    public void stop() {
        if (receiver != null) {
            try {
                appContext.unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
            }
            receiver = null;
        }
    }

    private void wireChangeListener() {
        ClassLoader cl = appContext.getClassLoader();
        Class<?> prefBase = PrefRef.load(cl, "androidx.preference.Preference");
        Class<?> changeListenerCls = resolveChangeListenerInterface(cl, leSwitch, prefBase);
        if (changeListenerCls == null) {
            MLog.w("LE Audio: change listener interface not resolvable");
            return;
        }
        Object listener = Proxy.newProxyInstance(cl, new Class[]{changeListenerCls},
                (proxy, method, args) -> {
                    if (args == null || args.length < 2 || !(args[1] instanceof Boolean)) {
                        return false;
                    }
                    boolean requested = (Boolean) args[1];
                    onToggleRequested(requested);
                    // Return false: do not let the switch flip yet. We flip it ourselves once
                    // the bridge confirms (or revert on failure / cancel).
                    return false;
                });
        invokeSetChangeListener(leSwitch, listener, changeListenerCls);
    }

    private void onToggleRequested(boolean enable) {
        Context dialogCtx = liveDialogContext();
        if (dialogCtx == null) {
            // No live UI to confirm against; fall back to firing directly.
            sendSetRequest(enable);
            return;
        }
        String message = enable ? Strings.LE_AUDIO_DIALOG_MSG_ON : Strings.LE_AUDIO_DIALOG_MSG_OFF;
        try {
            new AlertDialog.Builder(dialogCtx)
                    .setTitle(Strings.LE_AUDIO_DIALOG_TITLE)
                    .setMessage(message)
                    .setNegativeButton(Strings.LE_AUDIO_CANCEL, (d, w) -> d.dismiss())
                    .setPositiveButton(Strings.LE_AUDIO_CONFIRM, (d, w) -> {
                        d.dismiss();
                        sendSetRequest(enable);
                    })
                    .setCancelable(true)
                    .show();
        } catch (Throwable t) {
            MLog.w("LE Audio dialog failed; firing request directly", t);
            sendSetRequest(enable);
        }
    }

    /** Phase 2: ask wirelesssettings to flip LE Audio for this device. */
    private void sendSetRequest(boolean enable) {
        lastRequestAt = System.currentTimeMillis();
        Intent intent = new Intent(LeAudioIpc.ACTION_SET_LE_AUDIO);
        intent.setPackage(LeAudioIpc.WIRELESS_SETTINGS_PKG);
        intent.putExtra(LeAudioIpc.EXTRA_TOKEN, LeAudioIpc.TOKEN);
        intent.putExtra(LeAudioIpc.EXTRA_MAC, mac);
        intent.putExtra(LeAudioIpc.EXTRA_ENABLE, enable);
        try {
            appContext.sendBroadcast(intent);
            toast(Strings.LE_AUDIO_TOAST_SENT);
            MLog.event("le.melody.set.sent", "enable", enable);
        } catch (Throwable t) {
            MLog.e("LE Audio set broadcast failed", t);
            toast(Strings.LE_AUDIO_TOAST_FAILED);
        }
        // If the bridge does not answer within a few seconds, re-sync from local prefs so the
        // switch reflects whatever actually happened (or stays put on failure).
        mainHandler.postDelayed(this::refreshSupportAndState, 4_000L);
    }

    private void queryBridge() {
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

    private void registerReceivers() {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent != null ? intent.getAction() : null;
                if (LeAudioIpc.ACTION_LE_AUDIO_STATE.equals(action)) {
                    handleBridgeState(intent);
                } else if (MELODY_PUT_LEA_MODE_INFO.equals(action)) {
                    // melody updated its own LE state; re-read from prefs.
                    mainHandler.postDelayed(LeAudioUiController.this::refreshSupportAndState, 300L);
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
        String replyMac = intent.getStringExtra(LeAudioIpc.EXTRA_MAC);
        if (replyMac != null && mac != null && !replyMac.equalsIgnoreCase(mac)) return;
        boolean sup = intent.getBooleanExtra(LeAudioIpc.EXTRA_SUPPORTED, supported);
        boolean en = intent.getBooleanExtra(LeAudioIpc.EXTRA_ENABLED, enabled);
        boolean ok = intent.getBooleanExtra(LeAudioIpc.EXTRA_OK, true);
        mainHandler.post(() -> {
            supported = sup;
            enabled = en;
            applyToSwitch();
            if (!ok && lastRequestAt > 0) {
                toast(Strings.LE_AUDIO_TOAST_FAILED);
            }
        });
        MLog.event("le.melody.state.recv", "supported", sup, "enabled", en, "ok", ok);
    }

    /** Local best-effort support + state probe (Phase 1). */
    private void refreshSupportAndState() {
        boolean sup = probeSupport();
        boolean en = readEnabledFromPrefs();
        mainHandler.post(() -> {
            supported = sup;
            enabled = en;
            applyToSwitch();
        });
    }

    private void applyToSwitch() {
        if (leSwitch == null) return;
        PrefRef.setVisible(leSwitch, supported);
        if (!supported) return;
        PrefRef.setChecked(leSwitch, enabled);
        PrefRef.setSummary(leSwitch,
                enabled ? Strings.LE_AUDIO_SUMMARY_ON : Strings.LE_AUDIO_SUMMARY_OFF);
    }

    /**
     * Support probe (TODO B1): primary signal is whether melody's own {@code le-device-info}
     * prefs contains this MAC; secondary is GATT UUID advertisement (ASCS / PACS / BASS).
     */
    private boolean probeSupport() {
        if (macInLeDeviceInfo()) return true;
        return advertisesLeAudioUuids();
    }

    private boolean macInLeDeviceInfo() {
        try {
            SharedPreferences sp = openLeDeviceInfoPrefs();
            if (sp == null) return false;
            if (sp.contains(mac)) return true;
            // Keys may be stored case-normalised; scan once.
            for (String key : sp.getAll().keySet()) {
                if (key != null && key.equalsIgnoreCase(mac)) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Read {@code LeDevice.isLeOpen()} for this MAC from melody's prefs. */
    private boolean readEnabledFromPrefs() {
        try {
            SharedPreferences sp = openLeDeviceInfoPrefs();
            if (sp == null) return enabled;
            String json = sp.getString(mac, null);
            if (json == null) {
                for (java.util.Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                    if (e.getKey() != null && e.getKey().equalsIgnoreCase(mac)
                            && e.getValue() instanceof String) {
                        json = (String) e.getValue();
                        break;
                    }
                }
            }
            if (json == null) return enabled;
            // LeDevice serialises isLeOpen as a JSON boolean; a lightweight scan avoids pulling
            // in melody's Gson-backed model class by reflection.
            return jsonBool(json, "isLeOpen");
        } catch (Throwable t) {
            return enabled;
        }
    }

    private SharedPreferences openLeDeviceInfoPrefs() {
        // melody stores LE device info in a custom multi-process prefs file named
        // "le-device-info", served by MelodyAlivePreferencesHelper (a ContentProvider-backed
        // SharedPreferences, NOT a plain file). Since this controller runs inside the melody
        // process we obtain the real store reflectively: find a static method on
        // MelodyAlivePreferencesHelper that takes a String and returns SharedPreferences. The
        // class FQN is preserved (readable package); only the method name is R8-minified, so we
        // resolve by signature. Returns null on any failure — the bridge query (Phase 2) then
        // supplies the authoritative state.
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

    private boolean advertisesLeAudioUuids() {
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

    private Context liveDialogContext() {
        if (uiContext != null) return uiContext;
        try {
            Method m = fragment.getClass().getMethod("getActivity");
            Object activity = m.invoke(fragment);
            return activity instanceof Context ? (Context) activity : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private void toast(String message) {
        mainHandler.post(() -> {
            try {
                Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        });
    }

    private static Class<?> resolveChangeListenerInterface(
            ClassLoader cl, Object prefSample, Class<?> prefBase) {
        if (prefBase == null) return null;
        Class<?> cls = prefSample != null ? prefSample.getClass() : prefBase;
        while (cls != null && cls != Object.class) {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getParameterCount() != 1) continue;
                Class<?> p = m.getParameterTypes()[0];
                if (!p.isInterface()) continue;
                Method[] ifaceMethods = p.getDeclaredMethods();
                if (ifaceMethods.length != 1) continue;
                Method only = ifaceMethods[0];
                if (only.getReturnType() != boolean.class) continue;
                if (only.getParameterCount() != 2) continue;
                if (!prefBase.isAssignableFrom(only.getParameterTypes()[0])) continue;
                return p;
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    private static void invokeSetChangeListener(Object pref, Object listener, Class<?> ifaceCls) {
        if (pref == null || listener == null) return;
        Class<?> cls = pref.getClass();
        while (cls != null && cls != Object.class) {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getParameterCount() != 1) continue;
                if (m.isSynthetic() || m.isBridge()) continue;
                if (m.getParameterTypes()[0] == ifaceCls) {
                    try {
                        m.setAccessible(true);
                        m.invoke(pref, listener);
                        return;
                    } catch (Throwable t) {
                        MLog.w("LE Audio setOnPreferenceChangeListener failed", t);
                        return;
                    }
                }
            }
            cls = cls.getSuperclass();
        }
        MLog.w("LE Audio: no change-listener setter found");
    }
}
