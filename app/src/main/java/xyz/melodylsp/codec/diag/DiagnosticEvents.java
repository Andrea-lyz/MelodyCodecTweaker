package xyz.melodylsp.codec.diag;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import xyz.melodylsp.codec.BuildConfig;

public final class DiagnosticEvents {

    public static final String ACTION = BuildConfig.APPLICATION_ID + ".action.DIAGNOSTIC_EVENT";
    public static final String PREFS = "diagnostics";
    public static final String KEY_EVENTS = "events";
    public static final String KEY_EVENTS_JSON = "events_jsonl";
    public static final String KEY_SESSION_ID = "session.id";
    public static final String KEY_SESSION_STARTED = "session.started";
    public static final String EXTRA_SCOPE = "scope";
    public static final String EXTRA_PRIORITY = "priority";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_TIME = "time";

    private static final int MAX_EVENTS_CHARS = 256_000;
    private static final int MAX_EVENTS_JSON_CHARS = 256_000;

    private DiagnosticEvents() {
    }

    public static void send(Context context, String scope, int priority, String message) {
        send(context, scope, priority, message, System.currentTimeMillis());
    }

    public static void send(
            Context context,
            String scope,
            int priority,
            String message,
            long time) {
        if (context == null || message == null || priority < Log.INFO) return;
        try {
            Intent intent = new Intent(ACTION);
            intent.setPackage(BuildConfig.APPLICATION_ID);
            intent.putExtra(EXTRA_SCOPE, scope != null ? scope : "unknown");
            intent.putExtra(EXTRA_PRIORITY, priority);
            intent.putExtra(EXTRA_MESSAGE, message);
            intent.putExtra(EXTRA_TIME, time > 0L ? time : System.currentTimeMillis());
            context.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    public static String startSession(Context context) {
        if (context == null) return "";
        long now = System.currentTimeMillis();
        String id = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date(now));
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit()
                .putString(KEY_SESSION_ID, id)
                .putLong(KEY_SESSION_STARTED, now)
                .remove(KEY_EVENTS)
                .remove(KEY_EVENTS_JSON);
        for (String key : sp.getAll().keySet()) {
            if (key.startsWith("status.")
                    || key.startsWith("detail.")
                    || key.startsWith("time.")
                    || key.startsWith("last.")) {
                editor.remove(key);
            }
        }
        editor.apply();
        record(context, "module", Log.INFO, "evt=diag.session.start id=" + id, now);
        return id;
    }

    public static void record(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String message = intent.getStringExtra(EXTRA_MESSAGE);
        if (message == null || message.isEmpty()) return;
        String scope = intent.getStringExtra(EXTRA_SCOPE);
        int priority = intent.getIntExtra(EXTRA_PRIORITY, Log.INFO);
        long time = intent.getLongExtra(EXTRA_TIME, System.currentTimeMillis());
        record(context, scope, priority, message, time);
    }

    private static void record(
            Context context,
            String scope,
            int priority,
            String message,
            long time) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String safeScope = safe(scope);
        String line = formatTime(time) + " [" + safeScope + "/" + level(priority) + "] "
                + message.replace('\n', ' ');
        String old = sp.getString(KEY_EVENTS, "");
        String merged = trimRing(old == null || old.isEmpty() ? line : old + '\n' + line,
                MAX_EVENTS_CHARS);

        String jsonLine = jsonLine(time, safeScope, priority, message);
        String oldJson = sp.getString(KEY_EVENTS_JSON, "");
        String mergedJson = trimRing(
                oldJson == null || oldJson.isEmpty() ? jsonLine : oldJson + '\n' + jsonLine,
                MAX_EVENTS_JSON_CHARS);

        SharedPreferences.Editor editor = sp.edit()
                .putString(KEY_EVENTS, merged)
                .putString(KEY_EVENTS_JSON, mergedJson)
                .putString("last.scope", safeScope)
                .putString("last.level", level(priority))
                .putString("last.message", message)
                .putLong("last.time", time);
        classify(editor, message, priority, time);
        editor.apply();
    }

    public static String status(SharedPreferences sp, String key) {
        return sp.getString("status." + key, "not seen");
    }

    public static String detail(SharedPreferences sp, String key) {
        return sp.getString("detail." + key, "");
    }

    public static long time(SharedPreferences sp, String key) {
        return sp.getLong("time." + key, 0L);
    }

    public static String formatTime(long time) {
        if (time <= 0L) return "-";
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(new Date(time));
    }

