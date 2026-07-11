package xyz.melodylsp.codec.bt;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import xyz.melodylsp.codec.bridge.CodecRequest;
import xyz.melodylsp.codec.bridge.CodecSnapshot;
import xyz.melodylsp.codec.util.MLog;

/**
 * Reflective wrapper for the {@code @hide} A2DP codec API. Every reflective entry point is
 * wrapped in {@link BluetoothCodecReflectException} so callers never see raw
 * {@link NoSuchMethodException} / {@link java.lang.reflect.InvocationTargetException}
 * (Requirement 9.1).
 */
public final class BluetoothCodecReflect {

    private static final int CODEC_PRIORITY_HIGHEST = 1_000_000;
    private static final int OPTIONAL_CODECS_PREF_DISABLED = 0;
    private static final int OPTIONAL_CODECS_PREF_ENABLED = 1;
    private static final int OPTIONAL_CODECS_UNKNOWN = -1;

    private final Context context;
    private volatile BluetoothA2dp a2dpProxy;

    public BluetoothCodecReflect(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Acquires the A2DP profile proxy with a 1000 ms timeout (Requirement 3.4). */
    public BluetoothA2dp acquireProxyBlocking() {
        BluetoothA2dp current = a2dpProxy;
        if (current != null) return current;

        BluetoothAdapter adapter = resolveAdapter();
        CompletableFuture<BluetoothA2dp> future = new CompletableFuture<>();
        adapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile == BluetoothProfile.A2DP) {
                    future.complete((BluetoothA2dp) proxy);
                }
            }

