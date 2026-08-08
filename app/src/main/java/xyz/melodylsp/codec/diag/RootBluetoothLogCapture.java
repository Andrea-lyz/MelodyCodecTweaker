package xyz.melodylsp.codec.diag;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Keeps a bounded, tag-filtered root logcat for the full diagnostic session.
 *
 * <p>The ordinary logcat ring can turn over in seconds on verbose ColorOS builds. This capture is
 * deliberately diagnostic-only: it does not clear or resize global buffers and never touches the
 * LHDC governor.</p>
 */
public final class RootBluetoothLogCapture {

    static final String KEY_CAPTURE_SESSION = "root.capture.session";
    static final String KEY_CAPTURE_STATUS = "root.capture.status";
    static final String KEY_CAPTURE_DETAIL = "root.capture.detail";
    static final String KEY_CAPTURE_STARTED = "root.capture.started";

    private static final Pattern SESSION_ID = Pattern.compile("[0-9]{8}-[0-9]{6}");
    private static final String ROOT_DIR = "/data/local/tmp/melody-codec-stage-d";
    private static final int ROTATE_SIZE_KB = 1_024;
    private static final int ROTATE_COUNT = 2;
    private static final int CAPTURE_TIMEOUT_SECONDS = 31 * 60;
    private static final long ROOT_COMMAND_TIMEOUT_MS = 12_000L;
    private static final int MAX_DETAIL_CHARS = 320;

    private static final String LOGCAT_FILTERS =
            "MelodyCodecLsp:V "
                    + "MelodyLhdcGov:V "
                    + "BluetoothQualityReportNativeInterface:V "
                    + "BluetoothQualityReportJni:V "
                    + "bluetooth-a2dp:V "
                    + "soc_bta_av:V "
                    + "a2dp_vendor_lhdcv5:V "
                    + "a2dp_vendor_lhdcv5_encoder:V '*:S'";

    private RootBluetoothLogCapture() {
    }

    public static StartResult start(Context context, String sessionId) {
        if (context == null || !isValidSessionId(sessionId)) {
            return new StartResult(false, "invalid_session");
        }
        String output = FeedbackCollector.runRootCommandForDiagnostics(
                buildStartCommand(sessionId), ROOT_COMMAND_TIMEOUT_MS);
        boolean started = output.contains("capture_started")
                || output.contains("capture_already_running");
        String detail = summarize(output);
        updateStatus(context, sessionId, started ? "started" : "failed", detail, true);
        return new StartResult(started, detail);
    }

    public static void markUnavailable(Context context, String sessionId, String reason) {
        if (context == null || !isValidSessionId(sessionId)) return;
        updateStatus(context, sessionId, "unavailable", summarize(reason), true);
    }

    /** Stops the current capture without deleting its logs, so the user may package them later. */
    public static void stopAsync(Context context, String reason) {
        if (context == null) return;
        Context appContext = context.getApplicationContext();
        String sessionId = currentSessionId(appContext);
        if (!isValidSessionId(sessionId)) return;
        String status = capturePrefs(appContext).getString(KEY_CAPTURE_STATUS, "");
        if ("cleaned".equals(status) || "unavailable".equals(status)) return;
        Thread worker = new Thread(() -> {
            String output = FeedbackCollector.runRootCommandForDiagnostics(
                    buildStopCommand(sessionId), ROOT_COMMAND_TIMEOUT_MS);
            updateStatus(appContext, sessionId, "stopped",
                    summarize(reason) + ":" + summarize(output), false);
        }, "OPlusHeadsetAudioHelper-root-log-stop");
        worker.setDaemon(true);
        worker.start();
    }

    /** Stops and removes the previous session before a new diagnostic recording starts. */
    public static void retireCurrentSession(Context context, String reason) {
        if (context == null) return;
        String sessionId = currentSessionId(context);
        if (!isValidSessionId(sessionId)) return;
        retireSession(context, sessionId, reason);
    }

    /**
     * Stops and removes a specific session id (used to roll back a capture whose start failed,
     * before the diagnostic session is committed).
     */
    public static void retireSession(Context context, String sessionId, String reason) {
        if (context == null || !isValidSessionId(sessionId)) return;
        FeedbackCollector.runRootCommandForDiagnostics(
                buildStopCommand(sessionId), ROOT_COMMAND_TIMEOUT_MS);
        FeedbackCollector.runRootCommandForDiagnostics(
                buildCleanupCommand(sessionId), ROOT_COMMAND_TIMEOUT_MS);
        updateStatus(context, sessionId, "cleaned", summarize(reason), false);
    }

