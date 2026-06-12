package xyz.melodylsp.codec.system;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;

import java.lang.reflect.Method;
import java.util.List;

import xyz.melodylsp.codec.bridge.CodecRequest;
import xyz.melodylsp.codec.bridge.CodecSnapshot;
import xyz.melodylsp.codec.bridge.ICodecBridge;
import xyz.melodylsp.codec.bridge.ICodecBridgeListener;
import xyz.melodylsp.codec.bt.BluetoothCodecReflect;
import xyz.melodylsp.codec.util.MLog;

/**
 * Privileged bridge running inside {@code com.android.bluetooth}. Performs the same reflective
 * dance as {@link xyz.melodylsp.codec.bt.BluetoothCodecReflect} but invoked directly on the
 * already-instantiated {@code A2dpService} so we bypass any callsite permission checks.
 */
public final class CodecBridgeService extends ICodecBridge.Stub {

    private static final String SERVICE_NAME = "melody_codec_bridge";
    private static final String MELODY_PKG = "com.oplus.melody";
    private static final int OPTIONAL_CODECS_PREF_DISABLED = 0;
    private static final int OPTIONAL_CODECS_PREF_ENABLED = 1;
    private static final int OPTIONAL_CODECS_UNKNOWN = -1;

    private final Object a2dpService;
    private final Class<?> a2dpClass;
    private final RemoteCallbackList<ICodecBridgeListener> listeners = new RemoteCallbackList<>();

    public CodecBridgeService(Object a2dpService) {
        this.a2dpService = a2dpService;
        this.a2dpClass = a2dpService.getClass();
    }

