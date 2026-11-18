package xyz.melodylsp.codec.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import io.github.libxposed.api.XposedInterface;
import xyz.melodylsp.codec.BuildConfig;
import xyz.melodylsp.codec.MelodyCodecLspEntry;
import xyz.melodylsp.codec.diag.DiagnosticEvents;
import xyz.melodylsp.codec.diag.StatusProbe;

/**
 * Module-wide logging facade. Logs go to standard {@link Log} as well as the libxposed
 * framework log when an {@link XposedInterface} has been attached, so users can either tail
 * {@code logcat -s MelodyCodecLsp} or read the LSPosed module log viewer.
 *
 * <p>Output is tiered to keep the always-on cost low. Verbose mode (the diagnostic page's
 * debug-log switch, or any active feedback recording session) restores the full stream to both
 * sinks. Outside verbose mode:</p>
 * <ul>
 *   <li>warnings / errors and status-essential or sticky events reach logcat and the LSPosed
 *       module log;</li>
 *   <li>other low-frequency events reach logcat only (the LSPosed log is persisted to disk by
 *       the framework daemon and is the expensive sink);</li>
 *   <li>high-frequency telemetry ({@link #isDebugOnlyEventName}) and {@code d()} are
 *       dropped.</li>
 * </ul>
 * <p>Gating only affects text output: {@link StatusProbe} observation, sticky snapshots and
 * diagnostic-page persistence are unchanged, so the status page keeps working with the switch
 * off.</p>
 */
public final class MLog {

    public static final String TAG = "MelodyCodecLsp";

    /** Process-local mirror so the switch survives host / bluetooth process restarts. */
    private static final String LOCAL_LOG_PREFS = "melody_codec_log";

    static final int SINK_NONE = 0;
    static final int SINK_LOGCAT = 1;
    static final int SINK_ALL = 2;

    private static volatile boolean verboseLogging;
    /** Set once the local mirror or a control broadcast supplied the switch value. */
    private static volatile boolean verboseFromAuthoritativeSource;
    private static final List<Runnable> verboseListeners = new ArrayList<>();
    private static volatile XposedInterface xposed;
    private static volatile String hostVersion = "?";
    private static volatile Context diagnosticContext;
    private static volatile String diagnosticScope = "unknown";
    private static volatile long diagnosticRecordingUntilMs;
    private static volatile long diagnosticSnapshotPublishedUntilMs;
    private static volatile BroadcastReceiver diagnosticControlReceiver;
    private static final Object PENDING_LOCK = new Object();
    private static final List<PendingDiagnostic> pendingDiagnostics = new ArrayList<>();
    private static final int MAX_PENDING_DIAGNOSTICS = 128;
    private static final Map<String, PendingDiagnostic> stickyDiagnostics =
            new LinkedHashMap<>();
    private static final int MAX_STICKY_DIAGNOSTICS = 64;
    private static final Pattern BLUETOOTH_ADDRESS = Pattern.compile(
            "(?i)([0-9a-f]{2}):(?:[0-9a-f]{2}:){4}([0-9a-f]{2})");

    private MLog() {
    }

    public static void attach(XposedInterface iface) {
        xposed = iface;
    }

    public static void setDiagnosticContext(Context context, String scope) {
        Context appContext = null;
        if (context != null) {
            appContext = context.getApplicationContext();
            diagnosticContext = appContext;
        }
        if (scope != null && !scope.isEmpty()) {
            diagnosticScope = scope;
        }
        refreshDiagnosticRecordingFromRemotePreferences();
        loadLocalVerboseLogging(appContext);
        registerDiagnosticControlReceiver(appContext);
        if (isDiagnosticRecordingActive(System.currentTimeMillis())) {
            flushPendingDiagnostics(appContext, diagnosticScope);
            maybePublishDiagnosticSnapshot(appContext, diagnosticScope);
        } else {
            clearPendingDiagnostics();
        }
    }

