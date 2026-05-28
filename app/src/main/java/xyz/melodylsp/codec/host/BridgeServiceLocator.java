package xyz.melodylsp.codec.host;

import android.os.IBinder;

import java.lang.reflect.Method;

import xyz.melodylsp.codec.bridge.ICodecBridge;
import xyz.melodylsp.codec.util.MLog;

/**
 * Looks up the system-process bridge service via reflection on {@code android.os.ServiceManager}.
 * Returns {@code null} when the service is not registered (the {@code com.android.bluetooth}
 * scope is not enabled or the system hook hasn't been installed).
 */
public final class BridgeServiceLocator {

    public static final String SERVICE_NAME = "melody_codec_bridge";

    private BridgeServiceLocator() {
    }

    public static ICodecBridge get() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method getService = sm.getMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, SERVICE_NAME);
            if (binder == null) return null;
            return ICodecBridge.Stub.asInterface(binder);
        } catch (Throwable t) {
            MLog.w("BridgeServiceLocator.get failed", t);
            return null;
        }
    }
}