    public void registerToServiceManager() throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        Method addService = sm.getMethod("addService", String.class, IBinder.class);
        addService.invoke(null, SERVICE_NAME, this);
    }

    @Override
    public CodecSnapshot getStatus(String mac) throws RemoteException {
        if (!isMelodyCaller()) {
            return null;
        }
        return getStatusUnchecked(mac);
    }

    CodecSnapshot getStatusUnchecked(String mac) {
        try {
            BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(mac);
            if (!BluetoothCodecReflect.isAclConnectedOrUnknown(device)) {
                MLog.event("codec.bridge.status.skip.acl_disconnected", "mac", mac);
                return null;
            }
            Method m = a2dpClass.getMethod("getCodecStatus", BluetoothDevice.class);
            Object status = m.invoke(a2dpService, device);
            if (status == null) return null;
            return decode(mac, status);
        } catch (Throwable t) {
            MLog.w("bridge.getStatus failed", t);
            return null;
        }
    }

    @Override
    public int setCodec(CodecRequest request) throws RemoteException {
        if (!isMelodyCaller()) {
            return CodecRequest.RESULT_DENIED;
        }
        return setCodecUnchecked(request);
    }

    @Override
    public int setOptionalCodecs(String mac, boolean enable) throws RemoteException {
        if (!isMelodyCaller()) {
            return CodecRequest.RESULT_DENIED;
        }
        return setOptionalCodecsUnchecked(mac, enable);
    }

    int setCodecUnchecked(CodecRequest request) {
        if (request == null || request.mac == null) return CodecRequest.RESULT_INVALID;
        try {
            BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(request.mac);
            Class<?> cfgCls = Class.forName("android.bluetooth.BluetoothCodecConfig");
            Object cfg = newCodecConfig(cfgCls, request);
            Method m = a2dpClass.getMethod("setCodecConfigPreference", BluetoothDevice.class, cfgCls);
            m.invoke(a2dpService, device, cfg);
            return CodecRequest.RESULT_OK;
        } catch (Throwable t) {
            MLog.w("bridge.setCodec failed", t);
            return CodecRequest.RESULT_ERROR;
        }
    }

    int setOptionalCodecsUnchecked(String mac, boolean enable) {
        if (mac == null || mac.isEmpty()) return CodecRequest.RESULT_INVALID;
        try {
            BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(mac);
            boolean wrotePreference = invokeOptionalCodecPreference(device, enable);
            boolean toggled = invokeOptionalCodecToggle(device, enable);
            if (!wrotePreference && !toggled) {
                return CodecRequest.RESULT_ERROR;
            }
            MLog.event("bridge.setOptionalCodecs", "mac", mac, "enable", enable,
                    "pref", wrotePreference, "toggle", toggled);
            return CodecRequest.RESULT_OK;
        } catch (Throwable t) {
            MLog.w("bridge.setOptionalCodecs failed", t);
            return CodecRequest.RESULT_ERROR;
        }
    }

    @Override
    public void register(ICodecBridgeListener listener) throws RemoteException {
        if (!isMelodyCaller() || listener == null) return;
        listeners.register(listener);
    }

    @Override
    public void unregister(ICodecBridgeListener listener) throws RemoteException {
        if (listener == null) return;
        listeners.unregister(listener);
    }

    /** Called from the {@code codecConfigUpdated} hook. */
    void notifyCodecChanged(Object[] args) {
        // Best-effort: derive a snapshot from the args if possible. We pass through an empty
        // snapshot if the args do not contain (BluetoothDevice, BluetoothCodecStatus).
        BluetoothDevice device = null;
        Object status = null;
        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof BluetoothDevice) {
                    device = (BluetoothDevice) arg;
                } else if (arg != null
                        && "android.bluetooth.BluetoothCodecStatus".equals(arg.getClass().getName())) {
                    status = arg;
                }
            }
        }
        if (device == null || status == null) return;
        CodecSnapshot snapshot;
        try {
            snapshot = decode(device.getAddress(), status);
        } catch (Throwable t) {
            MLog.w("notifyCodecChanged decode failed", t);
            return;
        }
        int n = listeners.beginBroadcast();
        try {
            for (int i = 0; i < n; i++) {
                try {
                    listeners.getBroadcastItem(i).onCodecChanged(snapshot);
                } catch (RemoteException ignored) {
                }
            }
        } finally {
            listeners.finishBroadcast();
        }
    }

    private CodecSnapshot decode(String mac, Object status) throws Exception {
        Class<?> statusCls = status.getClass();
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
        if (selectableArr instanceof List<?>) {
            for (Object cap : (List<?>) selectableArr) {
                int t = (int) cap.getClass().getMethod("getCodecType").invoke(cap);
                if (t != activeCodec) continue;
                sampleRateMask = (int) cap.getClass().getMethod("getSampleRate").invoke(cap);
                long capSpec1 = (long) cap.getClass().getMethod("getCodecSpecific1").invoke(cap);
                if (capSpec1 != 0L) {
                    int bitCount = Math.max(1, Long.bitCount(capSpec1));
                    long[] tmp = new long[bitCount];
                    long working = capSpec1;
                    int idx = 0;
                    for (int bit = 0; bit < 16 && working != 0; bit++) {
                        long bitVal = 1L << bit;
                        if ((working & bitVal) != 0) {
                            tmp[idx++] = bitVal;
                            working &= ~bitVal;
                        }
                    }
                    if (idx == 0) {
                        selSpecific1 = new long[]{capSpec1};
                    } else {
                        selSpecific1 = new long[idx];
                        System.arraycopy(tmp, 0, selSpecific1, 0, idx);
                    }
                }
                break;
            }
        }
        return CodecSnapshot.now(
                mac, activeCodec, rate, bits, channel, s1, s2, s3, s4,
                selSpecific1, sampleRateMask,
                selectableCodecTypes,
                selectableCodecSampleRates,
                selectableCodecBitsPerSample,
                selectableCodecChannelModes,
                selectableCodecSpecific1Values,
                readOptionalCodecsSupported(mac),
                readOptionalCodecsEnabled(mac));
    }

    private int[] extractSelectableCodecTypes(Object selectableArr) {
        if (!(selectableArr instanceof List<?>)) return new int[0];
        List<?> list = (List<?>) selectableArr;
        int[] out = new int[list.size()];
        int i = 0;
        for (Object cap : list) {
            out[i++] = readCodecIntField(cap, "getCodecType");
        }
        return out;
    }

    private int[] extractSelectableCodecIntFields(Object selectableArr, String methodName) {
        if (!(selectableArr instanceof List<?>)) return new int[0];
        List<?> list = (List<?>) selectableArr;
        int[] out = new int[list.size()];
        int i = 0;
        for (Object cap : list) {
            out[i++] = readCodecIntField(cap, methodName);
        }
        return out;
    }

    private long[] extractSelectableCodecLongFields(Object selectableArr, String methodName) {
        if (!(selectableArr instanceof List<?>)) return new long[0];
        List<?> list = (List<?>) selectableArr;
        long[] out = new long[list.size()];
        int i = 0;
        for (Object cap : list) {
            out[i++] = readCodecLongField(cap, methodName);
        }
        return out;
    }

    private int readCodecIntField(Object cap, String methodName) {
        if (cap == null) return 0;
        try {
            Object value = cap.getClass().getMethod(methodName).invoke(cap);
            return value instanceof Number ? ((Number) value).intValue() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private long readCodecLongField(Object cap, String methodName) {
        if (cap == null) return 0L;
        try {
            Object value = cap.getClass().getMethod(methodName).invoke(cap);
            return value instanceof Number ? ((Number) value).longValue() : 0L;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private int readOptionalCodecsSupported(String mac) {
        return readOptionalCodecInt(mac,
                "getSupportsOptionalCodecs", "isOptionalCodecsSupported");
    }

    private int readOptionalCodecsEnabled(String mac) {
        return readOptionalCodecInt(mac,
                "getOptionalCodecsEnabled", "isOptionalCodecsEnabled");
    }

    private int readOptionalCodecInt(String mac, String... methodNames) {
        if (mac == null || mac.isEmpty()) return OPTIONAL_CODECS_UNKNOWN;
        try {
            BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(mac);
            Throwable last = null;
            for (String methodName : methodNames) {
                try {
                    Method m = findA2dpMethod(methodName, BluetoothDevice.class);
                    Object out = m.invoke(a2dpService, device);
                    if (out instanceof Number) return ((Number) out).intValue();
                    if (out instanceof Boolean) return (Boolean) out ? 1 : 0;
                } catch (Throwable t) {
                    last = t;
                }
            }
            if (last != null) {
                MLog.w("bridge optional codec read failed: " + joinMethodNames(methodNames), last);
            }
        } catch (Throwable t) {
            MLog.w("bridge optional codec read failed: " + joinMethodNames(methodNames), t);
        }
        return OPTIONAL_CODECS_UNKNOWN;
    }

    private boolean invokeOptionalCodecPreference(BluetoothDevice device, boolean enable) {
        try {
            Method m = findA2dpMethod(
                    "setOptionalCodecsEnabled", BluetoothDevice.class, int.class);
            m.invoke(a2dpService, device,
                    enable ? OPTIONAL_CODECS_PREF_ENABLED : OPTIONAL_CODECS_PREF_DISABLED);
            return true;
        } catch (Throwable t) {
            MLog.w("bridge.setOptionalCodecsEnabled failed", t);
            return false;
        }
    }

    private boolean invokeOptionalCodecToggle(BluetoothDevice device, boolean enable) {
        String name = enable ? "enableOptionalCodecs" : "disableOptionalCodecs";
        try {
            Method m = findA2dpMethod(name, BluetoothDevice.class);
            m.invoke(a2dpService, device);
            return true;
        } catch (Throwable t) {
            MLog.w("bridge." + name + " failed", t);
            return false;
        }
    }

    private Method findA2dpMethod(String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        try {
            return a2dpClass.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException ignored) {
            Method m = a2dpClass.getDeclaredMethod(name, parameterTypes);
            m.setAccessible(true);
            return m;
        }
    }

    private static String joinMethodNames(String[] names) {
        if (names == null || names.length == 0) return "optional";
        StringBuilder sb = new StringBuilder(names[0]);
        for (int i = 1; i < names.length; i++) {
            sb.append('/').append(names[i]);
        }
        return sb.toString();
    }

    private Object newCodecConfig(Class<?> cfgCls, CodecRequest req) throws Exception {
        for (java.lang.reflect.Constructor<?> ctor : cfgCls.getConstructors()) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length == 9 && params[0] == int.class) {
                return ctor.newInstance(
                        req.codecType, 1_000_000,
                        req.sampleRate, req.bitsPerSample, req.channelMode,
                        req.codecSpecific1, req.codecSpecific2,
                        req.codecSpecific3, req.codecSpecific4);
            }
            if (params.length == 8 && params[0] == int.class) {
                return ctor.newInstance(
                        req.codecType,
                        req.sampleRate, req.bitsPerSample, req.channelMode,
                        req.codecSpecific1, req.codecSpecific2,
                        req.codecSpecific3, req.codecSpecific4);
            }
        }
        throw new NoSuchMethodException("BluetoothCodecConfig ctor");
    }

    private boolean isMelodyCaller() {
        int callingUid = Binder.getCallingUid();
        if (callingUid == Process.SYSTEM_UID) return true;
        try {
            Class<?> amCls = Class.forName("android.app.ActivityThread");
            Method currentApp = amCls.getMethod("currentApplication");
            android.app.Application app = (android.app.Application) currentApp.invoke(null);
            int melodyUid = app.getPackageManager().getPackageUid(MELODY_PKG, 0);
            return melodyUid == callingUid;
        } catch (Throwable t) {
            MLog.w("isMelodyCaller resolution failed", t);
            return false;
        }
    }
}
