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

/**
 * Module-wide logging facade. Logs go to standard {@link Log} as well as the libxposed
 * framework log when an {@link XposedInterface} has been attached, so users can either tail
 * {@code logcat -s MelodyCodecLsp} or read the LSPosed module log viewer.
 */
public final class MLog {

    public static final String TAG = "MelodyCodecLsp";

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
        registerDiagnosticControlReceiver(appContext);
        if (isDiagnosticRecordingActive(System.currentTimeMillis())) {
            flushPendingDiagnostics(appContext, diagnosticScope);
            maybePublishDiagnosticSnapshot(appContext, diagnosticScope);
        } else {
            clearPendingDiagnostics();
        }
    }

    public static void configureDiagnosticRecordingUntil(long untilMs) {
        diagnosticRecordingUntilMs = Math.max(0L, untilMs);
        if (!isDiagnosticRecordingActive(System.currentTimeMillis())) {
            diagnosticSnapshotPublishedUntilMs = 0L;
            clearPendingDiagnostics();
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

    public static void d(String message) {
        emit(Log.DEBUG, message, null);
    }

    public static void d(String message, Throwable t) {
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
        StringBuilder sb = new StringBuilder("evt=").append(name);
        for (int i = 0; i + 1 < kvPairs.length; i += 2) {
            sb.append(' ').append(kvPairs[i]).append('=').append(kvPairs[i + 1]);
        }
        emit(Log.INFO, sb.toString(), null, persistDiagnostic, name);
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
        emit(priority, message, t, true);
    }

    private static void emit(
            int priority, String message, Throwable t, boolean persistDiagnostic) {
        emit(priority, message, t, persistDiagnostic, null);
    }

    private static void emit(
            int priority,
            String message,
            Throwable t,
            boolean persistDiagnostic,
            String stickyEventName) {
        long time = System.currentTimeMillis();
        String prefixed = prefix() + redactBluetoothAddresses(message);
        String safeStack = t != null
                ? redactBluetoothAddresses(Log.getStackTraceString(t))
                : null;
        if (t == null) {
            Log.println(priority, TAG, prefixed);
        } else {
            Log.println(priority, TAG, prefixed + '\n' + safeStack);
        }
        XposedInterface api = xposed;
        if (api != null) {
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
                || "cdm.hooks".equals(name)
                || "lhdc.memory_patch".equals(name)
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
