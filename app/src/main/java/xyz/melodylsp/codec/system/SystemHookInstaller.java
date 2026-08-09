package xyz.melodylsp.codec.system;

import android.app.Application;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONObject;

import dalvik.system.DexFile;

import xyz.melodylsp.codec.MelodyCodecLspEntry;
import xyz.melodylsp.codec.BuildConfig;
import xyz.melodylsp.codec.bridge.CodecIpc;
import xyz.melodylsp.codec.bridge.CodecSnapshot;
import xyz.melodylsp.codec.bridge.LhdcQualityPolicy;
import xyz.melodylsp.codec.diag.DiagnosticEvents;
import xyz.melodylsp.codec.label.CodecLabelTable;
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
    private static final String ACTION_A2DP_CONNECTION_STATE_CHANGED =
            "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED";
    private static final long GAME_MODE_SBC_FALLBACK_TTL_MS = 180_000L;
    private static final long LHDC_QUEUE_SAMPLE_INTERVAL_MS = 200L;
    private static final long LHDC_QUEUE_IDLE_INTERVAL_MS = 1_000L;
    /**
     * Phase N-2 choppy 1s dedup (decision 33/6.8.2): the host double-delivers the same
     * physical glitch within ~2 ms; one physical perturbation is one audible glitch, so
     * duplicate reports inside this window are logged and dropped at the Java convergence
     * point.
     */
    private static final long CHOPPY_DEDUP_WINDOW_MS = 1_000L;
    private static final long BQR_DIAGNOSTIC_INTERVAL_MS = 60_000L;
    private static final long BQR_LIVE_SUBSCRIPTION_TTL_MS = 12_000L;
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
    private volatile Object a2dpServiceInstance;
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
    private volatile String activeLhdcMac;
    private String lastBqrDiagnosticState;
    private long lastBqrDiagnosticMs;
    private BroadcastReceiver lhdcDiagnosticLiveControlReceiver;
    private BroadcastReceiver lhdcSessionReceiver;
    private long lhdcDiagnosticLiveUntilMs;
    private String lastLhdcDiagnosticLivePayload;
    private final Object lhdcDiagnosticReasonLock = new Object();
    private final Map<String, Integer> lhdcDiagnosticReasonCounts = new HashMap<>();
    private final Map<String, Long> lhdcDiagnosticReasonTimes = new HashMap<>();
    /** Per-MAC confirmed peer max-bitrate capability (900/1000 kbps), surviving process ordering. */
    private final Map<String, Integer> peerCeilingByMac = new HashMap<>();
    /**
     * Phase N-2: last accepted choppy report per MAC for the 1 s dedup window. The choppy
     * hook runs on binder threads, so the map must be safe for concurrent access
     * (review P2-5).
     */
    private final Map<String, Long> lastRemoteChoppyEventMsByMac = new ConcurrentHashMap<>();
    private final AtomicLong remoteChoppySequence = new AtomicLong();

    public SystemHookInstaller(
            MelodyCodecLspEntry module, ClassLoader classLoader, String sourceDir) {
        this.module = module;
        this.classLoader = classLoader;
        this.sourceDir = sourceDir;
        this.linkHealthController = new LhdcLinkHealthController(
                new LhdcLinkHealthController.Listener() {
                    @Override
                    public void onProbeCeilingChanged(
                            String mac, int ceilingKbps, String reason) {
                        handleProbeCeilingChanged(mac, ceilingKbps, reason);
                    }

                    @Override
                    public void onProbeStateChanged(
                            String mac, int ceilingKbps, String reason) {
                        handleProbeStateChanged(mac, ceilingKbps, reason);
                    }

                    @Override
                    public void onBqrShadowCandidate(
                            String mac,
                            int fromKbps,
                            int candidateKbps,
                            long overflowCount,
                            long underflowCount,
                            int candidateCount,
                            long streamSessionId) {
                        handleBqrShadowCandidate(
                                mac,
                                fromKbps,
                                candidateKbps,
                                overflowCount,
                                underflowCount,
                                candidateCount,
                                streamSessionId);
                    }

                    @Override
                    public void onBqrFallbackStateChanged(
                            String mac,
                            int capKbps,
                            String reason,
                            int badWindows,
                            int healthyWindows,
                            double retransmissionsPerSecond,
                            double noRxPerSecond,
                            int escalationLevel,
                            long holdMs) {
                        handleBqrFallbackStateChanged(
                                mac,
                                capKbps,
                                reason,
                                badWindows,
                                healthyWindows,
                                retransmissionsPerSecond,
                                noRxPerSecond,
                                escalationLevel,
                                holdMs);
                    }

                    @Override
                    public void onBqrWindowSkipped(
                            String mac,
                            String reason,
                            double retransmissionsPerSecond,
                            double noRxPerSecond,
                            long nowMs) {
                        MLog.event("lhdc.link.bqr_window_skipped",
                                "mac", redactMac(mac),
                                "reason", reason,
                                "retxPerSec", rateText(retransmissionsPerSecond),
                                "noRxPerSec", rateText(noRxPerSecond),
                                "nowMs", nowMs);
                    }

                    @Override
                    public void onShadowTrigger(
                            String mac,
                            String kind,
                            int fromKbps,
                            int toKbps,
                            long nowMs,
                            double retransmissionsPerSecond,
                            double noRxPerSecond,
                            int queueLength,
                            long queueHighAccumMs,
                            int choppyCount5s,
                            String snapshot) {
                        // Phase N-3 shadow sentinels: calibration evidence only, never applied.
                        MLog.event("lhdc.link.shadow_trigger",
                                "mac", redactMac(mac),
                                "kind", kind,
                                "fromKbps", fromKbps,
                                "toKbps", toKbps,
                                "retxPerSec", rateText(retransmissionsPerSecond),
                                "noRxPerSec", rateText(noRxPerSecond),
                                "queue", queueLength,
                                "queueHighAccumMs", queueHighAccumMs,
                                "choppyCount5s", choppyCount5s,
                                "snapshot", snapshot);
                    }
                });
    }

    public void install() {
        MLog.event("lhdc.link.stage_d",
                "mode", "shadow_only",
                "requiredWindows", LhdcLinkHealthController.REQUIRED_SHADOW_UNSTABLE_WINDOWS,
                "cooldownMs", LhdcLinkHealthController.SHADOW_CANDIDATE_COOLDOWN_MS,
                "rateMutation", false);
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
                    registerLhdcDiagnosticLiveControlReceiver(appContext);
                    registerLhdcSessionReceiver(appContext);
                    NativeLhdcMemoryPatch.configureModuleContext(appContext);
                    NativeLhdcMemoryPatch.installGovernor();
                    // Experimental governor switch: mirror the module preference so the
                    // runtime flag matches the UI (default OFF in production builds).
                    boolean governorEnabled = readGovernorExperimentalEnabled(appContext);
                    linkHealthController.setGovernorEnabled(
                            governorEnabled, SystemClock.elapsedRealtime());
                    MLog.eventLogOnly(
                            "lhdc.governor.experimental", "enabled", governorEnabled);
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

    private synchronized void registerLhdcDiagnosticLiveControlReceiver(Context context) {
        if (context == null || lhdcDiagnosticLiveControlReceiver != null) return;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                if (intent == null
                        || !CodecIpc.ACTION_LHDC_DIAGNOSTIC_LIVE_CONTROL.equals(
                        intent.getAction())
                        || !CodecIpc.TOKEN.equals(
                        intent.getStringExtra(CodecIpc.EXTRA_TOKEN))) {
                    return;
                }
                if (TrustedBroadcasts.supportsSenderIdentity()
                        && !TrustedBroadcasts.isTrustedSender(
                        receiverContext,
                        TrustedBroadcasts.captureSender(this),
                        BuildConfig.APPLICATION_ID)) {
                    return;
                }
                // hasExtra: a governor-only broadcast must not disturb the live sample
                // subscription state (review: otherwise toggling the switch killed it).
                if (intent.hasExtra(CodecIpc.EXTRA_DIAGNOSTIC_LIVE_ENABLED)) {
                    boolean enabled = intent.getBooleanExtra(
                            CodecIpc.EXTRA_DIAGNOSTIC_LIVE_ENABLED, false);
                    boolean wasActive = SystemClock.elapsedRealtime()
                            < lhdcDiagnosticLiveUntilMs;
                    lhdcDiagnosticLiveUntilMs = enabled
                            ? SystemClock.elapsedRealtime() + BQR_LIVE_SUBSCRIPTION_TTL_MS
                            : 0L;
                    if (enabled != wasActive) {
                        MLog.eventLogOnly("lhdc.link.live_control", "enabled", enabled);
                    }
                    if (enabled && !wasActive && lastLhdcDiagnosticLivePayload != null) {
                        sendLhdcDiagnosticLivePayload(lastLhdcDiagnosticLivePayload);
                    }
                }
                if (intent.hasExtra(CodecIpc.EXTRA_GOVERNOR_EXPERIMENTAL_ENABLED)) {
                    boolean govEnabled = intent.getBooleanExtra(
                            CodecIpc.EXTRA_GOVERNOR_EXPERIMENTAL_ENABLED, false);
                    persistGovernorExperimentalEnabled(appContext, govEnabled);
                    linkHealthController.setGovernorEnabled(
                            govEnabled, SystemClock.elapsedRealtime());
                    MLog.eventLogOnly("lhdc.governor.experimental", "enabled", govEnabled);
                }
            }
        };
        IntentFilter filter = new IntentFilter(
                CodecIpc.ACTION_LHDC_DIAGNOSTIC_LIVE_CONTROL);
        if (TrustedBroadcasts.registerExportedReceiver(
                context,
                receiver,
                filter,
                BuildConfig.APPLICATION_ID + ".permission.DIAGNOSTIC_REQUEST",
                mainHandler)) {
            lhdcDiagnosticLiveControlReceiver = receiver;
        } else {
            MLog.w("LHDC foreground diagnostic control receiver unavailable");
        }
    }

    /**
     * Local (bluetooth-process) prefs survive process restarts even when the module process
     * is not running — {@code getRemotePreferences} needs the module process alive and
     * silently returns defaults when it is not (feedback 215917: the switch did not survive
     * a bluetooth restart). The module prefs remain the UI's source of truth; the local
     * mirror is refreshed on every broadcast.
     */
    private static final String GOVERNOR_LOCAL_PREFS = "melody_codec_governor";

    private boolean readGovernorExperimentalEnabled(Context context) {
        SharedPreferences local = context.getSharedPreferences(
                GOVERNOR_LOCAL_PREFS, Context.MODE_PRIVATE);
        if (local.contains(CodecIpc.KEY_GOVERNOR_EXPERIMENTAL_ENABLED)) {
            return local.getBoolean(CodecIpc.KEY_GOVERNOR_EXPERIMENTAL_ENABLED, false);
        }
        // First boot since install: fall back to the module process pipe (may be offline).
        try {
            return module.getRemotePreferences(CodecIpc.PREFS_MODULE)
                    .getBoolean(CodecIpc.KEY_GOVERNOR_EXPERIMENTAL_ENABLED, false);
        } catch (Throwable t) {
            MLog.w("governor experimental prefs unavailable; default off", t);
            return false;
        }
    }

    private static void persistGovernorExperimentalEnabled(Context context, boolean enabled) {
        try {
            context.getSharedPreferences(GOVERNOR_LOCAL_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(CodecIpc.KEY_GOVERNOR_EXPERIMENTAL_ENABLED, enabled)
                    .apply();
        } catch (Throwable t) {
            MLog.w("governor experimental prefs persist failed", t);
        }
    }

    private synchronized void registerLhdcSessionReceiver(Context context) {
        if (context == null || lhdcSessionReceiver != null) return;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                if (intent == null) return;
                String action = intent.getAction();
                boolean disconnected = BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)
                        || (ACTION_A2DP_CONNECTION_STATE_CHANGED.equals(action)
                        && intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                        == BluetoothProfile.STATE_DISCONNECTED);
                if (!disconnected) return;
                BluetoothDevice device;
                try {
                    device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                } catch (Throwable ignored) {
                    return;
                }
                if (device == null) return;
                resetLhdcLinkState(
                        device.getAddress(),
                        BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)
                                ? "acl_disconnected" : "a2dp_disconnected");
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_A2DP_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(
                        receiver, filter, null, mainHandler, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter, null, mainHandler);
            }
            lhdcSessionReceiver = receiver;
            MLog.event("lhdc.link.session_receiver.registered");
        } catch (Throwable t) {
            MLog.w("LHDC session reset receiver unavailable", t);
        }
    }

    private void handleProbeCeilingChanged(String mac, int ceilingKbps, String reason) {
        int requestId = NativeLhdcMemoryPatch.setGovernorProbeCeilingKbps(ceilingKbps);
        // Phase N-1 requestId transaction: register the issued Target_Cap so native
        // confirmations with the same requestId close the switch and timeouts fall back
        // to the getter. requestId == 0 means nothing was written (governor unavailable or the
        // same rung already applied): no transaction, no phantom timeout. When the queue/event
        // loop is not sampling (ADAPTIVE policy), no confirmation can ever arrive, so the
        // transaction is not registered either (Phase N-1 review P1-4).
        if (requestId > 0 && NativeLhdcMemoryPatch.shouldSampleQueue()) {
            linkHealthController.onTargetCapIssued(
                    mac, NativeLhdcMemoryPatch.currentDesiredGovernorProbeCeilingKbps(),
                    requestId, SystemClock.elapsedRealtime());
        }
        MLog.event("lhdc.link.probe_ceiling",
                "mac", redactMac(mac),
                "ceilingKbps", ceilingKbps,
                "reason", reason);
        sendLhdcDiagnosticLiveState(
                mac,
                linkHealthController.snapshot(mac, SystemClock.elapsedRealtime()),
                reason,
                "reason");
    }

    private void handleProbeStateChanged(String mac, int ceilingKbps, String reason) {
        MLog.event("lhdc.link.probe_state",
                "mac", redactMac(mac),
                "ceilingKbps", ceilingKbps,
                "reason", reason);
        sendLhdcDiagnosticLiveState(
                mac,
                linkHealthController.snapshot(mac, SystemClock.elapsedRealtime()),
                reason,
                "reason");
    }

    /** Stage-D shadow result: persist evidence and update diagnostics, but never touch ceilings. */
    private void handleBqrShadowCandidate(
            String mac,
            int fromKbps,
            int candidateKbps,
            long overflowCount,
            long underflowCount,
            int candidateCount,
            long streamSessionId) {
        LhdcLinkHealthController.Snapshot snapshot =
                linkHealthController.snapshot(mac, SystemClock.elapsedRealtime());
        MLog.event("lhdc.link.bqr_shadow_candidate",
                "mac", redactMac(mac),
                "wouldProtect", candidateKbps > 0,
                "fromKbps", fromKbps,
                "candidateKbps", candidateKbps > 0 ? candidateKbps : "observe_only",
                "overflow", overflowCount,
                "underflow", underflowCount,
                "candidateCount", candidateCount,
                "streamSessionId", streamSessionId,
                "actualBitrateKbps", NativeLhdcMemoryPatch.currentGovernorBitrateKbps(),
                "requestedBitrateKbps",
                NativeLhdcMemoryPatch.currentGovernorRequestedBitrateKbps(),
                "bitrateVerification", NativeLhdcMemoryPatch.currentGovernorVerification(),
                "ceilingUnchangedKbps", snapshot.ceilingKbps);
        sendLhdcDiagnosticLiveState(mac, snapshot, "bqr_shadow_candidate", "shadow");
    }

    private void handleBqrFallbackStateChanged(
            String mac,
            int capKbps,
            String reason,
            int badWindows,
            int healthyWindows,
            double retransmissionsPerSecond,
            double noRxPerSecond,
            int escalationLevel,
            long holdMs) {
        MLog.event("lhdc.link.bqr_fallback",
                "mac", redactMac(mac),
                "capKbps", capKbps,
                "reason", reason,
                "badWindows", badWindows,
                "healthyWindows", healthyWindows,
                "escalationLevel", escalationLevel,
                "holdMs", holdMs,
                "retxPerSec", rateText(retransmissionsPerSecond),
                "noRxPerSec", rateText(noRxPerSecond),
                "actualBitrateKbps", NativeLhdcMemoryPatch.currentGovernorBitrateKbps(),
                "requestedBitrateKbps",
                NativeLhdcMemoryPatch.currentGovernorRequestedBitrateKbps(),
                "bitrateVerification", NativeLhdcMemoryPatch.currentGovernorVerification(),
                "pid", android.os.Process.myPid());
    }

    private void sendLhdcDiagnosticLiveState(
            String mac,
            LhdcLinkHealthController.Snapshot snapshot,
            String reason,
            String kind) {
        if (snapshot == null) return;
        try {
            JSONObject payload = new JSONObject();
            long capturedAtMs = System.currentTimeMillis();
            if (reason != null && !reason.isEmpty()) {
                noteLhdcDiagnosticReason(reason, capturedAtMs);
            }
            payload.put("time", capturedAtMs);
            payload.put("kind", kind == null ? "state" : kind);
            payload.put("mac", redactMac(mac));
            payload.put("streaming", NativeLhdcMemoryPatch.isGovernorStreaming());
            payload.put("actualBitrateKbps",
                    NativeLhdcMemoryPatch.currentGovernorBitrateKbps());
            payload.put("requestedBitrateKbps",
                    NativeLhdcMemoryPatch.currentGovernorRequestedBitrateKbps());
            payload.put("bitrateVerification",
                    NativeLhdcMemoryPatch.currentGovernorVerification());
            payload.put("retransmissionsPerSec",
                    finiteOrNull(snapshot.retransmissionsPerSecond));
            payload.put("noRxPerSec", finiteOrNull(snapshot.noRxPerSecond));
            payload.put("unusedAfh", Math.max(0, 79 - snapshot.usableAfhChannels));
            payload.put("usableAfh", snapshot.usableAfhChannels);
            payload.put("healthyWindows", snapshot.healthyBqrWindows);
            payload.put("ceilingKbps", snapshot.ceilingKbps);
            payload.put("effectiveCeilingKbps", snapshot.ceilingKbps);
            payload.put("peerCeilingKbps", snapshot.peerCeilingKbps);
            payload.put("boundary900To1000Supported",
                    snapshot.boundary900To1000Supported);
            payload.put("lock500to900", snapshot.boundary500To900Locked);
            payload.put("lock900to1000", snapshot.boundary900To1000Locked);
            payload.put("requiredHealthyWindows", snapshot.requiredHealthyBqrWindows);
            payload.put("requiredQuietMs", snapshot.requiredQuietMs);
            payload.put("queue", snapshot.currentQueueLength);
            payload.put("queueCapacity", snapshot.queueCapacity);
            payload.put("lowQueueDurationMs", snapshot.lowQueueDurationMs);
            payload.put("lastCongestionAgoMs", snapshot.lastCongestionAgoMs);
            payload.put("probePhase", snapshot.probePhase);
            payload.put("probeElapsedMs", snapshot.probeElapsedMs);
            payload.put("probeBadWindows", snapshot.probeBadBqrWindows);
            payload.put("recoveryWaitRemainingMs", snapshot.recoveryWaitRemainingMs);
            payload.put("nativeBackoffRemainingMs", snapshot.nativeBackoffRemainingMs);
            payload.put("blockedReason", snapshot.blockedReason);
            payload.put("streamSessionId", snapshot.streamSessionId);
            payload.put("lastRemoteChoppyLevel", snapshot.lastRemoteChoppyLevel);
            payload.put("lastRemoteChoppyAgoMs", snapshot.lastRemoteChoppyAgoMs);
            payload.put("remoteChoppyCount5s", snapshot.remoteChoppyCount5s);
            payload.put("choppyCapabilityState", snapshot.choppyCapabilityState);
            payload.put("overflow", snapshot.lastBqrOverflowCount);
            payload.put("underflow", snapshot.lastBqrUnderflowCount);
            payload.put("shadowUnstableWindows", snapshot.shadowUnstableWindows);
            payload.put("shadowCandidateCount", snapshot.shadowCandidateCount);
            payload.put("bqrFallbackCapKbps", snapshot.bqrFallbackCapKbps);
            payload.put("leakyFallbackCapKbps", snapshot.leakyFallbackCapKbps);
            payload.put("bqrFallbackHealthyWindows", snapshot.bqrFallbackHealthyWindows);
            payload.put("bqrFallbackRequiredHealthyWindows",
                    snapshot.bqrFallbackRequiredHealthyWindows);
            payload.put("bqrFallbackHoldRemainingMs", snapshot.bqrFallbackHoldRemainingMs);
            payload.put("leakyFallbackHealthyWindows", snapshot.leakyFallbackHealthyWindows);
            payload.put("leakyFallbackRequiredHealthyWindows",
                    snapshot.leakyFallbackRequiredHealthyWindows);
            payload.put("leakyFallbackHoldRemainingMs", snapshot.leakyFallbackHoldRemainingMs);
            payload.put("lastShadowCandidateKbps", snapshot.lastShadowCandidateKbps);
            payload.put("lastShadowCandidateAgoMs", snapshot.lastShadowCandidateAgoMs);
            payload.put("shadowStreamSessionId", snapshot.shadowStreamSessionId);
            appendLhdcDiagnosticReasonHistory(payload);
            if (reason != null && !reason.isEmpty()) payload.put("reason", reason);
            publishLhdcDiagnosticLivePayload(payload.toString());
        } catch (Throwable t) {
            MLog.w("LHDC live diagnostic payload failed", t);
        }
    }

    private void publishLhdcDiagnosticLivePayload(String payload) {
        if (payload == null || payload.isEmpty()) return;
        lastLhdcDiagnosticLivePayload = payload;
        sendLhdcDiagnosticLivePayload(payload);
    }

    private void sendLhdcDiagnosticLivePayload(String payload) {
        Context context = appContext;
        if (context == null
                || payload == null
                || SystemClock.elapsedRealtime() >= lhdcDiagnosticLiveUntilMs) {
            return;
        }
        Intent intent = new Intent(CodecIpc.ACTION_LHDC_DIAGNOSTIC_LIVE_SAMPLE);
        intent.setPackage(BuildConfig.APPLICATION_ID);
        intent.putExtra(CodecIpc.EXTRA_TOKEN, CodecIpc.TOKEN);
        intent.putExtra(CodecIpc.EXTRA_DIAGNOSTIC_LIVE_PAYLOAD, payload);
        TrustedBroadcasts.send(context, intent);
    }

    private static Object finiteOrNull(double value) {
        return Double.isNaN(value) || Double.isInfinite(value)
                ? JSONObject.NULL : value;
    }

    private void noteLhdcDiagnosticReason(String reason, long capturedAtMs) {
        synchronized (lhdcDiagnosticReasonLock) {
            lhdcDiagnosticReasonCounts.put(reason,
                    lhdcDiagnosticReasonCounts.getOrDefault(reason, 0) + 1);
            lhdcDiagnosticReasonTimes.put(reason, capturedAtMs);
        }
    }

    /** Maps a native transition reason id to the diagnostics key {@code native_<name>_<from>_<to>}. */
    private static String nativeTransitionReason(NativeLhdcMemoryPatch.GovernorEvent event) {
        String name;
        switch (event.reasonId) {
            case 1: name = "quality_start"; break;
            case 2: name = "quality_start_retry"; break;
            case 3: name = "probe_ceiling"; break;
            case 4: name = "probe_ceiling_restore"; break;
            case 5: name = "remote_choppy"; break;
            case 6: name = "queue_full"; break;
            case 7: name = "queue_critical"; break;
            case 8: name = "stable_upgrade"; break;
            default: name = "unknown";
        }
        return "native_" + name + "_" + event.fromKbps + "_" + event.toKbps;
    }

    private void appendLhdcDiagnosticReasonHistory(JSONObject payload) throws Exception {
        JSONObject counts = new JSONObject();
        JSONObject times = new JSONObject();
        synchronized (lhdcDiagnosticReasonLock) {
            for (Map.Entry<String, Integer> entry : lhdcDiagnosticReasonCounts.entrySet()) {
                counts.put(entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, Long> entry : lhdcDiagnosticReasonTimes.entrySet()) {
                times.put(entry.getKey(), entry.getValue());
            }
        }
        payload.put("reasonCounts", counts);
        payload.put("reasonTimes", times);
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
        a2dpServiceInstance = a2dpService;
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
            codecBroadcastBridge = new CodecBroadcastBridge(
                    context, service, this::handleGovernorPolicyChanged);
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
                        CodecSnapshot snapshot =
                                bridge.notifyCodecChanged(chain.getArgs().toArray());
                        if (snapshot != null) {
                            mainHandler.post(() -> {
                                try {
                                    handleCodecSnapshotForGovernor(snapshot);
                                } catch (Throwable t) {
                                    MLog.w("LHDC codec capability sync failed", t);
                                }
                            });
                        }
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

    private void handleGovernorPolicyChanged(
            String mac, int policy, String reason, int ceilingKbps) {
        String normalizedMac = normalizeMac(mac);
        if (normalizedMac == null) return;
        mainHandler.post(() -> {
            if (ceilingKbps > 0) {
                rememberPeerCeiling(normalizedMac, ceilingKbps, "policy_broadcast");
            }
            if (LhdcQualityPolicy.normalize(policy) != LhdcQualityPolicy.QUALITY) {
                resetLhdcLinkState(normalizedMac,
                        policy == LhdcQualityPolicy.CONNECTION
                                ? "policy_connection" : "policy_adaptive");
            }
        });
    }

    private void handleCodecSnapshotForGovernor(CodecSnapshot snapshot) {
        if (snapshot == null) return;
        String mac = normalizeMac(snapshot.mac);
        if (mac == null) return;
        if (CodecLabelTable.isLhdc(snapshot.activeCodecType)) {
            // A stack-confirmed LHDC config is the strongest capability signal. Always cache it
            // per MAC; the native ceiling is only rewritten when this MAC is (or becomes) active.
            syncPeerCeilingFromSnapshot(mac, snapshot, "codec_confirmed");
            return;
        }
        if (mac.equals(activeLhdcMac) || mac.equals(linkHealthController.activeMac())) {
            resetLhdcLinkState(mac, "codec_exit");
        }
    }

    /**
     * Mirrors the stack-confirmed transport low byte into the native governor probe ceiling
     * and the link-health controller so a 900 kbps peer never gets probed to 1000.
     */
    private void syncPeerCeilingFromSnapshot(
            String mac, CodecSnapshot snapshot, String reason) {
        if (mac == null || snapshot == null) return;
        int ceilingKbps = peerCeilingFromSnapshot(snapshot);
        if (ceilingKbps <= 0) return;
        rememberPeerCeiling(mac, ceilingKbps, reason);
    }

    /**
     * Persists a confirmed peer ceiling per MAC, syncs the per-MAC link-health state, and applies
     * the ceiling to the global native governor only when this MAC is the active LHDC device.
     * Non-active snapshots only update the capability cache.
     */
    private void rememberPeerCeiling(String mac, int ceilingKbps, String reason) {
        String key = normalizeMac(mac);
        if (key == null || ceilingKbps <= 0) return;
        int normalizedCeiling = NativeLhdcMemoryPatch.normalizeProbeCeilingKbps(ceilingKbps);
        Integer previous = peerCeilingByMac.put(key, normalizedCeiling);
        linkHealthController.setPeerCeilingKbps(
                key, normalizedCeiling, SystemClock.elapsedRealtime(), reason);
        boolean nativeApplied = key.equals(activeLhdcMac)
                || key.equals(linkHealthController.activeMac());
        if (nativeApplied) {
            // Bypass write: capability sync must not advance the transaction id of an
            // in-flight controller decision (Phase N-1 decision 33).
            NativeLhdcMemoryPatch.setGovernorProbeCeilingKbpsQuiet(normalizedCeiling);
        }
        MLog.event("lhdc.link.peer_ceiling_sync",
                "mac", redactMac(key),
                "ceilingKbps", normalizedCeiling,
                "reason", reason,
                "nativeApplied", nativeApplied,
                "changed", previous == null || previous != normalizedCeiling);
    }

    /**
     * Restores the active MAC's known peer ceiling before the controller activates, so the first
     * {@code device_active} publish is already 900 and never flashes 1000. Falls back to querying
     * the current stack-confirmed codec config through the bridge when no cache entry exists.
     */
    private boolean restorePeerCeilingForActive(String mac) {
        String key = normalizeMac(mac);
        if (key == null) return false;
        Integer cached = peerCeilingByMac.get(key);
        if (cached != null && cached > 0) {
            linkHealthController.setPeerCeilingKbps(
                    key, cached, SystemClock.elapsedRealtime(), "bqr_activate");
            NativeLhdcMemoryPatch.setGovernorProbeCeilingKbpsQuiet(cached);
            MLog.event("lhdc.link.peer_ceiling_restored",
                    "mac", redactMac(key),
                    "ceilingKbps", cached,
                    "source", "per_mac_cache");
            return true;
        }
        CodecBridgeService bridge = bridgeService;
        if (bridge == null) return false;
        try {
            CodecSnapshot snapshot = bridge.getStatusUnchecked(key);
            if (snapshot != null && CodecLabelTable.isLhdc(snapshot.activeCodecType)) {
                int ceilingKbps = peerCeilingFromSnapshot(snapshot);
                if (ceilingKbps > 0) {
                    rememberPeerCeiling(key, ceilingKbps, "bqr_activate_query");
                    return true;
                }
            }
        } catch (Throwable t) {
            MLog.w("LHDC BQR activate ceiling query failed", t);
        }
        return false;
    }

    private static int peerCeilingFromSnapshot(CodecSnapshot snapshot) {
        if (snapshot == null) return 0;
        long lowByte = snapshot.activeCodecSpecific1 & 0xFFL;
        if (lowByte == CodecLabelTable.LHDC_QUALITY_FIXED_900) return 900;
        if (lowByte == CodecLabelTable.LHDC_QUALITY_FIXED_1000) return 1000;
        return 0;
    }

    private void resetLhdcLinkState(String mac, String reason) {
        String key = normalizeMac(mac);
        if (key == null) return;
        boolean nativeSessionActive = key.equals(activeLhdcMac);
        boolean controllerWasActive = linkHealthController.resetDevice(
                key, SystemClock.elapsedRealtime(), reason);
        if (nativeSessionActive) activeLhdcMac = null;
        if (nativeSessionActive && !controllerWasActive) {
            NativeLhdcMemoryPatch.setGovernorProbeCeilingKbpsQuiet(1000);
        }
        if (nativeSessionActive || controllerWasActive) {
            MLog.event("lhdc.link.session_reset",
                    "mac", redactMac(key),
                    "reason", reason,
                    "ceilingKbps", 1000);
        }
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
        if (!mac.equals(activeLhdcMac)) {
            activeLhdcMac = mac;
            // Restore the confirmed peer ceiling before activation so the first publish is
            // already 900 (or the queried stack config) instead of a 1000 flash.
            if (!restorePeerCeilingForActive(mac)) {
                // Do not leak a previous headset's 900 ceiling into an unknown new device.
                NativeLhdcMemoryPatch.setGovernorProbeCeilingKbpsQuiet(1000);
                MLog.event("lhdc.link.peer_ceiling_restored",
                        "mac", redactMac(mac),
                        "ceilingKbps", 1000,
                        "source", "unknown_default");
            }
            linkHealthController.activate(mac, capturedAtMs);
        }
        boolean streaming = NativeLhdcMemoryPatch.isGovernorStreaming();
        linkHealthController.onBqrSample(mac, sample, capturedAtMs, streaming);
        LhdcLinkHealthController.Snapshot snapshot =
                linkHealthController.snapshot(mac, capturedAtMs);
        int actualBitrateKbps = NativeLhdcMemoryPatch.currentGovernorBitrateKbps();
        int requestedBitrateKbps =
                NativeLhdcMemoryPatch.currentGovernorRequestedBitrateKbps();
        String bitrateVerification = NativeLhdcMemoryPatch.currentGovernorVerification();
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
                "actualBitrateKbps", actualBitrateKbps,
                "requestedBitrateKbps", requestedBitrateKbps,
                "bitrateVerification", bitrateVerification,
                "healthyWindows", snapshot.healthyBqrWindows,
                "ceilingKbps", snapshot.ceilingKbps,
                "effectiveCeilingKbps", snapshot.ceilingKbps,
                "peerCeilingKbps", snapshot.peerCeilingKbps,
                "boundary900To1000Supported", snapshot.boundary900To1000Supported,
                "lock500to900", snapshot.boundary500To900Locked,
                "lock900to1000", snapshot.boundary900To1000Locked,
                "requiredHealthyWindows", snapshot.requiredHealthyBqrWindows,
                "requiredQuietMs", snapshot.requiredQuietMs,
                "queue", snapshot.currentQueueLength,
                "queueCapacity", snapshot.queueCapacity,
                "lowQueueDurationMs", snapshot.lowQueueDurationMs,
                "lastCongestionAgoMs", snapshot.lastCongestionAgoMs,
                "probePhase", snapshot.probePhase,
                "probeElapsedMs", snapshot.probeElapsedMs,
                "probeBadWindows", snapshot.probeBadBqrWindows,
                "recoveryWaitRemainingMs", snapshot.recoveryWaitRemainingMs,
                "nativeBackoffRemainingMs", snapshot.nativeBackoffRemainingMs,
                "blockedReason", snapshot.blockedReason,
                "streamSessionId", snapshot.streamSessionId,
                "lastRemoteChoppyLevel", snapshot.lastRemoteChoppyLevel,
                "remoteChoppyCount5s", snapshot.remoteChoppyCount5s,
                "choppyCapabilityState", snapshot.choppyCapabilityState,
                "shadowUnstableWindows", snapshot.shadowUnstableWindows,
                "shadowCandidateCount", snapshot.shadowCandidateCount,
                "lastShadowCandidateKbps", snapshot.lastShadowCandidateKbps,
                "lastShadowCandidateAgoMs", snapshot.lastShadowCandidateAgoMs,
                "shadowStreamSessionId", snapshot.shadowStreamSessionId
        };
        MLog.eventLogOnly("lhdc.link.bqr", telemetry);
        sendLhdcDiagnosticLiveState(mac, snapshot, null, "bqr");
        String diagnosticState = mac + '|' + streaming + '|'
                + snapshot.healthyBqrWindows + '|' + snapshot.ceilingKbps + '|'
                + snapshot.boundary500To900Locked + '|'
                + snapshot.boundary900To1000Locked + '|'
                + snapshot.requiredHealthyBqrWindows + '|' + snapshot.requiredQuietMs + '|'
                + snapshot.probePhase + '|' + snapshot.blockedReason + '|'
                + snapshot.probeBadBqrWindows + '|' + snapshot.streamSessionId + '|'
                + snapshot.peerCeilingKbps + '|' + sample.overflowCount + '|'
                + sample.underflowCount + '|' + snapshot.shadowUnstableWindows + '|'
                + snapshot.shadowCandidateCount + '|' + snapshot.choppyCapabilityState;
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
                    byte[] callbackPayload = args.length > 1 && args[1] instanceof byte[]
                            ? (byte[]) args[1] : null;
                    long nowMs = SystemClock.elapsedRealtime();
                    long sequence = remoteChoppySequence.incrementAndGet();
                    RemoteChoppyAttribution attribution =
                            resolveRemoteChoppyAttribution(callbackPayload);
                    if (level > 0) {
                        String dedupKey = attribution.reliable && attribution.mac != null
                                ? attribution.mac : "<unknown>";
                        Long lastAcceptedAt = lastRemoteChoppyEventMsByMac.get(dedupKey);
                        if (lastAcceptedAt != null
                                && nowMs - lastAcceptedAt < CHOPPY_DEDUP_WINDOW_MS) {
                            // Phase N-2 1s dedup: same physical event re-delivered; record the
                            // drop so the feedback package can audit double-delivery, but do
                            // not feed the decision path twice.
                            MLog.event("lhdc.link.choppy_dedup",
                                    "mac", redactMac(dedupKey),
                                    "level", level,
                                    "sequence", sequence,
                                    "sinceMs", nowMs - lastAcceptedAt);
                        } else {
                            lastRemoteChoppyEventMsByMac.put(dedupKey, nowMs);
                            NativeLhdcMemoryPatch.reportRemoteChoppy(level);
                            if (attribution.reliable && attribution.mac != null) {
                                mainHandler.post(() -> {
                                    linkHealthController.onRemoteChoppyReport(
                                            attribution.mac, level, nowMs);
                                    LhdcLinkHealthController.Snapshot snapshot =
                                            linkHealthController.snapshot(
                                                    attribution.mac,
                                                    SystemClock.elapsedRealtime());
                                    logRemoteChoppyEvent(
                                            level, sequence, callbackPayload, attribution,
                                            snapshot);
                                    sendLhdcDiagnosticLiveState(
                                            attribution.mac,
                                            snapshot,
                                            "remote_choppy",
                                            "choppy");
                                });
                            } else {
                                logRemoteChoppyEvent(
                                        level, sequence, callbackPayload, attribution, null);
                            }
                        }
                    } else {
                        LhdcLinkHealthController.Snapshot snapshot = attribution.mac != null
                                ? linkHealthController.snapshot(attribution.mac, nowMs) : null;
                        logRemoteChoppyEvent(
                                level, sequence, callbackPayload, attribution, snapshot);
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

    private RemoteChoppyAttribution resolveRemoteChoppyAttribution(byte[] callbackPayload) {
        Object service = a2dpServiceInstance;
        if (service != null) {
            try {
                Method method = findMethod(service.getClass(), "getActiveDevice");
                if (method != null) {
                    method.setAccessible(true);
                    String mac = macFromDeviceArg(method.invoke(service));
                    if (mac != null) {
                        return new RemoteChoppyAttribution(
                                mac, "active_device", true,
                                callbackAddressMatch(callbackPayload, mac));
                    }
                }
            } catch (Throwable ignored) {
                // Attribution falls through to the already-filtered controller active device.
            }
        }
        String fallback = normalizeMac(activeLhdcMac);
        if (fallback != null
                && fallback.equals(linkHealthController.activeMac())
                && NativeLhdcMemoryPatch.isGovernorStreaming()) {
            return new RemoteChoppyAttribution(
                    fallback, "controller_active", true,
                    callbackAddressMatch(callbackPayload, fallback));
        }
        return new RemoteChoppyAttribution(
                null, "unknown", false,
                callbackPayload != null && callbackPayload.length == 6
                        ? "unresolved_address_length" : "not_address_length");
    }

    private void logRemoteChoppyEvent(
            int level,
            long sequence,
            byte[] callbackPayload,
            RemoteChoppyAttribution attribution,
            LhdcLinkHealthController.Snapshot snapshot) {
        MLog.event("lhdc.link.remote_choppy",
                "mac", redactMac(attribution.mac),
                "attribution", attribution.source,
                "attributionReliable", attribution.reliable,
                "payloadLength", callbackPayload != null ? callbackPayload.length : -1,
                "payloadAddressMatch", attribution.payloadAddressMatch,
                "level", level,
                "sequence", sequence,
                "choppyCapabilityState", snapshot != null
                        ? snapshot.choppyCapabilityState
                        : LhdcLinkHealthController.CHOPPY_CAPABILITY_UNKNOWN,
                "actualBitrateKbps", NativeLhdcMemoryPatch.currentGovernorBitrateKbps(),
                "requestedBitrateKbps",
                NativeLhdcMemoryPatch.currentGovernorRequestedBitrateKbps(),
                "bitrateVerification", NativeLhdcMemoryPatch.currentGovernorVerification(),
                "queue", snapshot != null ? snapshot.currentQueueLength : -1,
                "queueCapacity", snapshot != null ? snapshot.queueCapacity : 0,
                "bqrAgeMs", snapshot != null ? snapshot.lastBqrAgoMs : -1L,
                "unusedAfh", snapshot != null
                        ? Math.max(0, 79 - snapshot.usableAfhChannels) : -1,
                "retxPerSec", snapshot != null
                        ? rateText(snapshot.retransmissionsPerSecond) : "?",
                "noRxPerSec", snapshot != null
                        ? rateText(snapshot.noRxPerSecond) : "?",
                "probePhase", snapshot != null ? snapshot.probePhase : "?",
                "streamSessionId", snapshot != null ? snapshot.streamSessionId : 0L);
    }

    private static String callbackAddressMatch(byte[] payload, String mac) {
        String normalized = normalizeMac(mac);
        if (payload == null || payload.length != 6 || normalized == null) {
            return "not_address_length";
        }
        String[] parts = normalized.split(":");
        if (parts.length != 6) return "invalid_mac";
        boolean forward = true;
        boolean reverse = true;
        for (int i = 0; i < 6; i++) {
            int expected;
            try {
                expected = Integer.parseInt(parts[i], 16);
            } catch (NumberFormatException ignored) {
                return "invalid_mac";
            }
            forward &= (payload[i] & 0xFF) == expected;
            reverse &= (payload[5 - i] & 0xFF) == expected;
        }
        if (forward) return "forward";
        if (reverse) return "reverse";
        return "none";
    }

    private static final class RemoteChoppyAttribution {
        final String mac;
        final String source;
        final boolean reliable;
        final String payloadAddressMatch;

        RemoteChoppyAttribution(
                String mac, String source, boolean reliable, String payloadAddressMatch) {
            this.mac = mac;
            this.source = source;
            this.reliable = reliable;
            this.payloadAddressMatch = payloadAddressMatch;
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
                    long streamSessionId = NativeLhdcMemoryPatch.currentGovernorSessionEpoch();
                    // Keep the single-slot native event pending until it can be bound to a known
                    // active MAC; consuming it with mac=null would permanently lose capability
                    // detection and upgrade evidence before the first BQR callback.
                    NativeLhdcMemoryPatch.GovernorEvent event = mac != null
                            ? NativeLhdcMemoryPatch.consumeGovernorEvent() : null;
                    if (mac != null) {
                        if (NativeLhdcMemoryPatch.isGovernorStreaming()) {
                            linkHealthController.onStreamSessionChanged(
                                    mac, streamSessionId, nowMs);
                        }
                        if (event != null
                                && event.type
                                == LhdcLinkHealthController.EVENT_PEER_CEILING_DETECTED) {
                            // Native getter proved the peer cannot sustain the target rung
                            // (e.g. actual 900 for target 1000). Bind it to the active MAC.
                            if (event.toKbps == 900 || event.toKbps == 1000) {
                                rememberPeerCeiling(mac, event.toKbps, "native_detected");
                            }
                        } else if (event != null) {
                            boolean current =
                                    NativeLhdcMemoryPatch.isGovernorEventCurrent(event);
                            if (!current) {
                                // Phase N-1 requestId transaction: the event belongs to a
                                // superseded Target_Cap write. Java only acts on the latest
                                // transaction; older results are logged and dropped.
                                MLog.event("lhdc.governor.event_stale",
                                        "mac", redactMac(mac),
                                        "type", event.type,
                                        "fromKbps", event.fromKbps,
                                        "toKbps", event.toKbps,
                                        "reasonId", event.reasonId,
                                        "requestId", event.requestId);
                            }
                            if (event.type
                                    == LhdcLinkHealthController.EVENT_TRANSITION_APPLIED) {
                                noteLhdcDiagnosticReason(
                                        nativeTransitionReason(event), nowMs);
                                if (current) {
                                    linkHealthController.onTransitionConfirmed(
                                            mac, event.toKbps, event.requestId, nowMs);
                                }
                            } else if (current) {
                                linkHealthController.onGovernorEvent(
                                        mac, event.type, event.fromKbps, event.toKbps,
                                        event.detailMs, nowMs);
                            }
                        }
                        if (NativeLhdcMemoryPatch.isGovernorStreaming()) {
                            linkHealthController.onQueueSample(
                                    mac, length, LHDC_QUEUE_CAPACITY, nowMs);
                            // Transactions can only be confirmed while the native sampler runs;
                            // when streaming is suspended no event can arrive, so defer the
                            // timeout check instead of reporting a getter=0 phantom timeout.
                            LhdcLinkHealthController.PendingTransaction switchTimedOut =
                                    linkHealthController.tickSwitchTransactions(mac, nowMs);
                            if (switchTimedOut != null) {
                                MLog.event("lhdc.governor.switch_timeout",
                                        "mac", redactMac(mac),
                                        "targetKbps", switchTimedOut.targetKbps,
                                        "requestId", switchTimedOut.requestId,
                                        "sinceMs", switchTimedOut.sinceMs,
                                        "actualBitrateKbps",
                                        NativeLhdcMemoryPatch.currentGovernorBitrateKbps(),
                                        "bitrateVerification",
                                        NativeLhdcMemoryPatch.currentGovernorVerification());
                            }
                        }
                        if (event != null) {
                            LhdcLinkHealthController.Snapshot snapshot =
                                    linkHealthController.snapshot(mac, nowMs);
                            String liveReason = null;
                            if (event.type
                                    == LhdcLinkHealthController.EVENT_PEER_CEILING_DETECTED) {
                                MLog.event("lhdc.link.peer_ceiling_detected",
                                        "mac", redactMac(mac),
                                        "actualKbps", event.toKbps,
                                        "peerCeilingKbps", snapshot.peerCeilingKbps,
                                        "ceilingKbps", snapshot.ceilingKbps,
                                        "probePhase", snapshot.probePhase);
                                liveReason = "peer_ceiling_detected";
                            } else if (event.type
                                    == LhdcLinkHealthController.EVENT_TRANSITION_APPLIED) {
                                MLog.event("lhdc.link.native_transition",
                                        "mac", redactMac(mac),
                                        "reason", nativeTransitionReason(event),
                                        "fromKbps", event.fromKbps,
                                        "toKbps", event.toKbps,
                                        "actualBitrateKbps",
                                        NativeLhdcMemoryPatch.currentGovernorBitrateKbps(),
                                        "requestedBitrateKbps",
                                        NativeLhdcMemoryPatch.currentGovernorRequestedBitrateKbps(),
                                        "bitrateVerification",
                                        NativeLhdcMemoryPatch.currentGovernorVerification());
                                liveReason = nativeTransitionReason(event);
                            } else {
                                MLog.event("lhdc.link.governor_event",
                                        "mac", redactMac(mac),
                                        "type", event.type,
                                        "fromKbps", event.fromKbps,
                                        "toKbps", event.toKbps,
                                        "detailMs", event.detailMs,
                                        "actualBitrateKbps",
                                        NativeLhdcMemoryPatch.currentGovernorBitrateKbps(),
                                        "requestedBitrateKbps",
                                        NativeLhdcMemoryPatch.currentGovernorRequestedBitrateKbps(),
                                        "bitrateVerification",
                                        NativeLhdcMemoryPatch.currentGovernorVerification(),
                                        "ceilingKbps", snapshot.ceilingKbps,
                                        "requiredHealthyWindows",
                                        snapshot.requiredHealthyBqrWindows,
                                        "requiredQuietMs", snapshot.requiredQuietMs,
                                        "queue", snapshot.currentQueueLength,
                                        "lowQueueDurationMs", snapshot.lowQueueDurationMs,
                                        "probePhase", snapshot.probePhase,
                                        "probeElapsedMs", snapshot.probeElapsedMs,
                                        "nativeBackoffRemainingMs",
                                        snapshot.nativeBackoffRemainingMs,
                                        "blockedReason", snapshot.blockedReason,
                                        "streamSessionId", snapshot.streamSessionId);
                                if (event.type
                                        == LhdcLinkHealthController.EVENT_UPGRADE_APPLIED) {
                                    liveReason = "native_upgrade_applied";
                                }
                            }
                            sendLhdcDiagnosticLiveState(
                                    mac, snapshot, liveReason, "governor");
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
        // Observe-only dynamic adapter probe: runs only while a diagnostic recording session is
        // active (and only in debug builds, see BuildConfig.LHDC_DYN_OBSERVE). Evidence lands in
        // the feedback package for future ROM-family research; never changes bitrate state.
        if (appContext != null
                && DiagnosticEvents.isRecording(appContext)
                && NativeLhdcMemoryPatch.isGovernorStreaming()) {
            NativeLhdcMemoryPatch.probeAdjustOwnerIfEnabled();
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
            NativeLhdcMemoryPatch.PatchResult previousQualitySwitch =
                    NativeLhdcMemoryPatch.lastQualitySwitchResult();
            if (nativePatchTerminal && !shouldRetryTerminalNativePatch(
                    previous, previousQualitySwitch, forceIfPending)) {
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
        NativeLhdcMemoryPatch.PatchResult qualitySwitchResult =
                NativeLhdcMemoryPatch.applyQualitySwitchGuard();
        try {
            MLog.event("lhdc.memory_patch",
                    "status", result.status,
                    "reason", reason,
                    "detail", result.reason,
                    "addr", result.addressHex(),
                    "patched", result.patchedCount,
                    "original", result.originalCount,
                    "success", result.success);
            MLog.event("lhdc.memory_patch.fast_switch",
                    "status", qualitySwitchResult.status,
                    "reason", reason,
                    "detail", qualitySwitchResult.reason,
                    "addr", qualitySwitchResult.addressHex(),
                    "patched", qualitySwitchResult.patchedCount,
                    "original", qualitySwitchResult.originalCount,
                    "success", qualitySwitchResult.success);
            sendNativePatchState(result);
        } finally {
            synchronized (this) {
                nativePatchRunning = false;
                nativePatchTerminal = result.terminal && qualitySwitchResult.terminal;
            }
        }
        if ((!result.terminal || !qualitySwitchResult.terminal) && allowRetry) {
            scheduleNativeLhdcMemoryPatch(reason);
        }
    }

    private static boolean shouldRetryTerminalNativePatch(
            NativeLhdcMemoryPatch.PatchResult previous,
            NativeLhdcMemoryPatch.PatchResult previousQualitySwitch,
            boolean forceIfPending) {
        if (!forceIfPending) return false;
        if (previous == null || previousQualitySwitch == null) return true;
        return !previous.terminal
                || "pending".equals(previous.status)
                || !previousQualitySwitch.terminal
                || "pending".equals(previousQualitySwitch.status);
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
