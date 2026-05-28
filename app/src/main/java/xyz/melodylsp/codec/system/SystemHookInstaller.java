package xyz.melodylsp.codec.system;

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
        hookConstructors(a2dpCls);
        hookLifecycle(a2dpCls);
        hookCodecConfigUpdated(a2dpCls);
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
            MLog.e("ensureBridgeRegistered failed", t);
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
}
