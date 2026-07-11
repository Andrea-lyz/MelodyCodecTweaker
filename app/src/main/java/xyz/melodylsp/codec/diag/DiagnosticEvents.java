package xyz.melodylsp.codec.diag;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import xyz.melodylsp.codec.BuildConfig;
import xyz.melodylsp.codec.bridge.CodecSnapshot;
import xyz.melodylsp.codec.label.CodecLabelTable;
import xyz.melodylsp.codec.util.TrustedBroadcasts;

public final class DiagnosticEvents {

    public static final String ACTION = BuildConfig.APPLICATION_ID + ".action.DIAGNOSTIC_EVENT";
    public static final String ACTION_MEMORY_SNAPSHOT_REQUEST =
            BuildConfig.APPLICATION_ID + ".action.REQUEST_MEMORY_SNAPSHOT";
    public static final String PERMISSION_MEMORY_SNAPSHOT_REQUEST =
            BuildConfig.APPLICATION_ID + ".permission.DIAGNOSTIC_REQUEST";
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
    private static final int MAX_MEMORY_REPLAY_CHARS = 32_000;
    static final int MAX_MESSAGE_CHARS = 4_096;
    private static final int MAX_SCOPE_CHARS = 48;
    private static final long MAX_EVENT_TIME_SKEW_MS = 5 * 60_000L;
    private static final String KEY_MEMORY_DEVICE_LIST = "memory.devices";
    private static final String KEY_MEMORY_SNAPSHOT_TIME = "memory.snapshot.time";
    private static final String KEY_MEMORY_SNAPSHOT_REASON = "memory.snapshot.reason";
    private static final String KEY_MEMORY_SNAPSHOT_COUNT = "memory.snapshot.count";
    private static final String KEY_MEMORY_REPLAY_CHAIN = "memory.replay.chain";
    private static final String KEY_MEMORY_REPLAY_TIME = "memory.replay.time";

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
            intent.putExtra(EXTRA_MESSAGE, limit(message, MAX_MESSAGE_CHARS));
            intent.putExtra(EXTRA_TIME, time > 0L ? time : System.currentTimeMillis());
            TrustedBroadcasts.send(context, intent);
        } catch (Throwable ignored) {
        }
    }

    public static void requestRememberedSnapshot(Context context) {
        if (context == null) return;
        try {
            Intent intent = new Intent(ACTION_MEMORY_SNAPSHOT_REQUEST);
            intent.setPackage("com.oplus.melody");
            context.sendOrderedBroadcast(intent, null);
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
        record(context, intent, null);
    }

    static void record(Context context, Intent intent, String verifiedScope) {
        if (context == null || intent == null) return;
        String message = intent.getStringExtra(EXTRA_MESSAGE);
        if (message == null || message.isEmpty()) return;
        message = limit(message, MAX_MESSAGE_CHARS);
        String scope = verifiedScope != null
                ? verifiedScope
                : limit(intent.getStringExtra(EXTRA_SCOPE), MAX_SCOPE_CHARS);
        int priority = intent.getIntExtra(EXTRA_PRIORITY, Log.INFO);
        if (priority < Log.INFO) priority = Log.INFO;
        if (priority > Log.ASSERT) priority = Log.ASSERT;
        long now = System.currentTimeMillis();
        long time = intent.getLongExtra(EXTRA_TIME, System.currentTimeMillis());
        if (time <= 0L
                || time < now - MAX_EVENT_TIME_SKEW_MS
                || time > now + MAX_EVENT_TIME_SKEW_MS) {
            time = now;
        }
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
        mirrorMemory(sp, editor, message, time);
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

    public static String rememberedSummary(SharedPreferences sp) {
        String devices = sp.getString(KEY_MEMORY_DEVICE_LIST, "");
        long snapshotTime = sp.getLong(KEY_MEMORY_SNAPSHOT_TIME, 0L);
        String reason = sp.getString(KEY_MEMORY_SNAPSHOT_REASON, "");
        int snapshotCount = sp.getInt(KEY_MEMORY_SNAPSHOT_COUNT, -1);
        if (devices == null || devices.trim().isEmpty()) {
            if (snapshotTime > 0L && snapshotCount == 0) {
                StringBuilder empty = new StringBuilder();
                empty.append("来源：Melody 私有 SharedPreferences @ ")
                        .append(formatTime(snapshotTime));
                if (reason != null && !reason.isEmpty()) empty.append(" / ").append(reason);
                empty.append("\nMelody prefs 当前没有任何耳机记忆记录。");
                return empty.toString();
            }
            return "暂未收到 Melody 私有数据里的真实记忆快照。请先打开/重启无线耳机 App，让 hook 进程启动；也可以点刷新后等待一两秒。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("来源：Melody 私有 SharedPreferences");
        if (snapshotTime > 0L) sb.append(" @ ").append(formatTime(snapshotTime));
        if (reason != null && !reason.isEmpty()) sb.append(" / ").append(reason);
        String[] suffixes = devices.split("\\n");
        for (String suffix : suffixes) {
            if (suffix == null || suffix.trim().isEmpty()) continue;
            String prefix = memoryPrefix(suffix.trim());
            String mac = sp.getString(prefix + "mac", suffix.trim());
            boolean remembered = sp.getBoolean(prefix + "remembered", false);
            boolean hasSnapshot = sp.getBoolean(prefix + "hasSnapshot", false);
            long updated = sp.getLong(prefix + "time", 0L);
            sb.append("\n\n").append(mac).append("：");
            if (!remembered) {
                sb.append("记忆关闭");
                if (updated > 0L) sb.append(" @ ").append(formatTime(updated));
                continue;
            }
            if (!hasSnapshot || !sp.contains(prefix + "codec")) {
                sb.append("记忆已开启，但 Melody prefs 里缺少具体挡位快照");
                if (updated > 0L) sb.append(" @ ").append(formatTime(updated));
                continue;
            }
            int codec = sp.getInt(prefix + "codec", -1);
            long specific1 = sp.getLong(prefix + "specific1", -1L);
            int rate = sp.getInt(prefix + "rate", -1);
            sb.append(memoryValueLabel(codec, specific1, rate));
            if (updated > 0L) sb.append("\n  更新时间：").append(formatTime(updated));
            sb.append("\n  raw: codec=").append(codec)
                    .append(" specific1=").append(specific1)
                    .append(" rate=0x").append(Integer.toHexString(rate));
        }
        return sb.length() > 0 ? sb.toString() : "暂无记忆镜像。";
    }

    public static String replayChain(SharedPreferences sp) {
        String chain = sp.getString(KEY_MEMORY_REPLAY_CHAIN, "");
        if (chain == null || chain.trim().isEmpty()) {
            return "暂无记忆恢复链路。下次耳机连接、重启后自动连接或手动恢复时会记录。";
        }
        return chain.trim();
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
        if (message.contains("evt=dexkit.")) {
            mark(editor, "dexkit", stateFromMessage(message), message, time);
        }
        if (message.contains("evt=write.")
                || message.contains("evt=a2dp.setCodecConfigPreference")
                || message.contains("evt=bt.native.setCodecConfigPreference")) {
            mark(editor, "codec.write", stateFromMessage(message), message, time);
        }
        if (message.contains("evt=remember.write")
                || message.contains("evt=remember.set")
                || message.contains("evt=remember.snapshot.")) {
            mark(editor, "remember.write", stateFromMessage(message), message, time);
        }
        if (message.contains("evt=replay.")) {
            mark(editor, "remember.replay", stateFromMessage(message), message, time);
        }
        if (message.contains("evt=game.mode.")
                || message.contains("evt=replay.suppress.game")) {
            mark(editor, "game.mode", stateFromMessage(message), message, time);
        }
        if (priority >= Log.WARN && !isRecoverableFallbackWarning(message)) {
            String key = priority >= Log.ERROR ? "last.error" : "last.warning";
            mark(editor, key, priority >= Log.ERROR ? "error" : "warning", message, time);
        }
    }

    private static void mirrorMemory(
            SharedPreferences sp,
            SharedPreferences.Editor editor,
            String message,
            long time) {
        String event = eventName(message);
        if (event == null || event.isEmpty()) return;
        if ("remember.snapshot.begin".equals(event)) {
            mirrorRememberSnapshotBegin(sp, editor, message, time);
        } else if ("remember.snapshot.item".equals(event)) {
            mirrorRememberSnapshotItem(sp, editor, message, time);
        } else if ("remember.snapshot.end".equals(event)) {
            mirrorRememberSnapshotEnd(editor, message, time);
        }
        if (event.startsWith("replay.")) {
            mirrorReplayChain(sp, editor, event, message, time);
        }
    }

    private static void mirrorRememberSnapshotBegin(
            SharedPreferences sp,
            SharedPreferences.Editor editor,
            String message,
            long time) {
        clearRememberedSnapshot(sp, editor);
        Integer count = intValue(valueOf(message, "count"));
        editor.putLong(KEY_MEMORY_SNAPSHOT_TIME, time)
                .putString(KEY_MEMORY_SNAPSHOT_REASON,
                        "reason=" + safe(valueOf(message, "reason")))
                .putInt(KEY_MEMORY_SNAPSHOT_COUNT, count != null ? count : 0);
    }

    private static void mirrorRememberSnapshotItem(
            SharedPreferences sp,
            SharedPreferences.Editor editor,
            String message,
            long time) {
        String mac = valueOf(message, "mac");
        if (mac == null || mac.isEmpty()) return;
        String suffix = memorySuffix(mac);
        rememberDevice(sp, editor, suffix);
        String prefix = memoryPrefix(suffix);
        boolean remembered = Boolean.parseBoolean(String.valueOf(valueOf(message, "remembered")));
        boolean hasSnapshot = Boolean.parseBoolean(String.valueOf(valueOf(message, "hasSnapshot")));
        editor.putString(prefix + "mac", mac)
                .putBoolean(prefix + "remembered", remembered)
                .putBoolean(prefix + "hasSnapshot", hasSnapshot)
                .putLong(prefix + "time", time);

        Integer codec = intValue(valueOf(message, "codec"));
        Long specific1 = longValue(valueOf(message, "specific1"));
        Integer rate = intValue(valueOf(message, "rate"));
        if (remembered && hasSnapshot && codec != null && specific1 != null && rate != null) {
            editor.putInt(prefix + "codec", codec)
                    .putLong(prefix + "specific1", specific1)
                    .putInt(prefix + "rate", rate);
        } else {
            editor.remove(prefix + "codec")
                    .remove(prefix + "specific1")
                    .remove(prefix + "rate");
        }
    }

    private static void mirrorRememberSnapshotEnd(
            SharedPreferences.Editor editor,
            String message,
            long time) {
        Integer count = intValue(valueOf(message, "count"));
        editor.putLong(KEY_MEMORY_SNAPSHOT_TIME, time)
                .putString(KEY_MEMORY_SNAPSHOT_REASON,
                        "reason=" + safe(valueOf(message, "reason")))
                .putInt(KEY_MEMORY_SNAPSHOT_COUNT, count != null ? count : 0);
    }

    private static void clearRememberedSnapshot(
            SharedPreferences sp,
            SharedPreferences.Editor editor) {
        String devices = sp.getString(KEY_MEMORY_DEVICE_LIST, "");
        if (devices != null && !devices.isEmpty()) {
            String[] suffixes = devices.split("\\n");
            for (String suffix : suffixes) {
                if (suffix == null || suffix.trim().isEmpty()) continue;
                String prefix = memoryPrefix(suffix.trim());
                editor.remove(prefix + "mac")
                        .remove(prefix + "remembered")
                        .remove(prefix + "hasSnapshot")
                        .remove(prefix + "codec")
                        .remove(prefix + "specific1")
                        .remove(prefix + "rate")
                        .remove(prefix + "time");
            }
        }
        editor.remove(KEY_MEMORY_DEVICE_LIST);
    }

    private static void mirrorReplayChain(
            SharedPreferences sp,
            SharedPreferences.Editor editor,
            String event,
            String message,
            long time) {
        String entry = formatTime(time) + " " + stripEventPrefix(message);
        String old = sp.getString(KEY_MEMORY_REPLAY_CHAIN, "");
        long lastTime = sp.getLong(KEY_MEMORY_REPLAY_TIME, 0L);
        boolean expired = lastTime <= 0L || Math.abs(time - lastTime) > 30_000L;
        boolean startEvent = "replay.bootstrap.scan".equals(event)
                || "replay.schedule".equals(event)
                || "replay.pending_ready".equals(event)
                || "replay.suppress.game_active".equals(event);
        String merged = expired && startEvent
                ? entry
                : (old == null || old.isEmpty() ? entry : old + '\n' + entry);
        editor.putString(KEY_MEMORY_REPLAY_CHAIN, trimRing(merged, MAX_MEMORY_REPLAY_CHARS))
                .putLong(KEY_MEMORY_REPLAY_TIME, time);
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

    private static String limit(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) return value;
        return value.substring(0, maxChars);
    }

    private static String valueOf(String message, String key) {
        if (message == null || key == null || key.isEmpty()) return null;
        String marker = key + "=";
        int start = message.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        int end = message.indexOf(' ', start);
        return end > start ? message.substring(start, end) : message.substring(start);
    }

    private static Integer intValue(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return Integer.decode(value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Long longValue(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return Long.decode(value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void rememberDevice(
            SharedPreferences sp,
            SharedPreferences.Editor editor,
            String suffix) {
        String old = sp.getString(KEY_MEMORY_DEVICE_LIST, "");
        if (containsLine(old, suffix)) return;
        editor.putString(KEY_MEMORY_DEVICE_LIST,
                old == null || old.isEmpty() ? suffix : old + '\n' + suffix);
    }

    private static boolean containsLine(String lines, String target) {
        if (lines == null || lines.isEmpty()) return false;
        String[] split = lines.split("\\n");
        for (String item : split) {
            if (target.equals(item)) return true;
        }
        return false;
    }

    private static String memorySuffix(String mac) {
        String safeMac = safe(mac);
        StringBuilder sb = new StringBuilder(safeMac.length());
        for (int i = 0; i < safeMac.length(); i++) {
            char c = safeMac.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.length() > 0 ? sb.toString() : "unknown";
    }

    private static String memoryPrefix(String suffix) {
        return "memory.device." + suffix + ".";
    }

    private static String memoryValueLabel(int codec, long specific1, int rate) {
        String codecLabel = CodecLabelTable.codecLabel(null, codec, specific1);
        String qualityLabel = qualityLabel(codec, specific1);
        String rateLabel = sampleRateLabel(rate);
        return codecLabel + " / " + qualityLabel + " / " + rateLabel;
    }

    private static String qualityLabel(int codec, long specific1) {
        if (CodecLabelTable.isKnownQuality(codec, specific1)) {
            return CodecLabelTable.qualityLabel(null, codec, specific1);
        }
        if (specific1 == 0L) return "默认/自适应档位";
        return "specific1=" + specific1 + " (0x" + Long.toHexString(specific1) + ")";
    }

    private static String sampleRateLabel(int rateMask) {
        if (rateMask <= 0) return "系统默认采样率";
        int[] rates = CodecSnapshot.decodeSampleRateBits(rateMask);
        if (rates.length == 1) {
            return CodecLabelTable.sampleRateLabel(rates[0]);
        }
        if (rates.length > 1) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < rates.length; i++) {
                if (i > 0) sb.append('/');
                sb.append(CodecLabelTable.sampleRateLabel(rates[i]));
            }
            return sb.toString();
        }
        return "采样率 bit=0x" + Integer.toHexString(rateMask);
    }

    private static String stripEventPrefix(String message) {
        if (message == null) return "";
        int start = message.indexOf("evt=");
        return start >= 0 ? message.substring(start) : message;
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
                || message.contains("evt=replay.already_applied")
                || message.contains("evt=replay.cancel.user_write")
                || message.contains("evt=replay.skip.user_write_quiet")
                || message.contains("evt=replay.stale.user_write")
                || message.contains("evt=replay.suppress.game_exit")
                || message.contains("evt=replay.suppress.game_probe_exit")
                || message.contains("evt=replay.suppress.game_fallback_expired")
                || (message.contains("evt=game.mode.state")
                && message.contains("active=false")
                && !message.contains("suppressed=true"))
                || message.contains("evt=dexkit.bridge.created")
                || message.contains("evt=dexkit.bridge.closed")
                || message.contains("evt=dexkit.native.loaded")
                || message.contains("evt=dexkit.find.classes")
                || message.contains("evt=remember.write.delayed_confirmed")
                || message.contains("status=patched")
                || message.contains("status=already_patched")) {
            return "ok";
        }
        if (message.contains("skip")
                || message.contains("pending")
                || message.contains("evt=replay.suppress.game_active")
                || message.contains("evt=game.mode.fallback.expiry_probe")
                || message.contains("evt=game.mode.probe.active")
                || message.contains("evt=game.mode.probe.unavailable")
                || (message.contains("evt=game.mode.state")
                && message.contains("active=false")
                && message.contains("suppressed=true"))
                || (message.contains("evt=game.mode.state")
                && message.contains("active=true"))
                || message.contains("evt=dexkit.find.empty")
                || message.contains("waiting")) {
            return "pending";
        }
        if (message.contains("failed")
                || message.contains("FAILED")
                || message.contains("TIMEOUT")
                || message.contains("evt=replay.unstable")
                || message.contains("evt=dexkit.unavailable")
                || message.contains("unsupported")
                || message.contains("success=false")) {
            return "attention";
        }
        return "seen";
    }

    private static boolean isRecoverableFallbackWarning(String message) {
        return message.contains("Path-A setCodec failed")
                || message.contains("Path-A setOptionalCodecs failed")
                || message.contains("Path-A accepted but not confirmed")
                || message.contains("Path-A LHDC accepted but not confirmed")
                || message.contains("Path-A optional codecs accepted but not confirmed");
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
