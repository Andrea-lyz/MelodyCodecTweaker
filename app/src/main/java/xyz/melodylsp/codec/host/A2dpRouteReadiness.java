package xyz.melodylsp.codec.host;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import xyz.melodylsp.codec.util.MLog;

/**
 * Tracks whether the current A2DP route has become usable at least once in this connection.
 *
 * <p>Some OPlus builds report the earphone as A2DP-connected while route-dependent developer
 * option rows are still disabled until the earbud is worn. {@code getActiveDevices(A2DP)}
 * is the closest public-ish signal for that state. Once the device has become active, we keep
 * the session latched as ready so putting the earbud back into the case does not block writes
 * that the Bluetooth stack still accepts.</p>
 */
final class A2dpRouteReadiness {

    static final String ACTION_ACTIVE_DEVICE_CHANGED =
            "android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED";

    private static final Pattern MAC_PATTERN =
            Pattern.compile("(?i)([0-9A-F]{2}:){5}[0-9A-F]{2}");

    private final Context context;
    private final Object lock = new Object();
    private final Set<String> readyMacs = new HashSet<>();
    private final Set<String> waitingMacs = new HashSet<>();
    private boolean loggedQueryFailure;
    private boolean loggedPermissionDenied;

    A2dpRouteReadiness(Context context) {
        this.context = context != null ? context.getApplicationContext() : null;
    }

    void markConnected(String mac) {
        String key = normalizeMac(mac);
        if (key == null) return;
        synchronized (lock) {
            if (readyMacs.contains(key)) {
                MLog.event("a2dp.ready.keep", "mac", redactMac(key), "reason", "connected");
                return;
            }
            readyMacs.remove(key);
            waitingMacs.add(key);
        }
        MLog.event("a2dp.ready.reset", "mac", redactMac(key));
    }

    void markDisconnected(String mac) {
        String key = normalizeMac(mac);
        if (key == null) return;
        synchronized (lock) {
            readyMacs.remove(key);
            waitingMacs.remove(key);
        }
        MLog.event("a2dp.ready.clear", "mac", redactMac(key));
    }

    boolean markReady(String mac, String reason) {
        String key = normalizeMac(mac);
        if (key == null) return false;
        boolean changed;
        synchronized (lock) {
            changed = readyMacs.add(key);
            waitingMacs.remove(key);
        }
        if (changed) {
            MLog.event("a2dp.ready.latched",
                    "mac", redactMac(key),
                    "reason", reason);
        }
        return true;
    }

    boolean isReadyOrUnknown(String mac) {
        String key = normalizeMac(mac);
        if (key == null) return true;
        if (isLatchedReady(key)) return true;
        if (isWaitingForActive(key)) return false;
        Boolean activeMatch = queryActiveA2dpMatch(key);
        if (Boolean.TRUE.equals(activeMatch)) {
            markReady(key, "active_query");
            return true;
        }
        return true;
    }

    boolean updateFromActiveDeviceIntent(Intent intent, String expectedMac) {
        String expected = normalizeMac(expectedMac);
        if (expected == null) return true;
        String active = macFromIntent(intent);
        if (active == null) return isLatchedReady(expected);
        if (expected.equals(active)) {
            markReady(expected, "active_broadcast");
            return true;
        }
        return isLatchedReady(expected);
    }

    private boolean isLatchedReady(String key) {
        synchronized (lock) {
            return readyMacs.contains(key);
        }
    }

    private boolean isWaitingForActive(String key) {
        synchronized (lock) {
            return waitingMacs.contains(key);
        }
    }

    private Boolean queryActiveA2dpMatch(String expected) {
        BluetoothAdapter adapter = resolveAdapter();
        if (adapter == null) return null;
        try {
            Method getActiveDevices = adapter.getClass().getMethod("getActiveDevices", int.class);
            Object devices = getActiveDevices.invoke(adapter, BluetoothProfile.A2DP);
            return deviceCollectionContains(devices, expected);
        } catch (Throwable t) {
            if (containsSecurityException(t)) {
                logPermissionDenied();
                return null;
            }
            logQueryFailure(t);
            return null;
        }
    }

    private BluetoothAdapter resolveAdapter() {
        try {
            if (context != null) {
                Object service = context.getSystemService(Context.BLUETOOTH_SERVICE);
                if (service instanceof BluetoothManager) {
                    BluetoothAdapter adapter = ((BluetoothManager) service).getAdapter();
                    if (adapter != null) return adapter;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            return BluetoothAdapter.getDefaultAdapter();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Boolean deviceCollectionContains(Object devices, String expected) {
        if (devices == null) return Boolean.FALSE;
        if (devices instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) devices) {
                String mac = macFromObject(item);
                if (expected.equals(mac)) return Boolean.TRUE;
            }
            return Boolean.FALSE;
        }
        Class<?> cls = devices.getClass();
        if (cls.isArray()) {
            int len = Array.getLength(devices);
            for (int i = 0; i < len; i++) {
                String mac = macFromObject(Array.get(devices, i));
                if (expected.equals(mac)) return Boolean.TRUE;
            }
            return Boolean.FALSE;
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static String macFromIntent(Intent intent) {
        if (intent == null) return null;
        try {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            return macFromObject(device);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String macFromObject(Object item) {
        if (item == null) return null;
        if (item instanceof BluetoothDevice) {
            try {
                return normalizeMac(((BluetoothDevice) item).getAddress());
            } catch (Throwable ignored) {
                return null;
            }
        }
        return normalizeMac(String.valueOf(item));
    }

    private void logQueryFailure(Throwable t) {
        if (loggedQueryFailure) return;
        loggedQueryFailure = true;
        MLog.w("A2DP active device query unavailable; route readiness will fail open", t);
    }

    private void logPermissionDenied() {
        if (loggedPermissionDenied) return;
        loggedPermissionDenied = true;
        MLog.event("a2dp.active.query.denied", "mode", "broadcast_only");
    }

    private static boolean containsSecurityException(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof SecurityException) return true;
            cur = cur.getCause();
        }
        return false;
    }

    static String normalizeMac(String mac) {
        if (mac == null) return null;
        Matcher matcher = MAC_PATTERN.matcher(mac);
        if (!matcher.find()) return null;
        return matcher.group().toUpperCase(Locale.ROOT);
    }

    static String redactMac(String mac) {
        String key = normalizeMac(mac);
        if (key == null || key.length() < 17) return "?";
        return key.substring(0, 2) + "**" + key.substring(15);
    }
}