    public static void configureDiagnosticRecordingUntil(long untilMs) {
        long previous = diagnosticRecordingUntilMs;
        diagnosticRecordingUntilMs = Math.max(0L, untilMs);
        if (!isDiagnosticRecordingActive(System.currentTimeMillis())) {
            diagnosticSnapshotPublishedUntilMs = 0L;
            clearPendingDiagnostics();
        }
        if (previous != diagnosticRecordingUntilMs) notifyVerboseListeners();
    }

    /** Applies the diagnostic page's debug-log switch in this process. */
    public static void configureVerboseLogging(boolean enabled) {
        boolean changed = verboseLogging != enabled;
        verboseLogging = enabled;
        if (changed) notifyVerboseListeners();
    }

    /** True when the debug-log switch is on or a feedback recording session is active. */
    public static boolean isVerboseActive() {
        return isVerbose(verboseLogging, diagnosticRecordingUntilMs, System.currentTimeMillis());
    }

    static boolean isVerbose(boolean switchOn, long recordingUntilMs, long nowMs) {
        return switchOn || isRecordingActive(recordingUntilMs, nowMs);
    }

    /**
     * Registers a callback fired when the verbose state may have changed (switch toggled or a
     * recording session started / stopped). Used to mirror the state into native code.
     */
    public static void addVerboseListener(Runnable listener) {
        if (listener == null) return;
        synchronized (verboseListeners) {
            verboseListeners.add(listener);
        }
    }

