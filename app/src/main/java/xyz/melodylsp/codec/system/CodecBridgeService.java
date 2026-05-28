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
import xyz.melodylsp.codec.util.MLog;

/**
 * Privileged bridge running inside {@code com.android.bluetooth}. Performs the same reflective
 * dance as {@link xyz.melodylsp.codec.bt.BluetoothCodecReflect} but invoked directly on the
 * already-instantiated {@code A2dpService} so we bypass any callsite permission checks.
 */
public final class CodecBridgeService extends ICodecBridge.Stub {

    private static final String SERVICE_NAME = "melody_codec_bridge";
    private static final String MELODY_PKG = "com.oplus.melody";

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
        try {
            BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(mac);
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
                selSpecific1, sampleRateMask);
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
