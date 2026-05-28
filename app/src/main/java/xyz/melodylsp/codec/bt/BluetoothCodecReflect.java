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
        Object status;
        try {
            Method m = proxy.getClass().getMethod("getCodecStatus", BluetoothDevice.class);
            status = m.invoke(proxy, device);
        } catch (Exception e) {
            throw new BluetoothCodecReflectException(
                    proxy.getClass().getName(), "getCodecStatus", e);
        }
        if (status == null) return null;
        return readSnapshotFromCodecStatus(mac, status);
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

    private CodecSnapshot readSnapshotFromCodecStatus(String mac, Object status) {
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
                    selSpecific1, sampleRateMask);
        } catch (Exception e) {
            throw new BluetoothCodecReflectException(statusCls.getName(), "decode", e);
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