    private static void notifyVerboseListeners() {
        List<Runnable> copy;
        synchronized (verboseListeners) {
            if (verboseListeners.isEmpty()) return;
            copy = new ArrayList<>(verboseListeners);
        }
        for (Runnable listener : copy) {
            try {
                listener.run();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void loadLocalVerboseLogging(Context context) {
        if (context == null) return;
        try {
            SharedPreferences local =
                    context.getSharedPreferences(LOCAL_LOG_PREFS, Context.MODE_PRIVATE);
            if (local.contains(DiagnosticEvents.KEY_VERBOSE_LOGGING)) {
                verboseFromAuthoritativeSource = true;
                configureVerboseLogging(
                        local.getBoolean(DiagnosticEvents.KEY_VERBOSE_LOGGING, false));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void persistLocalVerboseLogging(Context context, boolean enabled) {
        if (context == null) return;
        try {
            context.getSharedPreferences(LOCAL_LOG_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(DiagnosticEvents.KEY_VERBOSE_LOGGING, enabled)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    static boolean isRecordingActive(long untilMs, long nowMs) {
        return untilMs > 0L && untilMs > nowMs;
    }

    private static boolean isDiagnosticRecordingActive(long nowMs) {
        return isRecordingActive(diagnosticRecordingUntilMs, nowMs);
    }

    private static void refreshDiagnosticRecordingFromRemotePreferences() {
        try {
            MelodyCodecLspEntry entry = MelodyCodecLspEntry.current();
            if (entry == null) return;
            SharedPreferences preferences = entry.getRemotePreferences(DiagnosticEvents.MODULE_PREFS);
            configureDiagnosticRecordingUntil(
                    preferences.getLong(DiagnosticEvents.KEY_RECORDING_UNTIL, 0L));
        } catch (Throwable ignored) {
            configureDiagnosticRecordingUntil(0L);
        }
    }

    /**
     * Reads the switch through the libxposed remote preferences pipe. Used at module / package
     * load, before a process context exists; the process-local mirror (applied in
     * {@link #setDiagnosticContext}) wins afterwards because the pipe returns defaults when
     * the module process is not running.
     */
    public static void refreshVerboseLoggingFromRemotePreferences() {
        if (verboseFromAuthoritativeSource) return;
        try {
            MelodyCodecLspEntry entry = MelodyCodecLspEntry.current();
            if (entry == null) return;
            configureVerboseLogging(entry.getRemotePreferences(DiagnosticEvents.MODULE_PREFS)
                    .getBoolean(DiagnosticEvents.KEY_VERBOSE_LOGGING, false));
        } catch (Throwable ignored) {
        }
    }

    private static synchronized void registerDiagnosticControlReceiver(Context context) {
        if (context == null || diagnosticControlReceiver != null) return;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                if (intent == null
                        || !DiagnosticEvents.ACTION_RECORDING_CONTROL.equals(intent.getAction())) {
                    return;
                }
                if (TrustedBroadcasts.supportsSenderIdentity()
                        && !TrustedBroadcasts.isTrustedSender(
                        receiverContext,
                        TrustedBroadcasts.captureSender(this),
                        BuildConfig.APPLICATION_ID)) {
                    return;
                }
                configureDiagnosticRecordingUntil(intent.getLongExtra(
                        DiagnosticEvents.EXTRA_RECORDING_UNTIL, 0L));
                if (intent.hasExtra(DiagnosticEvents.EXTRA_VERBOSE_LOGGING)) {
                    boolean verbose = intent.getBooleanExtra(
                            DiagnosticEvents.EXTRA_VERBOSE_LOGGING, false);
                    verboseFromAuthoritativeSource = true;
                    if (verbose != verboseLogging) {
                        configureVerboseLogging(verbose);
                        persistLocalVerboseLogging(receiverContext, verbose);
                        // Low-frequency, user-initiated: always visible so a log reader can
                        // tell where the verbose stream starts or stops.
                        event("diag.log.verbose", "enabled", verbose, "scope", diagnosticScope);
                    } else {
                        persistLocalVerboseLogging(receiverContext, verbose);
                    }
                }
                boolean active = isDiagnosticRecordingActive(System.currentTimeMillis());
                if (active) maybePublishDiagnosticSnapshot(receiverContext, diagnosticScope);
                eventLogOnly("diag.recording.control",
                        "active", active,
                        "scope", diagnosticScope);
            }
        };
        IntentFilter filter = new IntentFilter(DiagnosticEvents.ACTION_RECORDING_CONTROL);
        if (TrustedBroadcasts.registerExportedReceiver(
                context,
                receiver,
                filter,
                DiagnosticEvents.PERMISSION_MEMORY_SNAPSHOT_REQUEST,
                null)) {
            diagnosticControlReceiver = receiver;
        }
    }

    /** Called by {@link xyz.melodylsp.codec.host.HostHookInstaller} once host package info is known. */
    public static void setHostVersion(String hostVersionName) {
        if (hostVersionName != null && !hostVersionName.isEmpty()) {
            hostVersion = hostVersionName;
        }
    }

    /** Debug detail: emitted only in verbose mode (debug switch or recording session). */
    public static void d(String message) {
        if (!isVerboseActive()) return;
        emit(Log.DEBUG, message, null);
    }

    public static void d(String message, Throwable t) {
        if (!isVerboseActive()) return;
        emit(Log.DEBUG, message, t);
    }

    public static void i(String message) {
        emit(Log.INFO, message, null);
    }

    public static void w(String message) {
        emit(Log.WARN, message, null);
    }

    public static void w(String message, Throwable t) {
        emit(Log.WARN, message, t);
    }

    public static void e(String message) {
        emit(Log.ERROR, message, null);
    }

    public static void e(String message, Throwable t) {
        emit(Log.ERROR, message, t);
    }

    /** Structured event log; {@code kvPairs} is appended as {@code k=v} pairs separated by spaces. */
    public static void event(String name, Object... kvPairs) {
        emitEvent(name, true, kvPairs);
    }

    /**
     * Structured logcat event that is intentionally omitted from the persistent diagnostic ring.
     * Use this for high-frequency telemetry whose raw samples remain available in feedback logcat.
     */
    public static void eventLogOnly(String name, Object... kvPairs) {
        emitEvent(name, false, kvPairs);
    }

    private static void emitEvent(String name, boolean persistDiagnostic, Object... kvPairs) {
        long now = System.currentTimeMillis();
        boolean recording = isDiagnosticRecordingActive(now);
        int sinks = eventSinks(name, isVerbose(verboseLogging, diagnosticRecordingUntilMs, now));
        boolean persist = persistDiagnostic
                && (recording || isStatusEssentialEventName(name));
        boolean sticky = isStickyDiagnosticEvent(name);
        // Skip formatting entirely when nobody consumes the text (hot BQR / reply paths).
        if (sinks != SINK_NONE || persist || sticky) {
            StringBuilder sb = new StringBuilder("evt=").append(name);
            for (int i = 0; i + 1 < kvPairs.length; i += 2) {
                sb.append(' ').append(kvPairs[i]).append('=').append(kvPairs[i + 1]);
            }
            emit(Log.INFO, sb.toString(), null, persistDiagnostic, name, sinks);
        }
        StatusProbe.observe(name, kvPairs);
    }

    /** Output sinks for a structured event; never affects persistence or status probing. */
    static int eventSinks(String name, boolean verbose) {
        if (verbose || "diag.log.verbose".equals(name)) return SINK_ALL;
        if (isDebugOnlyEventName(name)) return SINK_NONE;
        if (isStatusEssentialEventName(name) || isStickyDiagnosticEvent(name)) return SINK_ALL;
        return SINK_LOGCAT;
    }

    static int plainSinks(int priority, boolean verbose) {
        if (verbose || priority >= Log.WARN) return SINK_ALL;
        if (priority >= Log.INFO) return SINK_LOGCAT;
        return SINK_NONE;
    }

    /**
     * High-frequency telemetry and request/reply chatter that is only useful while actively
     * debugging. Checked before the status-essential list, so an event can stay persisted for
     * the status page while its text output is debug-only. Recording sessions force verbose,
     * so feedback packs still contain all of these.
     */
    static boolean isDebugOnlyEventName(String name) {
        if (name == null || name.isEmpty()) return false;
        // Per-BQR-sample (~every 6 s while streaming, also for inactive devices) and remote
        // choppy telemetry from the bluetooth process.
        if (name.startsWith("lhdc.link.bqr")
                && !"lhdc.link.bqr_hooks".equals(name)) {
            return true;
        }
        // Process-start chatter repeated by every Melody process launch.
        if (name.startsWith("replay.bootstrap.")) return true;
        if (name.startsWith("dexkit.find.")) return true;
        if (name.startsWith("remember.snapshot.")) return true;
        switch (name) {
            case "lhdc.link.remote_choppy":
            case "lhdc.link.choppy_dedup":
            case "lhdc.link.live_control":
            case "lhdc.link.control_state":
            case "lhdc.link.governor_event":
            case "lhdc.link.probe_state":
            case "lhdc.dyn.observe":
            case "native.patch.state.recv":
            case "codec.bt.reply":
            case "codec.broadcast.query":
            case "codec.status.skip.acl_disconnected":
            case "codec.bridge.status.skip.acl_disconnected":
            case "le.bt.reply":
            case "le.ws.reply":
            case "le.melody.state.recv":
            case "game.ll.state.sent":
            case "game.ll.state.recv":
            case "game.ll.state.confirmed":
            case "snapshot.refresh.stale.ignore":
            case "snapshot.publish.device_mismatch":
            case "surface.scan.skip_non_codec":
            case "a2dp.ready.keep":
            case "replay.already_applied":
            case "replay.advisory.dedup":
            case "diag.recording.control":
                return true;
            default:
                return false;
        }
    }

    /** Returns a single-token throwable summary suitable for structured event values. */
    public static String compactThrowable(Throwable t) {
        if (t == null) return "unknown";
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        String value = root.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ':' + message);
        value = value.replaceAll("\\s+", "_");
        return value.length() <= 96 ? value : value.substring(0, 96);
    }

    private static void emit(int priority, String message, Throwable t) {
        emit(priority, message, t, true, null,
                plainSinks(priority, isVerboseActive()));
    }

    private static void emit(
            int priority,
            String message,
            Throwable t,
            boolean persistDiagnostic,
            String stickyEventName,
            int sinks) {
        long time = System.currentTimeMillis();
        String prefixed = prefix() + redactBluetoothAddresses(message);
        String safeStack = t != null
                ? redactBluetoothAddresses(Log.getStackTraceString(t))
                : null;
        if (sinks != SINK_NONE) {
            if (t == null) {
                Log.println(priority, TAG, prefixed);
            } else {
                Log.println(priority, TAG, prefixed + '\n' + safeStack);
            }
        }
        XposedInterface api = xposed;
        if (api != null && sinks == SINK_ALL) {
            try {
                if (t == null) api.log(priority, TAG, prefixed);
                else api.log(priority, TAG, prefixed + '\n' + safeStack);
            } catch (Throwable swallow) {
                // Logging never crashes the app.
            }
        }
        String diagnosticMessage = t == null ? prefixed : prefixed + '\n' + safeStack;
        if (stickyEventName != null && isStickyDiagnosticEvent(stickyEventName)) {
            rememberStickyDiagnostic(stickyEventName, priority, diagnosticMessage, time);
        }
        boolean persistNow = persistDiagnostic
                && (isDiagnosticRecordingActive(time)
                || isStatusEssentialEventName(stickyEventName));
        if (persistNow) {
            Context context = diagnosticContext;
            if (context != null) {
                DiagnosticEvents.send(context, diagnosticScope, priority, diagnosticMessage, time);
            } else {
                enqueuePendingDiagnostic(priority, diagnosticMessage, time);
            }
        }
    }

    /**
     * Status-essential events are low-frequency state snapshots that must stay visible on the
     * diagnostic page even outside a recording session (V3-15: status rows are decoupled from
     * feedback recording). Besides the remember-card mirror and native patch results, the
     * scope / hook / injection / bridge / write / replay families are captured at hook time so
     * the page shows the last known state without starting a recording. High-frequency
     * activity events (codec.bt.reply, le.bt.*, lhdc.link.bqr_summary, remote_choppy, …) stay
     * recording-gated to avoid non-session pref churn.
     */
    public static boolean isStatusEssentialEventName(String eventName) {
        if (eventName == null) return false;
        if (eventName.startsWith("remember.snapshot.")) return true;
        if (eventName.startsWith("remember.write")) return true;
        if (eventName.startsWith("replay.")) return true;
        if (eventName.startsWith("write.")) return true;
        if (eventName.startsWith("dexkit.")) return true;
        if ("lhdc.memory_patch".equals(eventName)) return true;
        if ("lhdc.memory_patch.fast_switch".equals(eventName)) return true;
        if ("native.patch.state.recv".equals(eventName)) return true;
        switch (eventName) {
            case "scope.host.start":
            case "scope.host.context.ready":
            case "scope.system.start":
            case "scope.system.context.ready":
            case "scope.wirelesssettings.start":
            case "scope.wirelesssettings.context.ready":
            case "diag.scope.snapshot":
            case "controller.ready":
            case "preference.fragment.hooked":
            case "detailmain.activity.hooked":
            case "onespace.activity.hooked":
            case "onespace.fragment.hooked":
            case "activity.scan.registered":
            case "hires_anchored.injected":
            case "detailmain_fallback.injected":
            case "onespace.injected":
            case "system.bridge.registered":
            case "codec.bt.receiver.registered":
            case "le.bt.receiver.registered":
            case "le.ws.receiver.registered":
            case "bt.a2dp.resolved":
            case "codec.updated.hooks":
            case "cdm.hooks":
            case "cdm.bypass":
            case "a2dp.setCodecConfigPreference":
            case "bt.native.setCodecConfigPreference":
            case "lhdc.link.stage_d":
            case "lhdc.governor.choppy_hooks":
            case "lhdc.governor.queue_hooks":
            case "lhdc.link.bqr_hooks":
            case "remember.set":
                return true;
            default:
                return false;
        }
    }

    private static String prefix() {
        return "[mod=" + BuildConfig.VERSION_NAME + " host=" + hostVersion + "] ";
    }

    static String redactBluetoothAddresses(String value) {
        if (value == null || value.isEmpty()) return value;
        return BLUETOOTH_ADDRESS.matcher(value).replaceAll("$1:**:**:**:**:$2");
    }

    private static void enqueuePendingDiagnostic(int priority, String message, long time) {
        if (priority < Log.INFO) return;
        synchronized (PENDING_LOCK) {
            pendingDiagnostics.add(new PendingDiagnostic(priority, message, time));
            while (pendingDiagnostics.size() > MAX_PENDING_DIAGNOSTICS) {
                pendingDiagnostics.remove(0);
            }
        }
    }

    private static void flushPendingDiagnostics(Context context, String scope) {
        if (context == null) return;
        List<PendingDiagnostic> copy;
        synchronized (PENDING_LOCK) {
            if (pendingDiagnostics.isEmpty()) return;
            copy = new ArrayList<>(pendingDiagnostics);
            pendingDiagnostics.clear();
        }
        for (PendingDiagnostic pending : copy) {
            DiagnosticEvents.send(context, scope, pending.priority, pending.message, pending.time);
        }
    }

    private static void sendDiagnosticScopeSnapshot(Context context, String scope) {
        if (context == null) return;
        String safeScope = scope != null && !scope.isEmpty() ? scope : "unknown";
        DiagnosticEvents.send(
                context,
                safeScope,
                Log.INFO,
                prefix() + "evt=diag.scope.snapshot scope=" + safeScope,
                System.currentTimeMillis());
    }

    private static void maybePublishDiagnosticSnapshot(Context context, String scope) {
        if (context == null) return;
        long now = System.currentTimeMillis();
        long until = diagnosticRecordingUntilMs;
        if (!shouldPublishDiagnosticSnapshot(until, diagnosticSnapshotPublishedUntilMs, now)) {
            return;
        }
        synchronized (PENDING_LOCK) {
            if (!shouldPublishDiagnosticSnapshot(
                    until, diagnosticSnapshotPublishedUntilMs, System.currentTimeMillis())) {
                return;
            }
            diagnosticSnapshotPublishedUntilMs = until;
        }
        sendDiagnosticScopeSnapshot(context, scope);
        flushStickyDiagnostics(context, scope);
    }

    static boolean shouldPublishDiagnosticSnapshot(long until, long publishedUntil, long now) {
        return isRecordingActive(until, now) && until != publishedUntil;
    }

    private static void rememberStickyDiagnostic(
            String eventName, int priority, String message, long time) {
        synchronized (PENDING_LOCK) {
            stickyDiagnostics.remove(eventName);
            stickyDiagnostics.put(eventName, new PendingDiagnostic(priority, message, time));
            while (stickyDiagnostics.size() > MAX_STICKY_DIAGNOSTICS) {
                String oldest = stickyDiagnostics.keySet().iterator().next();
                stickyDiagnostics.remove(oldest);
            }
        }
    }

    private static void flushStickyDiagnostics(Context context, String scope) {
        if (context == null) return;
        List<PendingDiagnostic> copy;
        synchronized (PENDING_LOCK) {
            copy = new ArrayList<>(stickyDiagnostics.values());
        }
        long now = System.currentTimeMillis();
        for (PendingDiagnostic pending : copy) {
            // The cached event describes state that is still true now. Refresh its timestamp so
            // a long-running process is not rejected by the diagnostic anti-replay window.
            DiagnosticEvents.send(context, scope, pending.priority, pending.message, now);
        }
    }

    static boolean isStickyDiagnosticEvent(String name) {
        if (name == null || name.isEmpty()) return false;
        return name.startsWith("scope.")
                || name.startsWith("dexkit.")
                || name.endsWith(".hooked")
                || name.endsWith(".injected")
                || name.endsWith(".registered")
                || "controller.ready".equals(name)
                || "activity.scan.registered".equals(name)
                || "bt.a2dp.resolved".equals(name)
                || "codec.updated.hooks".equals(name)
                || "ui.bindings.resolved".equals(name)
                || "cdm.hooks".equals(name)
                || "lhdc.memory_patch".equals(name)
                || "lhdc.pacing.install".equals(name)
                || "lhdc.link.stage_d".equals(name)
                || "lhdc.link.bqr_hooks".equals(name)
                || "lhdc.governor.choppy_hooks".equals(name)
                || "lhdc.governor.queue_hooks".equals(name);
    }

    private static void clearPendingDiagnostics() {
        synchronized (PENDING_LOCK) {
            pendingDiagnostics.clear();
        }
    }

    private static final class PendingDiagnostic {
        final int priority;
        final String message;
        final long time;

        PendingDiagnostic(int priority, String message, long time) {
            this.priority = priority;
            this.message = message;
            this.time = time;
        }
    }
}
