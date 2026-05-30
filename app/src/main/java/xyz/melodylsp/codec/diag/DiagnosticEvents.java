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
    public static final String EXTRA_SCOPE = "scope";
    public static final String EXTRA_PRIORITY = "priority";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_TIME = "time";

    private static final int MAX_EVENTS_CHARS = 16_000;

    private DiagnosticEvents() {
    }

    public static void send(Context context, String scope, int priority, String message) {
        if (context == null || message == null || priority < Log.INFO) return;
        try {
            Intent intent = new Intent(ACTION);
            intent.setPackage(BuildConfig.APPLICATION_ID);
            intent.putExtra(EXTRA_SCOPE, scope != null ? scope : "unknown");
            intent.putExtra(EXTRA_PRIORITY, priority);
            intent.putExtra(EXTRA_MESSAGE, message);
            intent.putExtra(EXTRA_TIME, System.currentTimeMillis());
            context.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    public static void record(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String message = intent.getStringExtra(EXTRA_MESSAGE);
        if (message == null || message.isEmpty()) return;
        String scope = intent.getStringExtra(EXTRA_SCOPE);
        int priority = intent.getIntExtra(EXTRA_PRIORITY, Log.INFO);
        long time = intent.getLongExtra(EXTRA_TIME, System.currentTimeMillis());

        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String line = formatTime(time) + " [" + safe(scope) + "/" + level(priority) + "] "
                + message.replace('\n', ' ');
        String old = sp.getString(KEY_EVENTS, "");
        String merged = old == null || old.isEmpty() ? line : old + '\n' + line;
        if (merged.length() > MAX_EVENTS_CHARS) {
            merged = merged.substring(merged.length() - MAX_EVENTS_CHARS);
            int firstBreak = merged.indexOf('\n');
            if (firstBreak >= 0 && firstBreak + 1 < merged.length()) {
                merged = merged.substring(firstBreak + 1);
            }
        }

        SharedPreferences.Editor editor = sp.edit()
                .putString(KEY_EVENTS, merged)
                .putString("last.scope", safe(scope))
                .putString("last.level", level(priority))
                .putString("last.message", message)
                .putLong("last.time", time);
        classify(editor, message, priority, time);
        editor.apply();
    }

    public static String status(SharedPreferences sp, String key) {
        return sp.getString("status." + key, "未收到");
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
            SharedPreferences.Editor editor, String message, int priority, long time) {
        if (message.contains("evt=scope.host.start")
                || message.contains("evt=scope.host.context.ready")) {
            mark(editor, "scope.host", "已加载", message, time);
        }
        if (message.contains("evt=controller.ready")) {
            mark(editor, "host.controller", "已就绪", message, time);
        }
        if (message.contains("evt=preference.fragment.hooked")
                || message.contains("evt=detailmain.activity.hooked")
                || message.contains("evt=onespace.activity.hooked")
                || message.contains("evt=activity.scan.registered")) {
            mark(editor, "hook.host", "已安装", message, time);
        }
        if (message.contains("evt=hires_anchored.injected")
                || message.contains("evt=detailmain_fallback.injected")) {
            mark(editor, "inject.detail", "已注入", message, time);
        }
        if (message.contains("evt=onespace.injected")) {
            mark(editor, "inject.onespace", "已注入", message, time);
        }
        if (message.contains("evt=scope.system.start")
                || message.contains("evt=scope.system.context.ready")
                || message.contains("evt=bt.a2dp.resolved")
                || message.contains("evt=codec.updated.hooks")
                || message.contains("evt=cdm.hooks")
                || message.contains("evt=codec.bt.")
                || message.contains("evt=le.bt.")) {
            mark(editor, "scope.bluetooth", "已加载", message, time);
        }
        if (message.contains("evt=system.bridge.registered")
                || message.contains("evt=codec.bt.receiver.registered")
                || message.contains("evt=codec.bt.reply")
                || message.contains("evt=codec.bt.set")
                || message.contains("evt=bt.a2dp.resolved")
                || message.contains("evt=codec.updated.hooks")
                || message.contains("evt=a2dp.setCodecConfigPreference")
                || message.contains("evt=cdm.bypass")) {
            mark(editor, "bridge.codec", "已注册", message, time);
        }
        if (message.contains("evt=le.bt.receiver.registered")
                || message.contains("evt=le.bt.")) {
            mark(editor, "bridge.le.bt", "已注册", message, time);
        }
        if (message.contains("evt=scope.wirelesssettings.start")
                || message.contains("evt=scope.wirelesssettings.context.ready")
                || message.contains("evt=le.ws.")) {
            mark(editor, "scope.wirelesssettings", "已加载", message, time);
        }
        if (message.contains("evt=le.ws.receiver.registered")
                || message.contains("evt=le.ws.")) {
            mark(editor, "bridge.le.ws", "已注册", message, time);
        }
        if (priority >= Log.WARN) {
            String key = priority >= Log.ERROR ? "last.error" : "last.warning";
            mark(editor, key, priority >= Log.ERROR ? "错误" : "警告", message, time);
        }
    }

    private static void mark(
            SharedPreferences.Editor editor, String key, String status, String detail, long time) {
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
}