    private static void classify(
            SharedPreferences.Editor editor,
            String message,
            int priority,
            long time) {
        if (message.contains("evt=scope.host.start")
                || message.contains("evt=scope.host.context.ready")) {
            mark(editor, "scope.host", "loaded", message, time);
        }
        if (message.contains("evt=controller.ready")) {
            mark(editor, "host.controller", "ready", message, time);
        }
        if (message.contains("evt=preference.fragment.hooked")
                || message.contains("evt=detailmain.activity.hooked")
                || message.contains("evt=onespace.activity.hooked")
                || message.contains("evt=activity.scan.registered")) {
            mark(editor, "hook.host", "hooked", message, time);
        }
        if (message.contains("evt=hires_anchored.injected")
                || message.contains("evt=detailmain_fallback.injected")) {
            mark(editor, "inject.detail", "injected", message, time);
        }
        if (message.contains("evt=onespace.injected")) {
            mark(editor, "inject.onespace", "injected", message, time);
        }
        if (message.contains("evt=scope.system.start")
                || message.contains("evt=scope.system.context.ready")
                || message.contains("evt=bt.a2dp.resolved")
                || message.contains("evt=codec.updated.hooks")
                || message.contains("evt=cdm.hooks")
                || message.contains("evt=codec.bt.")
                || message.contains("evt=le.bt.")) {
            mark(editor, "scope.bluetooth", "loaded", message, time);
        }
        if (message.contains("evt=system.bridge.registered")
                || message.contains("evt=codec.bt.receiver.registered")
                || message.contains("evt=codec.bt.reply")
                || message.contains("evt=codec.bt.set")
                || message.contains("evt=bt.a2dp.resolved")
                || message.contains("evt=codec.updated.hooks")
                || message.contains("evt=a2dp.setCodecConfigPreference")
                || message.contains("evt=cdm.bypass")) {
            mark(editor, "bridge.codec", "registered", message, time);
        }
        if (message.contains("evt=le.bt.receiver.registered")
                || message.contains("evt=le.bt.")) {
            mark(editor, "bridge.le.bt", "registered", message, time);
        }
        if (message.contains("evt=scope.wirelesssettings.start")
                || message.contains("evt=scope.wirelesssettings.context.ready")
                || message.contains("evt=le.ws.")) {
            mark(editor, "scope.wirelesssettings", "loaded", message, time);
        }
        if (message.contains("evt=le.ws.receiver.registered")
                || message.contains("evt=le.ws.")) {
            mark(editor, "bridge.le.ws", "registered", message, time);
        }
        if (message.contains("evt=lhdc.memory_patch")
                || message.contains("evt=native.patch.state.recv")) {
            mark(editor, "native.patch", stateFromMessage(message), message, time);
        }
        if (message.contains("evt=write.")
                || message.contains("evt=a2dp.setCodecConfigPreference")
                || message.contains("evt=bt.native.setCodecConfigPreference")) {
            mark(editor, "codec.write", stateFromMessage(message), message, time);
        }
        if (message.contains("evt=remember.write")
                || message.contains("evt=remember.set")) {
            mark(editor, "remember.write", stateFromMessage(message), message, time);
        }
        if (message.contains("evt=replay.")) {
            mark(editor, "remember.replay", stateFromMessage(message), message, time);
        }
        if (priority >= Log.WARN && !isRecoverableFallbackWarning(message)) {
            String key = priority >= Log.ERROR ? "last.error" : "last.warning";
            mark(editor, key, priority >= Log.ERROR ? "error" : "warning", message, time);
        }
    }

    private static void mark(
            SharedPreferences.Editor editor,
            String key,
            String status,
            String detail,
            long time) {
        editor.putString("status." + key, status)
                .putString("detail." + key, detail)
                .putLong("time." + key, time);
    }

    private static String safe(String value) {
        return value != null && !value.isEmpty() ? value : "unknown";
    }

    private static String level(int priority) {
        if (priority >= Log.ERROR) return "E";
        if (priority >= Log.WARN) return "W";
        if (priority >= Log.INFO) return "I";
        if (priority >= Log.DEBUG) return "D";
        return "V";
    }

    private static String stateFromMessage(String message) {
        if (message.contains("success=true")
                || message.contains("outcome=CONFIRMED")
                || message.contains("evt=replay.stable")
                || message.contains("evt=remember.write.delayed_confirmed")
                || message.contains("status=patched")
                || message.contains("status=already_patched")) {
            return "ok";
        }
        if (message.contains("skip")
                || message.contains("pending")
                || message.contains("waiting")) {
            return "pending";
        }
        if (message.contains("failed")
                || message.contains("FAILED")
                || message.contains("TIMEOUT")
                || message.contains("evt=replay.unstable")
                || message.contains("unsupported")
                || message.contains("success=false")) {
            return "attention";
        }
        return "seen";
    }

    private static boolean isRecoverableFallbackWarning(String message) {
        return message.contains("Path-A setCodec failed")
                || message.contains("Path-A setOptionalCodecs failed");
    }

    private static String trimRing(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) return value;
        String trimmed = value.substring(value.length() - maxChars);
        int firstBreak = trimmed.indexOf('\n');
        if (firstBreak >= 0 && firstBreak + 1 < trimmed.length()) {
            return trimmed.substring(firstBreak + 1);
        }
        return trimmed;
    }

    private static String jsonLine(long time, String scope, int priority, String message) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        appendJsonField(sb, "time", String.valueOf(time), false);
        sb.append(',');
        appendJsonField(sb, "timeText", formatTime(time), true);
        sb.append(',');
        appendJsonField(sb, "scope", scope, true);
        sb.append(',');
        appendJsonField(sb, "level", level(priority), true);
        sb.append(',');
        appendJsonField(sb, "event", eventName(message), true);
        sb.append(',');
        appendJsonField(sb, "message", message.replace('\n', ' '), true);
        sb.append('}');
        return sb.toString();
    }

    private static void appendJsonField(
            StringBuilder sb,
            String name,
            String value,
            boolean quoted) {
        sb.append('"').append(name).append('"').append(':');
        if (!quoted) {
            sb.append(value);
            return;
        }
        sb.append('"').append(jsonEscape(value)).append('"');
    }

    private static String eventName(String message) {
        int start = message != null ? message.indexOf("evt=") : -1;
        if (start < 0) return "";
        start += 4;
        int end = message.indexOf(' ', start);
        return end > start ? message.substring(start, end) : message.substring(start);
    }

    private static String jsonEscape(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    out.append("\\\\");
                    break;
                case '"':
                    out.append("\\\"");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                    break;
            }
        }
        return out.toString();
    }
}
