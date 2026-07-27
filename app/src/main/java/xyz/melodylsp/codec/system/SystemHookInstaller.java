package xyz.melodylsp.codec.system;

import android.app.Application;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dalvik.system.DexFile;

import xyz.melodylsp.codec.MelodyCodecLspEntry;
import xyz.melodylsp.codec.bridge.CodecIpc;
import xyz.melodylsp.codec.leaudio.BluetoothLeAudioBridge;
import xyz.melodylsp.codec.util.MLog;
import xyz.melodylsp.codec.util.TrustedBroadcasts;

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
    private static final String CLASS_A2DP_NATIVE_INTERFACE =
            "com.android.bluetooth.a2dp.A2dpNativeInterface";
    private static final String CLASS_ADAPTER_SERVICE =
            "com.android.bluetooth.btservice.AdapterService";
    private static final String CLASS_BLUETOOTH_QUALITY_REPORT =
            "android.bluetooth.BluetoothQualityReport";
    private static final String CLASS_BT_UTILS = "com.android.bluetooth.Utils";
    private static final String CLASS_OPLUS_SMART_AUDIO =
            "com.oplus.bluetooth.feature.smartaudio.OplusBluetoothSmartAudioInterface";
    private static final String MELODY_PKG = "com.oplus.melody";
    private static final long GAME_MODE_SBC_FALLBACK_TTL_MS = 180_000L;
    private static final long LHDC_QUEUE_SAMPLE_INTERVAL_MS = 200L;
    private static final long LHDC_QUEUE_IDLE_INTERVAL_MS = 1_000L;
    private static final long BQR_DIAGNOSTIC_INTERVAL_MS = 60_000L;
    private static final int LHDC_QUEUE_CAPACITY = 45;
    private static final long[] NATIVE_PATCH_RETRY_DELAYS_MS = {
            0L, 350L, 1_500L, 5_000L, 12_000L
    };
    private static final Pattern MAC_PATTERN =
            Pattern.compile("(?i)([0-9A-F]{2}:){5}[0-9A-F]{2}");

    private final MelodyCodecLspEntry module;
    private final ClassLoader classLoader;
    private final String sourceDir;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private CodecBridgeService bridgeService;
    private CodecBroadcastBridge codecBroadcastBridge;
    private BluetoothLeAudioBridge leAudioBridge;
    private Context appContext;
    private boolean serviceManagerAttempted;
    private boolean nativePatchTerminal;
    private boolean nativePatchRunning;
    private boolean nativePatchScheduled;
    private int nativePatchAttempts;
    private Object smartAudioInterface;
    private Method smartAudioQueueLengthMethod;
    private boolean smartAudioQueueSampleScheduled;
    private int smartAudioQueueSampleFailures;
    private final LhdcLinkHealthController linkHealthController;
    private String activeLhdcMac;
    private String lastBqrDiagnosticState;
    private long lastBqrDiagnosticMs;

    public SystemHookInstaller(
            MelodyCodecLspEntry module, ClassLoader classLoader, String sourceDir) {
        this.module = module;
        this.classLoader = classLoader;
        this.sourceDir = sourceDir;
        this.linkHealthController = new LhdcLinkHealthController((mac, ceilingKbps, reason) -> {
            NativeLhdcMemoryPatch.setGovernorProbeCeilingKbps(ceilingKbps);
            MLog.event("lhdc.link.probe_ceiling",
                    "mac", redactMac(mac),
                    "ceilingKbps", ceilingKbps,
                    "reason", reason);
        });
    }

    public void install() {
        hookApplicationOnCreate();
        hookBluetoothQualityReports();
        Class<?> a2dpCls = resolveA2dpServiceClass();
        if (a2dpCls == null) {
            MLog.w("A2dpService not found in com.android.bluetooth (scope misconfigured?)");
            return;
        }
        hookCdmAssociationForMelody();
        hookConstructors(a2dpCls);
        hookLifecycle(a2dpCls);
        hookCodecConfigUpdated(a2dpCls);
        hookNativeCodecPreferenceLogger();
        hookSmartAudioQueueSampler();
        hookRemoteChoppyReport();
    }

    private Class<?> resolveA2dpServiceClass() {
        try {
            Class<?> cls = Class.forName(CLASS_A2DP_SERVICE, false, classLoader);
            MLog.event("bt.a2dp.resolved", "mode", "fqn", "class", cls.getName());
            return cls;
        } catch (Throwable ignored) {
        }
        for (Class<?> cls : scanBluetoothClasses()) {
            if (looksLikeA2dpService(cls)) {
                MLog.event("bt.a2dp.resolved", "mode", "scan", "class", cls.getName());
                return cls;
            }
        }
        return null;
    }

    private List<Class<?>> scanBluetoothClasses() {
        List<Class<?>> out = new ArrayList<>();
        DexFile dex = null;
        try {
            if (sourceDir == null || sourceDir.isEmpty()) return out;
            dex = new DexFile(sourceDir);
            Enumeration<String> entries = dex.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement();
                if (!name.startsWith("com.android.bluetooth.")) continue;
                if (!name.contains("a2dp") && !name.contains("A2dp")
                        && !name.endsWith(".Utils")) continue;
                try {
                    out.add(Class.forName(name, false, classLoader));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            MLog.w("Bluetooth dex scan failed", t);
        } finally {
            if (dex != null) {
                try {
                    dex.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    private static boolean looksLikeA2dpService(Class<?> cls) {
        if (cls == null) return false;
        return findMethod(cls, "getCodecStatus", BluetoothDevice.class) != null
                && hasSetCodecConfigPreference(cls);
    }

    private static boolean hasSetCodecConfigPreference(Class<?> cls) {
        for (Method m : cls.getMethods()) {
            if (!"setCodecConfigPreference".equals(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 2
                    && p[0] == BluetoothDevice.class
                    && "android.bluetooth.BluetoothCodecConfig".equals(p[1].getName())) {
                return true;
            }
        }
        return false;
    }

    private Class<?> resolveA2dpNativeInterfaceClass() {
        try {
            Class<?> cls = Class.forName(CLASS_A2DP_NATIVE_INTERFACE, false, classLoader);
            MLog.event("bt.a2dp.native.resolved", "mode", "fqn", "class", cls.getName());
            return cls;
        } catch (Throwable ignored) {
        }
        for (Class<?> cls : scanBluetoothClasses()) {
            if (looksLikeA2dpNativeInterface(cls)) {
                MLog.event("bt.a2dp.native.resolved", "mode", "scan", "class", cls.getName());
                return cls;
            }
        }
        return null;
    }

    private static boolean looksLikeA2dpNativeInterface(Class<?> cls) {
        if (cls == null) return false;
        for (Method m : cls.getDeclaredMethods()) {
            if (isNativeCodecPreferenceMethod(m)) return true;
        }
        return false;
    }

    private void hookApplicationOnCreate() {
        try {
            Method onCreate = Application.class.getMethod("onCreate");
            module.hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                Object app = chain.getThisObject();
                if (app instanceof Context) {
                    appContext = ((Context) app).getApplicationContext();
                    NativeLhdcMemoryPatch.configureModuleContext(appContext);
                    NativeLhdcMemoryPatch.installGovernor();
                    MLog.setDiagnosticContext(appContext, "bluetooth");
                    MLog.event("scope.system.context.ready");
                    ensureLeAudioBridge(appContext);
                    ensureCodecBroadcastBridge(appContext, bridgeService);
                    scheduleNativeLhdcMemoryPatch("application.onCreate");
                }
                return result;
            });
        } catch (Throwable t) {
            MLog.w("hook bluetooth Application.onCreate for LE Audio failed", t);
        }
    }

    private void hookCdmAssociationForMelody() {
        List<Class<?>> utilsClasses = resolveCdmUtilityClasses();
        if (utilsClasses.isEmpty()) {
            MLog.w("Bluetooth Utils class not found; CDM bypass unavailable");
            return;
        }
        int hooked = 0;
        for (Class<?> utilsCls : utilsClasses) {
            for (Method m : utilsCls.getDeclaredMethods()) {
                if (!isCdmEnforcementMethod(m.getName())) continue;
                module.hook(m).intercept(chain -> {
                    Object[] args = chain.getArgs().toArray();
                    if (isMelodyA2dpCodecCall(args)) {
                        MLog.event("cdm.bypass",
                                "class", utilsCls.getName(), "method", m.getName());
                        return bypassReturnValue(m.getReturnType());
                    }
                    return chain.proceed();
                });
                hooked++;
            }
        }
        MLog.event("cdm.hooks", "count", hooked);
    }

    private List<Class<?>> resolveCdmUtilityClasses() {
        List<Class<?>> out = new ArrayList<>();
        try {
            out.add(Class.forName(CLASS_BT_UTILS, false, classLoader));
        } catch (Throwable ignored) {
        }
        for (Class<?> cls : scanBluetoothClasses()) {
            boolean hasCdm = false;
            for (Method m : cls.getDeclaredMethods()) {
                if (isCdmEnforcementMethod(m.getName())) {
                    hasCdm = true;
                    break;
                }
            }
            if (hasCdm && !out.contains(cls)) {
                out.add(cls);
            }
        }
        return out;
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
        Context context = a2dpService instanceof Context
                ? (Context) a2dpService
                : currentApplication();
        scheduleNativeLhdcMemoryPatch("a2dp.service");
        if (context != null) {
            NativeLhdcMemoryPatch.configureModuleContext(context);
            ensureLeAudioBridge(context);
        }
        if (a2dpService == null) return;
        try {
            if (bridgeService == null) {
                bridgeService = new CodecBridgeService(a2dpService);
            }
            if (context != null) {
                ensureCodecBroadcastBridge(context, bridgeService);
            }
            if (!serviceManagerAttempted) {
                serviceManagerAttempted = true;
                try {
                    bridgeService.registerToServiceManager();
                    MLog.event("system.bridge.registered");
                } catch (Throwable t) {
                    MLog.w("system bridge service-manager register failed; broadcast bridge kept");
                }
            }
        } catch (Throwable t) {
            MLog.w("ensureBridgeRegistered failed", t);
            bridgeService = null;
            codecBroadcastBridge = null;
        }
    }

    private synchronized void ensureLeAudioBridge(Context context) {
        if (leAudioBridge != null || context == null) return;
        try {
            leAudioBridge = new BluetoothLeAudioBridge(context);
            leAudioBridge.register();
        } catch (Throwable t) {
            leAudioBridge = null;
            MLog.w("ensureLeAudioBridge failed", t);
        }
    }

    private synchronized void ensureCodecBroadcastBridge(
            Context context, CodecBridgeService service) {
        if (codecBroadcastBridge != null || context == null || service == null) return;
        try {
            codecBroadcastBridge = new CodecBroadcastBridge(context, service);
            codecBroadcastBridge.register();
        } catch (Throwable t) {
            codecBroadcastBridge = null;
            MLog.w("ensureCodecBroadcastBridge failed", t);
        }
    }

    private void hookCodecConfigUpdated(Class<?> a2dpCls) {
        int hooked = 0;
        for (Method m : a2dpCls.getDeclaredMethods()) {
            if (!isCodecConfigUpdatedMethod(m)) continue;
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
            hooked++;
        }
        MLog.event("codec.updated.hooks", "count", hooked);
    }

    private void hookNativeCodecPreferenceLogger() {
        Class<?> nativeCls = resolveA2dpNativeInterfaceClass();
        if (nativeCls == null) {
            MLog.w("A2dpNativeInterface not found; native codec preference logging unavailable");
            return;
        }
        int hooked = 0;
        for (Method m : nativeCls.getDeclaredMethods()) {
            if (!isNativeCodecPreferenceMethod(m)) continue;
            try {
                m.setAccessible(true);
            } catch (Throwable ignored) {
            }
            module.hook(m).intercept(chain -> {
                Object[] args = chain.getArgs().toArray();
                runNativeLhdcMemoryPatchNow("native.setCodecConfigPreference");
                MLog.event("bt.native.setCodecConfigPreference",
                        "device", args.length > 0 ? describeDevice(args[0]) : "?",
                        "configs", args.length > 1 ? describeCodecConfigArray(args[1]) : "[]");
                maybeBroadcastGameModeSbcHint(args);
                try {
                    Object result = chain.proceed();
                    MLog.event("bt.native.setCodecConfigPreference.done",
                            "device", args.length > 0 ? describeDevice(args[0]) : "?",
                            "configs", args.length > 1 ? describeCodecConfigArray(args[1]) : "[]");
                    return result;
                } catch (Throwable t) {
                    MLog.w("bt.native.setCodecConfigPreference failed", t);
                    throw t;
                }
            });
            hooked++;
        }
        MLog.event("bt.native.codec.hooks", "count", hooked, "class", nativeCls.getName());
    }

    private void hookBluetoothQualityReports() {
        try {
            Class<?> adapterClass = Class.forName(CLASS_ADAPTER_SERVICE, false, classLoader);
            Class<?> reportClass = Class.forName(
                    CLASS_BLUETOOTH_QUALITY_REPORT, false, classLoader);
            BqrAccessors accessors = BqrAccessors.resolve(reportClass);
            Method activeDevices = findMethod(adapterClass, "getActiveDevices", int.class);
            if (activeDevices != null) activeDevices.setAccessible(true);
            int hooked = 0;
            for (Method method : adapterClass.getDeclaredMethods()) {
                if (!"bluetoothQualityReportReadyCallback".equals(method.getName())) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 2
                        || params[0] != BluetoothDevice.class
                        || !CLASS_BLUETOOTH_QUALITY_REPORT.equals(params[1].getName())) {
                    continue;
                }
                method.setAccessible(true);
                Method finalActiveDevices = activeDevices;
                module.hook(method).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        Object[] args = chain.getArgs().toArray();
                        captureBluetoothQualityReport(
                                chain.getThisObject(), args, accessors, finalActiveDevices);
                    } catch (Throwable t) {
                        MLog.w("BQR callback extraction failed", t);
                    }
                    return result;
                });
                hooked++;
            }
            MLog.event("lhdc.link.bqr_hooks", "count", hooked,
                    "class", adapterClass.getName());
        } catch (Throwable t) {
            MLog.w("BQR hook unavailable; local LHDC governor remains active", t);
        }
    }

    private void captureBluetoothQualityReport(
            Object adapterService,
            Object[] args,
            BqrAccessors accessors,
            Method activeDevicesMethod) throws Exception {
        if (args == null || args.length < 2 || !(args[0] instanceof BluetoothDevice)) return;
        BluetoothDevice device = (BluetoothDevice) args[0];
        Object report = args[1];
        if (report == null || accessors.intValue(accessors.qualityReportId, report) != 1) return;
        Object common = accessors.bqrCommon.invoke(report);
        if (common == null) return;
        String mac = macFromDeviceArg(device);
        if (mac == null) return;
        LhdcLinkHealthController.BqrSample sample = new LhdcLinkHealthController.BqrSample(
                accessors.intValue(accessors.unusedAfhChannels, common),
                accessors.intValue(accessors.unidealAfhChannels, common),
                accessors.longValue(accessors.retransmissionCount, common),
                accessors.longValue(accessors.noRxCount, common),
                accessors.longValue(accessors.nakCount, common),
                accessors.intValue(accessors.rssi, common),
                accessors.intValue(accessors.snr, common),
                accessors.longValue(accessors.overflowCount, common),
                accessors.longValue(accessors.underflowCount, common));
        long capturedAtMs = SystemClock.elapsedRealtime();
        mainHandler.post(() -> handleBluetoothQualityReport(
                adapterService, device, mac, sample, capturedAtMs, activeDevicesMethod));
    }

    private void handleBluetoothQualityReport(
            Object adapterService,
            BluetoothDevice device,
            String mac,
            LhdcLinkHealthController.BqrSample sample,
            long capturedAtMs,
            Method activeDevicesMethod) {
        if (!isActiveA2dpDevice(adapterService, device, mac, activeDevicesMethod)) {
            MLog.event("lhdc.link.bqr_ignored", "mac", redactMac(mac), "reason", "not_active");
            return;
        }
        activeLhdcMac = mac;
        linkHealthController.activate(mac, capturedAtMs);
        boolean streaming = NativeLhdcMemoryPatch.isGovernorStreaming();
        linkHealthController.onBqrSample(mac, sample, capturedAtMs, streaming);
        LhdcLinkHealthController.Snapshot snapshot =
                linkHealthController.snapshot(mac, capturedAtMs);
        Object[] telemetry = {
                "mac", redactMac(mac),
                "unusedAfh", sample.unusedAfhChannels,
                "usableAfh", snapshot.usableAfhChannels,
                "unidealAfh", sample.unidealAfhChannels,
                "retransmissions", sample.retransmissionCount,
                "retransmissionsPerSec", rateText(snapshot.retransmissionsPerSecond),
                "noRx", sample.noRxCount,
                "noRxPerSec", rateText(snapshot.noRxPerSecond),
                "nak", sample.nakCount,
                "rssi", sample.rssi,
                "snr", sample.snr,
                "overflow", sample.overflowCount,
                "underflow", sample.underflowCount,
                "streaming", streaming,
                "healthyWindows", snapshot.healthyBqrWindows,
                "ceilingKbps", snapshot.ceilingKbps,
                "lock500to900", snapshot.boundary500To900Locked,
                "lock900to1000", snapshot.boundary900To1000Locked,
                "requiredHealthyWindows", snapshot.requiredHealthyBqrWindows,
                "requiredQuietMs", snapshot.requiredQuietMs
        };
        MLog.eventLogOnly("lhdc.link.bqr", telemetry);
        String diagnosticState = mac + '|' + streaming + '|'
                + snapshot.healthyBqrWindows + '|' + snapshot.ceilingKbps + '|'
                + snapshot.boundary500To900Locked + '|'
                + snapshot.boundary900To1000Locked + '|'
                + snapshot.requiredHealthyBqrWindows + '|' + snapshot.requiredQuietMs;
        if (!diagnosticState.equals(lastBqrDiagnosticState)
                || capturedAtMs - lastBqrDiagnosticMs >= BQR_DIAGNOSTIC_INTERVAL_MS) {
            lastBqrDiagnosticState = diagnosticState;
            lastBqrDiagnosticMs = capturedAtMs;
            MLog.event("lhdc.link.bqr_summary", telemetry);
        }
    }

    private boolean isActiveA2dpDevice(
            Object adapterService,
            BluetoothDevice device,
            String mac,
            Method activeDevicesMethod) {
        if (adapterService != null && activeDevicesMethod != null) {
            try {
                Object value = activeDevicesMethod.invoke(adapterService, 2);
                if (value instanceof List) {
                    for (Object active : (List<?>) value) {
                        if (device.equals(active) || mac.equals(macFromDeviceArg(active))) return true;
                    }
                    return false;
                }
            } catch (Throwable t) {
                MLog.w("BQR active A2DP filter failed; using current-device fallback", t);
            }
        }
        return activeLhdcMac == null || mac.equals(activeLhdcMac);
    }

    private static String rateText(double value) {
        return Double.isNaN(value) ? "?" : String.format(Locale.ROOT, "%.1f", value);
    }

    private void hookRemoteChoppyReport() {
        try {
            Class<?> cls = Class.forName(CLASS_OPLUS_SMART_AUDIO, false, classLoader);
            int hooked = 0;
            for (Method method : cls.getDeclaredMethods()) {
                if (!"onRemoteChoppyReport".equals(method.getName())) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 2 || params[0] != int.class || params[1] != byte[].class) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                } catch (Throwable ignored) {
                }
                module.hook(method).intercept(chain -> {
                    Object[] args = chain.getArgs().toArray();
                    int level = args.length > 0 && args[0] instanceof Integer
                            ? (Integer) args[0] : 0;
                    NativeLhdcMemoryPatch.reportRemoteChoppy(level);
                    if (level > 0) {
                        long nowMs = SystemClock.elapsedRealtime();
                        mainHandler.post(() -> {
                            String mac = activeLhdcMac;
                            if (mac != null) linkHealthController.onCongestion(mac, nowMs);
                        });
                    }
                    return chain.proceed();
                });
                hooked++;
            }
            MLog.event("lhdc.governor.choppy_hooks", "count", hooked);
        } catch (Throwable t) {
            MLog.w("LHDC remote choppy hook unavailable", t);
        }
    }

    private void hookSmartAudioQueueSampler() {
        try {
            Class<?> cls = Class.forName(CLASS_OPLUS_SMART_AUDIO, false, classLoader);
            Method queueLength = cls.getDeclaredMethod("getAudioQueueLengthNative");
            queueLength.setAccessible(true);
            int hooked = 0;
            for (Method method : cls.getDeclaredMethods()) {
                if (!"getInstance".equals(method.getName())
                        || method.getParameterTypes().length != 1
                        || method.getReturnType() != cls) {
                    continue;
                }
                method.setAccessible(true);
                module.hook(method).intercept(chain -> {
                    Object result = chain.proceed();
                    captureSmartAudioInterface(result, queueLength, "getInstance");
                    return result;
                });
                hooked++;
            }
            for (Method method : cls.getDeclaredMethods()) {
                if (!"init".equals(method.getName())
                        || method.getParameterTypes().length != 0) {
                    continue;
                }
                method.setAccessible(true);
                module.hook(method).intercept(chain -> {
                    captureSmartAudioInterface(chain.getThisObject(), queueLength, "init");
                    return chain.proceed();
                });
                hooked++;
            }
            MLog.event("lhdc.governor.queue_hooks", "count", hooked);
        } catch (Throwable t) {
            MLog.w("LHDC queue sampler hook unavailable", t);
        }
    }

    private void captureSmartAudioInterface(Object instance, Method queueLength, String source) {
        if (instance == null || queueLength == null) return;
        boolean changed;
        synchronized (this) {
            changed = smartAudioInterface != instance;
            smartAudioInterface = instance;
            smartAudioQueueLengthMethod = queueLength;
            if (!smartAudioQueueSampleScheduled) {
                smartAudioQueueSampleScheduled = true;
                mainHandler.postDelayed(this::sampleLhdcQueue,
                        LHDC_QUEUE_SAMPLE_INTERVAL_MS);
            }
        }
        if (changed) MLog.event("lhdc.governor.queue_source", "source", source);
    }

    private void sampleLhdcQueue() {
        Object instance;
        Method method;
        synchronized (this) {
            smartAudioQueueSampleScheduled = false;
            instance = smartAudioInterface;
            method = smartAudioQueueLengthMethod;
        }
        long nextDelay = LHDC_QUEUE_IDLE_INTERVAL_MS;
        if (instance != null && method != null && NativeLhdcMemoryPatch.shouldSampleQueue()) {
            nextDelay = LHDC_QUEUE_SAMPLE_INTERVAL_MS;
            try {
                Object value = method.invoke(instance);
                if (value instanceof Integer) {
                    int length = (Integer) value;
                    NativeLhdcMemoryPatch.reportQueueLength(length);
                    long nowMs = SystemClock.elapsedRealtime();
                    String mac = activeLhdcMac;
                    NativeLhdcMemoryPatch.GovernorEvent event =
                            NativeLhdcMemoryPatch.consumeGovernorEvent();
                    if (mac != null) {
                        if (NativeLhdcMemoryPatch.isGovernorStreaming()) {
                            linkHealthController.onQueueSample(
                                    mac, length, LHDC_QUEUE_CAPACITY, nowMs);
                        }
                        if (event != null) {
                            linkHealthController.onGovernorEvent(
                                    mac, event.type, event.fromKbps, event.toKbps, nowMs);
                            LhdcLinkHealthController.Snapshot snapshot =
                                    linkHealthController.snapshot(mac, nowMs);
                            MLog.event("lhdc.link.governor_event",
                                    "mac", redactMac(mac),
                                    "type", event.type,
                                    "fromKbps", event.fromKbps,
                                    "toKbps", event.toKbps,
                                    "ceilingKbps", snapshot.ceilingKbps,
                                    "requiredHealthyWindows",
                                    snapshot.requiredHealthyBqrWindows,
                                    "requiredQuietMs", snapshot.requiredQuietMs);
                        }
                    }
                    smartAudioQueueSampleFailures = 0;
                }
            } catch (Throwable t) {
                if (++smartAudioQueueSampleFailures <= 3) {
                    MLog.w("LHDC queue sample invocation failed", t);
                }
            }
        }
        synchronized (this) {
            if (!smartAudioQueueSampleScheduled && smartAudioInterface != null) {
                smartAudioQueueSampleScheduled = true;
                mainHandler.postDelayed(this::sampleLhdcQueue, nextDelay);
            }
        }
    }

    private synchronized void scheduleNativeLhdcMemoryPatch(String reason) {
        if (nativePatchTerminal || nativePatchScheduled) return;
        if (nativePatchAttempts >= NATIVE_PATCH_RETRY_DELAYS_MS.length) {
            nativePatchTerminal = true;
            MLog.event("lhdc.memory_patch.give_up", "attempts", nativePatchAttempts);
            return;
        }
        int attempt = nativePatchAttempts++;
        long delay = NATIVE_PATCH_RETRY_DELAYS_MS[attempt];
        nativePatchScheduled = true;
        mainHandler.postDelayed(() -> {
            synchronized (SystemHookInstaller.this) {
                nativePatchScheduled = false;
            }
            runNativeLhdcMemoryPatch("retry:" + reason, true);
        }, delay);
    }

    private void runNativeLhdcMemoryPatchNow(String reason) {
        runNativeLhdcMemoryPatch(reason, false, true);
    }

    private void runNativeLhdcMemoryPatch(String reason, boolean allowRetry) {
        runNativeLhdcMemoryPatch(reason, allowRetry, false);
    }

    private void runNativeLhdcMemoryPatch(
            String reason,
            boolean allowRetry,
            boolean forceIfPending) {
        NativeLhdcMemoryPatch.PatchResult cachedResult = null;
        synchronized (this) {
            if (nativePatchRunning) return;
            NativeLhdcMemoryPatch.PatchResult previous = NativeLhdcMemoryPatch.lastResult();
            if (nativePatchTerminal && !shouldRetryTerminalNativePatch(previous, forceIfPending)) {
                cachedResult = previous;
            } else {
                nativePatchRunning = true;
            }
        }
        if (cachedResult != null) {
            sendNativePatchState(cachedResult);
            return;
        }
        synchronized (this) {
            if (!nativePatchRunning) {
                // Terminal retry was declined with no cached result to replay.
                return;
            }
        }

        NativeLhdcMemoryPatch.PatchResult result = NativeLhdcMemoryPatch.apply();
        try {
            MLog.event("lhdc.memory_patch",
                    "status", result.status,
                    "reason", reason,
                    "detail", result.reason,
                    "addr", result.addressHex(),
                    "patched", result.patchedCount,
                    "original", result.originalCount,
                    "success", result.success);
            sendNativePatchState(result);
        } finally {
            synchronized (this) {
                nativePatchRunning = false;
                nativePatchTerminal = result.terminal;
            }
        }
        if (!result.terminal && allowRetry) {
            scheduleNativeLhdcMemoryPatch(reason);
        }
    }

    private static boolean shouldRetryTerminalNativePatch(
            NativeLhdcMemoryPatch.PatchResult previous,
            boolean forceIfPending) {
        if (!forceIfPending) return false;
        if (previous == null) return true;
        return !previous.terminal || "pending".equals(previous.status);
    }

    private void sendNativePatchState(NativeLhdcMemoryPatch.PatchResult result) {
        Context context = appContext;
        if (context == null || result == null) return;
        Intent intent = new Intent(CodecIpc.ACTION_NATIVE_PATCH_STATE);
        intent.setPackage(CodecIpc.MELODY_PKG);
        intent.putExtra(CodecIpc.EXTRA_TOKEN, CodecIpc.TOKEN);
        intent.putExtra(CodecIpc.EXTRA_NATIVE_PATCH_STATUS, result.status);
        intent.putExtra(CodecIpc.EXTRA_NATIVE_PATCH_DETAIL, result.reason);
        intent.putExtra(CodecIpc.EXTRA_NATIVE_PATCH_PATCHED, result.patchedCount);
        intent.putExtra(CodecIpc.EXTRA_NATIVE_PATCH_ORIGINAL, result.originalCount);
        intent.putExtra(CodecIpc.EXTRA_NATIVE_PATCH_SUCCESS, result.success);
        try {
            if (!TrustedBroadcasts.send(context, intent)) {
                MLog.w("native patch state broadcast was not delivered");
            }
        } catch (Throwable t) {
            MLog.w("native patch state broadcast failed", t);
        }
    }

    private static boolean isNativeCodecPreferenceMethod(Method m) {
        if (m == null || !"setCodecConfigPreference".equals(m.getName())) return false;
        Class<?>[] p = m.getParameterTypes();
        return p.length == 2
                && p[0] == BluetoothDevice.class
                && p[1].isArray()
                && "android.bluetooth.BluetoothCodecConfig".equals(
                p[1].getComponentType().getName());
    }

    private static boolean isCodecConfigUpdatedMethod(Method m) {
        if (m == null) return false;
        if ("codecConfigUpdated".equals(m.getName())) return true;
        boolean hasDevice = false;
        boolean hasStatus = false;
        for (Class<?> p : m.getParameterTypes()) {
            if (p == BluetoothDevice.class) hasDevice = true;
            if ("android.bluetooth.BluetoothCodecStatus".equals(p.getName())) hasStatus = true;
        }
        return hasDevice && hasStatus;
    }

    private void maybeBroadcastGameModeSbcHint(Object[] args) {
        if (args == null || args.length < 2) return;
        long marker = findGameModeSbcMarker(args[1]);
        if (marker <= 0L) return;
        String mac = macFromDeviceArg(args[0]);
        if (mac == null) return;
        Context context = appContext != null ? appContext : currentApplication();
        if (context == null) return;
        Intent intent = new Intent(CodecIpc.ACTION_GAME_MODE_STATE);
        intent.setPackage(CodecIpc.MELODY_PKG);
        intent.putExtra(CodecIpc.EXTRA_TOKEN, CodecIpc.TOKEN);
        intent.putExtra(CodecIpc.EXTRA_MAC, mac);
        intent.putExtra(CodecIpc.EXTRA_GAME_MODE_ACTIVE, true);
        intent.putExtra(CodecIpc.EXTRA_GAME_MODE_TYPE, -1);
        intent.putExtra(CodecIpc.EXTRA_GAME_MODE_SOURCE, "bt.native.sbc_s2_" + marker);
        intent.putExtra(CodecIpc.EXTRA_GAME_MODE_TTL_MS, GAME_MODE_SBC_FALLBACK_TTL_MS);
        try {
            if (!TrustedBroadcasts.send(context, intent)) {
                MLog.w("game mode SBC hint broadcast was not delivered");
                return;
            }
            MLog.event("game.mode.bt_hint",
                    "mac", redactMac(mac),
                    "s2", marker,
                    "ttlMs", GAME_MODE_SBC_FALLBACK_TTL_MS);
        } catch (Throwable t) {
            MLog.w("game mode SBC hint broadcast failed", t);
        }
    }

    private static long findGameModeSbcMarker(Object configs) {
        if (configs == null || !configs.getClass().isArray()) return 0L;
        int length = Array.getLength(configs);
        for (int i = 0; i < length; i++) {
            long marker = gameModeSbcMarker(Array.get(configs, i));
            if (marker > 0L) return marker;
        }
        return 0L;
    }

    private static long gameModeSbcMarker(Object config) {
        if (config == null) return 0L;
        if (readInt(config, "getCodecType") != 0) return 0L;
        long marker = readLong(config, "getCodecSpecific2");
        return marker > 0L ? marker : 0L;
    }

    private static String macFromDeviceArg(Object device) {
        if (device instanceof BluetoothDevice) {
            try {
                return normalizeMac(((BluetoothDevice) device).getAddress());
            } catch (Throwable ignored) {
                return null;
            }
        }
        return normalizeMac(String.valueOf(device));
    }

    private static String normalizeMac(String value) {
        if (value == null) return null;
        Matcher matcher = MAC_PATTERN.matcher(value);
        if (!matcher.find()) return null;
        return matcher.group().toUpperCase(Locale.ROOT);
    }

    private static String redactMac(String mac) {
        String key = normalizeMac(mac);
        if (key == null || key.length() < 17) return "?";
        return key.substring(0, 2) + "**" + key.substring(15);
    }

    private static String describeDevice(Object device) {
        if (device instanceof BluetoothDevice) {
            try {
                return ((BluetoothDevice) device).getAddress();
            } catch (Throwable ignored) {
            }
        }
        return String.valueOf(device);
    }

    private static String describeCodecConfigArray(Object configs) {
        if (configs == null || !configs.getClass().isArray()) return "[]";
        int length = Array.getLength(configs);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(describeCodecConfig(Array.get(configs, i)));
        }
        return sb.append(']').toString();
    }

    private static String describeCodecConfig(Object config) {
        if (config == null) return "null";
        return "{codec=0x" + Integer.toHexString(readInt(config, "getCodecType"))
                + " rate=0x" + Integer.toHexString(readInt(config, "getSampleRate"))
                + " bits=0x" + Integer.toHexString(readInt(config, "getBitsPerSample"))
                + " channel=0x" + Integer.toHexString(readInt(config, "getChannelMode"))
                + " s1=" + readLong(config, "getCodecSpecific1")
                + " s2=" + readLong(config, "getCodecSpecific2")
                + " s3=" + readLong(config, "getCodecSpecific3")
                + " s4=" + readLong(config, "getCodecSpecific4")
                + '}';
    }

    private static int readInt(Object target, String methodName) {
        Object value = invokeNoArg(target, methodName);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static long readLong(Object target, String methodName) {
        Object value = invokeNoArg(target, methodName);
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static Object invokeNoArg(Object target, String methodName) {
        try {
            Method m = target.getClass().getMethod(methodName);
            return m.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final class BqrAccessors {
        final Method qualityReportId;
        final Method bqrCommon;
        final Method unusedAfhChannels;
        final Method unidealAfhChannels;
        final Method retransmissionCount;
        final Method noRxCount;
        final Method nakCount;
        final Method rssi;
        final Method snr;
        final Method overflowCount;
        final Method underflowCount;

        private BqrAccessors(
                Method qualityReportId,
                Method bqrCommon,
                Method unusedAfhChannels,
                Method unidealAfhChannels,
                Method retransmissionCount,
                Method noRxCount,
                Method nakCount,
                Method rssi,
                Method snr,
                Method overflowCount,
                Method underflowCount) {
            this.qualityReportId = qualityReportId;
            this.bqrCommon = bqrCommon;
            this.unusedAfhChannels = unusedAfhChannels;
            this.unidealAfhChannels = unidealAfhChannels;
            this.retransmissionCount = retransmissionCount;
            this.noRxCount = noRxCount;
            this.nakCount = nakCount;
            this.rssi = rssi;
            this.snr = snr;
            this.overflowCount = overflowCount;
            this.underflowCount = underflowCount;
        }

        static BqrAccessors resolve(Class<?> reportClass) throws Exception {
            Method bqrCommon = requiredNoArg(reportClass, "getBqrCommon");
            Class<?> commonClass = bqrCommon.getReturnType();
            return new BqrAccessors(
                    requiredNoArg(reportClass, "getQualityReportId"),
                    bqrCommon,
                    requiredNoArg(commonClass, "getUnusedAfhChannelCount"),
                    requiredNoArg(commonClass, "getAfhSelectUnidealChannelCount"),
                    requiredNoArg(commonClass, "getRetransmissionCount"),
                    requiredNoArg(commonClass, "getNoRxCount"),
                    requiredNoArg(commonClass, "getNakCount"),
                    requiredNoArg(commonClass, "getRssi"),
                    requiredNoArg(commonClass, "getSnr"),
                    requiredNoArg(commonClass, "getOverflowCount"),
                    requiredNoArg(commonClass, "getUnderflowCount"));
        }

        int intValue(Method method, Object target) throws Exception {
            return ((Number) method.invoke(target)).intValue();
        }

        long longValue(Method method, Object target) throws Exception {
            return ((Number) method.invoke(target)).longValue();
        }

        private static Method requiredNoArg(Class<?> cls, String name) throws Exception {
            Method method = findMethod(cls, name);
            if (method == null) throw new NoSuchMethodException(cls.getName() + '#' + name);
            method.setAccessible(true);
            return method;
        }
    }

    private static Method findMethod(Class<?> startCls, String name, Class<?>... params) {
        Class<?> cls = startCls;
        while (cls != null && cls != Object.class) {
            try {
                return cls.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
                cls = cls.getSuperclass();
            }
        }
        return null;
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