            @Override
            public void onServiceDisconnected(int profile) {
                // No-op: keep using the cached reference.
            }
        }, BluetoothProfile.A2DP);

        try {
            BluetoothA2dp proxy = future.get(1000L, TimeUnit.MILLISECONDS);
            a2dpProxy = proxy;
            return proxy;
        } catch (Exception e) {
            throw new BluetoothCodecReflectException(
                    "BluetoothAdapter", "getProfileProxy", e);
        }
    }

    /**
     * Reads the active codec status via {@code BluetoothA2dp.getCodecStatus(BluetoothDevice)}.
     * Returns {@code null} if the platform reports null (likely no active device).
     */
    public CodecSnapshot readStatus(String mac) {
        BluetoothA2dp proxy = acquireProxyBlocking();
        BluetoothDevice device = ensureDevice(mac);
        if (!isAclConnectedOrUnknown(device)) {
            MLog.event("codec.status.skip.acl_disconnected", "mac", mac);
            return null;
        }
        if (!isConnected(proxy, device)) return null;
        Object status;
        try {
            Method m = proxy.getClass().getMethod("getCodecStatus", BluetoothDevice.class);
            status = m.invoke(proxy, device);
        } catch (Exception e) {
            throw new BluetoothCodecReflectException(
                    proxy.getClass().getName(), "getCodecStatus", e);
        }
        if (status == null) return null;
        return readSnapshotFromCodecStatus(mac, status, proxy, device);
    }

    @android.annotation.SuppressLint("MissingPermission")
    private static boolean isConnected(BluetoothA2dp proxy, BluetoothDevice device) {
        try {
            String target = device.getAddress();
            if (target == null) return true;
            List<BluetoothDevice> connected = proxy.getConnectedDevices();
            if (connected == null) return false;
            for (BluetoothDevice item : connected) {
                String address = item != null ? item.getAddress() : null;
                if (address != null && address.equalsIgnoreCase(target)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * Hidden framework ACL check. Some OPlus builds keep a stale A2DP codec status after the
     * physical link is gone; in that state profile/capability APIs may still look connected.
     */
    public static boolean isAclConnectedOrUnknown(BluetoothDevice device) {
        if (device == null) return true;
        try {
            Method m = device.getClass().getMethod("isConnected");
            Object out = m.invoke(device);
            if (out instanceof Boolean) return (Boolean) out;
        } catch (Throwable ignored) {
        }
        return true;
    }

    /** Writes a {@link CodecRequest} via {@code setCodecConfigPreference}. */
    public void setCodec(CodecRequest request) {
        BluetoothA2dp proxy = acquireProxyBlocking();
        BluetoothDevice device = ensureDevice(request.mac);
        Object codecConfig = buildCodecConfig(request);
        try {
            Method m = proxy.getClass().getMethod(
                    "setCodecConfigPreference", BluetoothDevice.class, codecConfig.getClass());
            m.invoke(proxy, device, codecConfig);
            MLog.event("a2dp.setCodecConfigPreference", "request", request);
        } catch (Exception e) {
            throw new BluetoothCodecReflectException(
                    proxy.getClass().getName(), "setCodecConfigPreference", e);
        }
    }

    /** Mirrors SettingsLib's high-quality audio switch: persist preference, then renegotiate. */
    public void setOptionalCodecs(String mac, boolean enable) {
        BluetoothA2dp proxy = acquireProxyBlocking();
        BluetoothDevice device = ensureDevice(mac);
        boolean wrotePreference = invokeOptionalCodecPreference(proxy, device, enable);
        boolean toggled = invokeOptionalCodecToggle(proxy, device, enable);
        if (!wrotePreference && !toggled) {
            throw new BluetoothCodecReflectException(
                    proxy.getClass().getName(), "setOptionalCodecs", null);
        }
        MLog.event("a2dp.setOptionalCodecs", "mac", mac, "enable", enable,
                "pref", wrotePreference, "toggle", toggled);
    }

    private BluetoothAdapter resolveAdapter() {
        BluetoothManager mgr = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = mgr != null ? mgr.getAdapter() : BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            throw new BluetoothCodecReflectException("BluetoothAdapter", "default", null);
        }
        return adapter;
    }

    private BluetoothDevice ensureDevice(String mac) {
        if (mac == null) {
            throw new BluetoothCodecReflectException("BluetoothA2dp", "ensureDevice", null);
        }
        try {
            return resolveAdapter().getRemoteDevice(mac);
        } catch (IllegalArgumentException e) {
            throw new BluetoothCodecReflectException(
                    "BluetoothAdapter", "getRemoteDevice(" + mac + ")", e);
        }
    }

    private CodecSnapshot readSnapshotFromCodecStatus(
            String mac, Object status, BluetoothA2dp proxy, BluetoothDevice device) {
        Class<?> statusCls = status.getClass();
        try {
            Object active = statusCls.getMethod("getCodecConfig").invoke(status);
            Object selectableArr = statusCls.getMethod("getCodecsSelectableCapabilities").invoke(status);
            int activeCodec = (int) active.getClass().getMethod("getCodecType").invoke(active);
            int rate = (int) active.getClass().getMethod("getSampleRate").invoke(active);
            int bits = (int) active.getClass().getMethod("getBitsPerSample").invoke(active);
            int channel = (int) active.getClass().getMethod("getChannelMode").invoke(active);
            long s1 = (long) active.getClass().getMethod("getCodecSpecific1").invoke(active);
            long s2 = (long) active.getClass().getMethod("getCodecSpecific2").invoke(active);
            long s3 = (long) active.getClass().getMethod("getCodecSpecific3").invoke(active);
            long s4 = (long) active.getClass().getMethod("getCodecSpecific4").invoke(active);

            int sampleRateMask = rate;
            long[] selSpecific1 = new long[0];
            int[] selectableCodecTypes = extractSelectableCodecTypes(selectableArr);
            int[] selectableCodecSampleRates =
                    extractSelectableCodecIntFields(selectableArr, "getSampleRate");
            int[] selectableCodecBitsPerSample =
                    extractSelectableCodecIntFields(selectableArr, "getBitsPerSample");
            int[] selectableCodecChannelModes =
                    extractSelectableCodecIntFields(selectableArr, "getChannelMode");
            long[] selectableCodecSpecific1Values =
                    extractSelectableCodecLongFields(selectableArr, "getCodecSpecific1");
            if (selectableArr != null) {
                List<?> list = (List<?>) selectableArr;
                for (Object cap : list) {
                    int t = (int) cap.getClass().getMethod("getCodecType").invoke(cap);
                    if (t != activeCodec) continue;
                    sampleRateMask = (int) cap.getClass().getMethod("getSampleRate").invoke(cap);
                    long capSpec1 = (long) cap.getClass().getMethod("getCodecSpecific1").invoke(cap);
                    if (capSpec1 != 0L) {
                        selSpecific1 = decodeSpecific1Capability(capSpec1);
                    }
                    break;
                }
            }
            return CodecSnapshot.now(
                    mac,
                    activeCodec, rate, bits, channel,
                    s1, s2, s3, s4,
                    selSpecific1, sampleRateMask,
                    selectableCodecTypes,
                    selectableCodecSampleRates,
                    selectableCodecBitsPerSample,
                    selectableCodecChannelModes,
                    selectableCodecSpecific1Values,
                    readOptionalCodecsSupported(proxy, device),
                    readOptionalCodecsEnabled(proxy, device));
        } catch (Exception e) {
            throw new BluetoothCodecReflectException(statusCls.getName(), "decode", e);
        }
    }

    private static int[] extractSelectableCodecTypes(Object selectableArr) {
        if (!(selectableArr instanceof List<?>)) return new int[0];
        List<?> list = (List<?>) selectableArr;
        int[] out = new int[list.size()];
        int i = 0;
        for (Object cap : list) {
            out[i++] = readCodecIntField(cap, "getCodecType");
        }
        return out;
    }

    private static int[] extractSelectableCodecIntFields(Object selectableArr, String methodName) {
        if (!(selectableArr instanceof List<?>)) return new int[0];
        List<?> list = (List<?>) selectableArr;
        int[] out = new int[list.size()];
        int i = 0;
        for (Object cap : list) {
            out[i++] = readCodecIntField(cap, methodName);
        }
        return out;
    }

    private static long[] extractSelectableCodecLongFields(Object selectableArr, String methodName) {
        if (!(selectableArr instanceof List<?>)) return new long[0];
        List<?> list = (List<?>) selectableArr;
        long[] out = new long[list.size()];
        int i = 0;
        for (Object cap : list) {
            out[i++] = readCodecLongField(cap, methodName);
        }
        return out;
    }

    private static int readCodecIntField(Object cap, String methodName) {
        if (cap == null) return 0;
        try {
            Object value = cap.getClass().getMethod(methodName).invoke(cap);
            return value instanceof Number ? ((Number) value).intValue() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static long readCodecLongField(Object cap, String methodName) {
        if (cap == null) return 0L;
        try {
            Object value = cap.getClass().getMethod(methodName).invoke(cap);
            return value instanceof Number ? ((Number) value).longValue() : 0L;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static int readOptionalCodecsSupported(BluetoothA2dp proxy, BluetoothDevice device) {
        return readOptionalCodecInt(proxy, device, "isOptionalCodecsSupported");
    }

    private static int readOptionalCodecsEnabled(BluetoothA2dp proxy, BluetoothDevice device) {
        return readOptionalCodecInt(proxy, device, "isOptionalCodecsEnabled");
    }

    private static int readOptionalCodecInt(
            BluetoothA2dp proxy, BluetoothDevice device, String methodName) {
        if (proxy == null || device == null) return OPTIONAL_CODECS_UNKNOWN;
        try {
            Method m = proxy.getClass().getMethod(methodName, BluetoothDevice.class);
            Object out = m.invoke(proxy, device);
            if (out instanceof Number) return ((Number) out).intValue();
            if (out instanceof Boolean) return (Boolean) out ? 1 : 0;
        } catch (Throwable t) {
            MLog.w("optional codec read failed: " + methodName, t);
        }
        return OPTIONAL_CODECS_UNKNOWN;
    }

    private static boolean invokeOptionalCodecPreference(
            BluetoothA2dp proxy, BluetoothDevice device, boolean enable) {
        try {
            Method m = proxy.getClass().getMethod(
                    "setOptionalCodecsEnabled", BluetoothDevice.class, int.class);
            m.invoke(proxy, device,
                    enable ? OPTIONAL_CODECS_PREF_ENABLED : OPTIONAL_CODECS_PREF_DISABLED);
            return true;
        } catch (Throwable t) {
            MLog.w("setOptionalCodecsEnabled failed", t);
            return false;
        }
    }

    private static boolean invokeOptionalCodecToggle(
            BluetoothA2dp proxy, BluetoothDevice device, boolean enable) {
        String name = enable ? "enableOptionalCodecs" : "disableOptionalCodecs";
        try {
            Method m = proxy.getClass().getMethod(name, BluetoothDevice.class);
            m.invoke(proxy, device);
            return true;
        } catch (Throwable t) {
            MLog.w(name + " failed", t);
            return false;
        }
    }

    /**
     * Decodes a vendor "selectable specific1" word into the list of supported quality values.
     * Returns a single-element array containing the raw value if no bit is set so that the UI
     * never silently drops an entry the platform meant to expose.
     */
    private static long[] decodeSpecific1Capability(long mask) {
        long working = mask;
        long[] tmp = new long[Math.max(1, Long.bitCount(working))];
        int idx = 0;
        for (int bit = 0; bit < 16 && working != 0; bit++) {
            long bitVal = 1L << bit;
            if ((working & bitVal) != 0) {
                tmp[idx++] = bitVal;
                working &= ~bitVal;
            }
        }
        if (idx == 0) {
            return new long[]{mask};
        }
        long[] copy = new long[idx];
        System.arraycopy(tmp, 0, copy, 0, idx);
        return copy;
    }

    /**
     * Build the {@code BluetoothCodecConfig} via reflection. Two known constructor shapes:
     * 9-args (with priority) on Android 13–14, 8-args on legacy releases.
     */
    private Object buildCodecConfig(CodecRequest req) {
        Class<?> cfgCls;
        try {
            cfgCls = Class.forName("android.bluetooth.BluetoothCodecConfig");
        } catch (ClassNotFoundException e) {
            throw new BluetoothCodecReflectException(
                    "android.bluetooth.BluetoothCodecConfig", "Class.forName", e);
        }
        try {
            for (Constructor<?> ctor : cfgCls.getConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length == 9 && params[0] == int.class) {
                    return ctor.newInstance(
                            req.codecType,
                            CODEC_PRIORITY_HIGHEST,
                            req.sampleRate,
                            req.bitsPerSample,
                            req.channelMode,
                            req.codecSpecific1,
                            req.codecSpecific2,
                            req.codecSpecific3,
                            req.codecSpecific4);
                }
                if (params.length == 8 && params[0] == int.class) {
                    return ctor.newInstance(
                            req.codecType,
                            req.sampleRate,
                            req.bitsPerSample,
                            req.channelMode,
                            req.codecSpecific1,
                            req.codecSpecific2,
                            req.codecSpecific3,
                            req.codecSpecific4);
                }
            }
            throw new BluetoothCodecReflectException(
                    cfgCls.getName(), "<init>", new NoSuchMethodException("no matching ctor"));
        } catch (BluetoothCodecReflectException e) {
            throw e;
        } catch (Exception e) {
            throw new BluetoothCodecReflectException(cfgCls.getName(), "<init>", e);
        }
    }

    /** Wraps any reflective failure so the caller sees a single exception type. */
    public static final class BluetoothCodecReflectException extends RuntimeException {
        public final String className;
        public final String methodName;

        public BluetoothCodecReflectException(String className, String methodName, Throwable cause) {
            super("reflect failed: " + className + "#" + methodName, cause);
            this.className = className;
            this.methodName = methodName;
        }
    }
}