    /** Stops the logger and returns rotated files from oldest to newest. Files remain for retry. */
    public static String stopAndRead(Context context, String sessionId) {
        if (context == null || !isValidSessionId(sessionId)) return "";
        String stopOutput = FeedbackCollector.runRootCommandForDiagnostics(
                buildStopCommand(sessionId), ROOT_COMMAND_TIMEOUT_MS);
        String logOutput = FeedbackCollector.runRootCommandForDiagnostics(
                buildReadCommand(sessionId), ROOT_COMMAND_TIMEOUT_MS);
        boolean available = !logOutput.startsWith("root command failed:")
                && !logOutput.contains("capture_log_missing");
        updateStatus(context, sessionId, available ? "collected" : "missing",
                summarize(stopOutput) + ":chars=" + logOutput.length(), false);
        return available ? logOutput : "";
    }

    /** Deletes only the files belonging to the validated session after the ZIP is complete. */
    public static void cleanup(Context context, String sessionId) {
        if (context == null || !isValidSessionId(sessionId)) return;
        String output = FeedbackCollector.runRootCommandForDiagnostics(
                buildCleanupCommand(sessionId), ROOT_COMMAND_TIMEOUT_MS);
        updateStatus(context, sessionId, "cleaned", summarize(output), false);
    }

    static boolean isValidSessionId(String sessionId) {
        return sessionId != null && SESSION_ID.matcher(sessionId).matches();
    }

    static String buildStartCommand(String sessionId) {
        requireValidSessionId(sessionId);
        String log = logPath(sessionId);
        String pid = pidPath(sessionId);
        return "DIR='" + ROOT_DIR + "'; LOG='" + log + "'; PID='" + pid + "'; "
                + "/system/bin/mkdir -p \"$DIR\" && /system/bin/chmod 700 \"$DIR\" || exit 1; "
                + "if [ -r \"$PID\" ]; then P=$(/system/bin/cat \"$PID\" 2>/dev/null); "
                + "case \"$P\" in ''|*[!0-9]*) ;; *) "
                + "CMD=$(/system/bin/tr '\\000' ' ' < \"/proc/$P/cmdline\" 2>/dev/null); "
                + "case \"$CMD\" in *logcat*\"$LOG\"*) "
                + "if /system/bin/kill -0 \"$P\" 2>/dev/null; then "
                + "echo capture_already_running pid=$P; exit 0; fi ;; esac ;; esac; fi; "
                + "/system/bin/rm -f \"$LOG\" \"$LOG.1\" \"$LOG.2\" \"$PID\"; "
                + "( exec /system/bin/toybox nohup /system/bin/toybox timeout "
                + CAPTURE_TIMEOUT_SECONDS + " /system/bin/logcat -b all -v threadtime "
                + "-f \"$LOG\" -r " + ROTATE_SIZE_KB + " -n " + ROTATE_COUNT + " "
                + LOGCAT_FILTERS + " ) </dev/null >/dev/null 2>&1 & P=$!; "
                + "echo \"$P\" > \"$PID\"; /system/bin/sleep 1; "
                + "if /system/bin/kill -0 \"$P\" 2>/dev/null; then "
                + "echo capture_started pid=$P; else /system/bin/rm -f \"$PID\"; "
                + "echo capture_failed; exit 1; fi";
    }

