package xyz.melodylsp.codec.system;

import android.content.Context;
import android.os.Binder;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import xyz.melodylsp.codec.MelodyCodecLspEntry;
import xyz.melodylsp.codec.util.MLog;

/**
 * Installs the privileged {@link CodecBridgeService} inside {@code com.android.bluetooth}.
 *
 * <p>The hook attaches in two places:</p>
 * <ul>
 *   <li>{@code A2dpService} constructors and {@code start()} / {@code onStart()} variants —
 *       a one-shot service registration so the bridge becomes available as soon as A2dpService
 *       stands up.</li>
 *   <li>{@code A2dpService.codecConfigUpdated} — pushes snapshots to subscribed listeners.</li>
 * </ul>
 */
public final class SystemHookInstaller {

    private static final String CLASS_A2DP_SERVICE = "com.android.bluetooth.a2dp.A2dpService";
    private static final String CLASS_BT_UTILS = "com.android.bluetooth.Utils";
    private static final String MELODY_PKG = "com.oplus.melody";

    private final MelodyCodecLspEntry module;
    private final ClassLoader classLoader;
    private CodecBridgeService bridgeService;

    public SystemHookInstaller(MelodyCodecLspEntry module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
    }

    public void install() {
        Class<?> a2dpCls;
        try {
            a2dpCls = Class.forName(CLASS_A2DP_SERVICE, false, classLoader);
        } catch (Throwable t) {
            MLog.w("A2dpService not found in com.android.bluetooth (scope misconfigured?)");
            return;
        }
        hookCdmAssociationForMelody();
        hookConstructors(a2dpCls);
        hookLifecycle(a2dpCls);
        hookCodecConfigUpdated(a2dpCls);
    }

    private void hookCdmAssociationForMelody() {
        Class<?> utilsCls;
        try {
            utilsCls = Class.forName(CLASS_BT_UTILS, false, classLoader);
        } catch (Throwable t) {
            MLog.w("Bluetooth Utils class not found; CDM bypass unavailable");
            return;
        }
        int hooked = 0;
        for (Method m : utilsCls.getDeclaredMethods()) {
            if (!isCdmEnforcementMethod(m.getName())) continue;
            module.hook(m).intercept(chain -> {
                Object[] args = chain.getArgs().toArray();
                if (isMelodyA2dpCodecCall(args)) {
                    MLog.event("cdm.bypass", "method", m.getName());
                    return bypassReturnValue(m.getReturnType());
                }
                return chain.proceed();
            });
            hooked++;
        }
        MLog.event("cdm.hooks", "count", hooked);
    }

    /**
     * Match the CDM / privileged-association enforcement helpers (TODO A6). The canonical name
     * is {@code enforceCdmAssociation}, but OPPO can rename it on a ROM bump; we additionally
     * match any helper whose name advertises a CDM / association / privileged check so a rename
     * does not silently re-block Path A. Matching is conservative — the actual bypass still
     * requires {@link #isMelodyA2dpCodecCall} to confirm the live call originates from melody's
     * {@code setCodecConfigPreference} stack, so over-matching a method name cannot let an
     * unrelated caller through.
     */
    private static boolean isCdmEnforcementMethod(String name) {
        if (name == null) return false;
        if ("enforceCdmAssociation".equals(name)) return true;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("cdm") && lower.contains("assoc")) return true;
        if (lower.contains("enforceassociation")) return true;
        if (lower.contains("requirebluetoothprivileged")) return true;
        if (lower.contains("enforcebluetoothprivileged")) return true;
        return false;
    }

    private static Object bypassReturnValue(Class<?> returnType) {
        if (returnType == void.class) return null;
        if (returnType == boolean.class || returnType == Boolean.class) return Boolean.TRUE;
        if (returnType == int.class || returnType == Integer.class) return 0;
        if (returnType == long.class || returnType == Long.class) return 0L;
        if (returnType == float.class || returnType == Float.class) return 0f;
        if (returnType == double.class || returnType == Double.class) return 0d;
        if (returnType == byte.class || returnType == Byte.class) return (byte) 0;
        if (returnType == short.class || returnType == Short.class) return (short) 0;
        if (returnType == char.class || returnType == Character.class) return (char) 0;
        return null;
    }

    private void hookConstructors(Class<?> a2dpCls) {
        for (Constructor<?> ctor : a2dpCls.getDeclaredConstructors()) {
            module.hook(ctor).intercept(chain -> {
                Object result = chain.proceed();
                ensureBridgeRegistered(chain.getThisObject());
                return result;
            });
        }
    }

    private void hookLifecycle(Class<?> a2dpCls) {
        for (Method m : a2dpCls.getDeclaredMethods()) {
            String name = m.getName();
            if (name.equals("start") || name.equals("onStart") || name.equals("doStart")) {
                module.hook(m).intercept(chain -> {
                    Object result = chain.proceed();
                    ensureBridgeRegistered(chain.getThisObject());
                    return result;
                });
            }
        }
    }

    private synchronized void ensureBridgeRegistered(Object a2dpService) {
        if (bridgeService != null || a2dpService == null) return;
        try {
            bridgeService = new CodecBridgeService(a2dpService);
            bridgeService.registerToServiceManager();
            MLog.event("system.bridge.registered");
        } catch (Throwable t) {
            // Most ROMs deny ServiceManager.addService from com.android.bluetooth via SELinux.
            // This is expected; the host-side falls back to the direct A2DP API and finally to
            // Settings.Global, so failure here is not fatal. Demote to WARN once per process.
            MLog.w("ensureBridgeRegistered failed (likely SELinux) — host-side fallback will be used");
            bridgeService = null;
        }
    }

    private void hookCodecConfigUpdated(Class<?> a2dpCls) {
        for (Method m : a2dpCls.getDeclaredMethods()) {
            if (!"codecConfigUpdated".equals(m.getName())) continue;
            module.hook(m).intercept(chain -> {
                Object result = chain.proceed();
                CodecBridgeService bridge = bridgeService;
                if (bridge != null) {
                    try {
                        bridge.notifyCodecChanged(chain.getArgs().toArray());
                    } catch (Throwable t) {
                        MLog.w("notifyCodecChanged failed", t);
                    }
                }
                return result;
            });
        }
    }

    private static boolean isMelodyA2dpCodecCall(Object[] args) {
        if (!isA2dpCodecPreferenceStack()) return false;
        for (Object arg : args) {
            if (MELODY_PKG.equals(String.valueOf(arg))) return true;
        }
        Context context = findContext(args);
        if (context == null) context = currentApplication();
        if (context == null) return false;
        int callingUid = Binder.getCallingUid();
        String[] packages = context.getPackageManager().getPackagesForUid(callingUid);
        if (packages == null) return false;
        for (String pkg : packages) {
            if (MELODY_PKG.equals(pkg)) return true;
        }
        return false;
    }

    private static boolean isA2dpCodecPreferenceStack() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement e : stack) {
            if ("setCodecConfigPreference".equals(e.getMethodName())
                    && e.getClassName().contains("A2dpService")) {
                return true;
            }
        }
        return false;
    }

    private static Context findContext(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof Context) return (Context) arg;
        }
        return null;
    }

    private static Context currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method currentApplication = at.getMethod("currentApplication");
            Object app = currentApplication.invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