    static String buildStopCommand(String sessionId) {
        requireValidSessionId(sessionId);
        String log = logPath(sessionId);
        String pid = pidPath(sessionId);
        return "LOG='" + log + "'; PID='" + pid + "'; "
                + "if [ ! -r \"$PID\" ]; then echo capture_not_running; exit 0; fi; "
                + "P=$(/system/bin/cat \"$PID\" 2>/dev/null); "
                + "case \"$P\" in ''|*[!0-9]*) echo capture_invalid_pid; "
                + "/system/bin/rm -f \"$PID\"; exit 0 ;; esac; "
                + "CMD=$(/system/bin/tr '\\000' ' ' < \"/proc/$P/cmdline\" 2>/dev/null); "
                + "case \"$CMD\" in *logcat*\"$LOG\"*) "
                + "CHILDREN=$(/system/bin/cat \"/proc/$P/task/$P/children\" 2>/dev/null); "
                + "for Q in $CHILDREN $P; do case \"$Q\" in ''|*[!0-9]*) continue ;; esac; "
                + "QC=$(/system/bin/tr '\\000' ' ' < \"/proc/$Q/cmdline\" 2>/dev/null); "
                + "case \"$QC\" in *logcat*\"$LOG\"*) /system/bin/kill -TERM \"$Q\" 2>/dev/null ;; esac; done; "
                + "/system/bin/sleep 1; "
                + "for Q in $CHILDREN $P; do case \"$Q\" in ''|*[!0-9]*) continue ;; esac; "
                + "QC=$(/system/bin/tr '\\000' ' ' < \"/proc/$Q/cmdline\" 2>/dev/null); "
                + "case \"$QC\" in *logcat*\"$LOG\"*) /system/bin/kill -KILL \"$Q\" 2>/dev/null ;; esac; done; "
                + "echo capture_stopped pid=$P ;; *) echo capture_pid_mismatch pid=$P ;; esac; "
                + "/system/bin/rm -f \"$PID\"";
    }

    static String buildReadCommand(String sessionId) {
        requireValidSessionId(sessionId);
        String log = logPath(sessionId);
        return "LOG='" + log + "'; FOUND=0; "
                + "for F in \"$LOG.2\" \"$LOG.1\" \"$LOG\"; do "
                + "if [ -r \"$F\" ]; then echo \"----- persistent root log: $F -----\"; "
                + "/system/bin/cat \"$F\"; FOUND=1; fi; done; "
                + "if [ \"$FOUND\" -eq 0 ]; then echo capture_log_missing; fi";
    }

    static String buildCleanupCommand(String sessionId) {
        requireValidSessionId(sessionId);
        String log = logPath(sessionId);
        String pid = pidPath(sessionId);
        return "/system/bin/rm -f '" + log + "' '" + log + ".1' '" + log
                + ".2' '" + pid + "'; echo capture_cleaned";
    }

    private static void requireValidSessionId(String sessionId) {
        if (!isValidSessionId(sessionId)) {
            throw new IllegalArgumentException("Invalid diagnostic session id");
        }
    }

    private static String currentSessionId(Context context) {
        return capturePrefs(context).getString(DiagnosticEvents.KEY_SESSION_ID, "");
    }

    private static SharedPreferences capturePrefs(Context context) {
        return context.getSharedPreferences(DiagnosticEvents.PREFS, Context.MODE_PRIVATE);
    }

    private static String logPath(String sessionId) {
        return ROOT_DIR + "/bt-" + sessionId + ".log";
    }

    private static String pidPath(String sessionId) {
        return ROOT_DIR + "/bt-" + sessionId + ".pid";
    }

    private static void updateStatus(
            Context context,
            String sessionId,
            String status,
            String detail,
            boolean setStartedAt) {
        if (context == null || !isValidSessionId(sessionId)) return;
        SharedPreferences.Editor editor = capturePrefs(context).edit()
                .putString(KEY_CAPTURE_SESSION, sessionId)
                .putString(KEY_CAPTURE_STATUS, status)
                .putString(KEY_CAPTURE_DETAIL, summarize(detail));
        if (setStartedAt) editor.putLong(KEY_CAPTURE_STARTED, System.currentTimeMillis());
        editor.commit();
        DiagnosticEvents.recordLocal(context, "module", Log.INFO,
                "evt=diag.root_capture status=" + safeToken(status)
                        + " session=" + sessionId
                        + " detail=" + safeToken(detail),
                System.currentTimeMillis());
    }

    private static String summarize(String value) {
        if (value == null) return "";
        String clean = value.replace('\r', ' ').replace('\n', ' ').trim();
        if (clean.length() <= MAX_DETAIL_CHARS) return clean;
        return clean.substring(0, MAX_DETAIL_CHARS);
    }

    private static String safeToken(String value) {
        String clean = summarize(value).replaceAll("\\s+", "_");
        return clean.isEmpty() ? "none" : clean.toLowerCase(Locale.ROOT);
    }

    public static final class StartResult {
        public final boolean started;
        public final String detail;

        StartResult(boolean started, String detail) {
            this.started = started;
            this.detail = detail != null ? detail : "";
        }
    }
}
