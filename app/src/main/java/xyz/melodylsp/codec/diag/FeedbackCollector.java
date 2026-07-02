package xyz.melodylsp.codec.diag;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import xyz.melodylsp.codec.BuildConfig;

public final class FeedbackCollector {

    private static final String[] PACKAGES = {
            BuildConfig.APPLICATION_ID,
            "com.oplus.melody",
            "com.android.bluetooth",
            "com.oplus.wirelesssettings"
    };

    private static final String[] STATUS_KEYS = {
            "scope.host",
            "host.controller",
            "hook.host",
            "inject.detail",
            "inject.onespace",
            "scope.bluetooth",
            "bridge.codec",
            "bridge.le.bt",
            "scope.wirelesssettings",
            "bridge.le.ws",
            "dexkit",
            "native.patch",
            "codec.write",
            "remember.write",
            "remember.replay",
            "last.warning",
            "last.error"
    };

    private static final String[] BLUETOOTH_LOG_PATTERNS = {
            "MelodyCodecLsp",
            "LSPosedFramework",
            "bluetooth-a2dp",
            "btif_a2dp",
            "soc_bta_av",
            "a2dp_vendor_lhdcv5",
            "a2dp_vendor_lhdcv5_encoder",
            "dexkit",
            "OplusA2dpStateMachineExtImpl",
            "setCodecConfigPreference",
            "quality_mode",
            "target bit rate",
            "max bit rate",
            "codec_specific_1",
            "lhdc.memory_patch",
            "remember.snapshot",
            "remember.set",
            "remember.write",
            "replay.bootstrap",
            "replay.schedule",
            "replay.dispatch",
            "replay.outcome",
            "replay.retry",
            "replay.stable",
            "replay.unstable",
            "write.timeout",
            "ignore target bitrate"
    };
    private static final String[] MODULE_LOG_PATTERNS = {
            "MelodyCodecLsp",
            "LSPosedFramework"
    };
    private static final String[] SU_CANDIDATES = {
            "su",
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/vendor/bin/su",
            "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/magisk/su"
    };
    private static final String MODULE_LOGCAT_COMMAND =
            "/system/bin/logcat -d -b all -t 20000 "
                    + "MelodyCodecLsp:V '*:S'";
    private static final String BLUETOOTH_LOGCAT_COMMAND =
            "/system/bin/logcat -d -b all -t 50000 "
                    + "MelodyCodecLsp:V "
                    + "bluetooth-a2dp:V soc_bta_av:V "
                    + "a2dp_vendor_lhdcv5:V a2dp_vendor_lhdcv5_encoder:V '*:S'";
    private static final int MAX_COMMAND_OUTPUT_CHARS = 4_000_000;

    private FeedbackCollector() {
    }

    public static String collect(Context context) throws Exception {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        String name = "OPlusHeadsetAudioHelper-feedback-" + stamp + ".zip";
        DiagnosticEvents.requestRememberedSnapshot(context);
        sleepQuietly(900L);
        OutputTarget target = openTarget(context, name);
        try {
            ZipOutputStream zip = new ZipOutputStream(target.stream);
            SharedPreferences diag = context.getSharedPreferences(
                    DiagnosticEvents.PREFS, Context.MODE_PRIVATE);
            write(zip, "summary.txt", buildSummary(context));
            write(zip, "diagnostics.txt", buildDiagnostics(diag));
            write(zip, "timeline.txt", diag.getString(DiagnosticEvents.KEY_EVENTS, ""));
            write(zip, "events.jsonl", diag.getString(DiagnosticEvents.KEY_EVENTS_JSON, ""));
            write(zip, "state.json", buildStateJson(context, diag));
            write(zip, "memory.txt", buildMemoryReport(diag));
            write(zip, "prefs.txt", buildPrefsDump(context, diag));

            String moduleLogcat = collectModuleLogcat();
            write(zip, "logcat-module.txt", moduleLogcat);
            write(zip, "logcat.txt", moduleLogcat);
            write(zip, "logcat-bluetooth-root.txt", collectBluetoothLogcatRoot());

            write(zip, "module-prop.txt", readResource(context,
                    "META-INF/xposed/module.prop"));
            write(zip, "scope-list.txt", readResource(context,
                    "META-INF/xposed/scope.list"));
            zip.close();
            target.finish(context);
            return target.displayPath;
        } catch (Throwable t) {
            target.abort(context);
            throw t;
        }
    }

    public static boolean hasRootAccess() {
        String result = runRootCommand("/system/bin/id", 3_000L);
        return !result.startsWith("root command failed:")
                && result.toLowerCase(Locale.ROOT).contains("uid=0");
    }

    private static OutputTarget openTarget(Context context, String name) throws Exception {
        File root = Environment.getExternalStorageDirectory();
        File file = new File(root, name);
        try {
            return new OutputTarget(new FileOutputStream(file), file.getAbsolutePath(), null);
        } catch (Throwable ignored) {
        }
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                OutputStream out = resolver.openOutputStream(uri);
                if (out != null) {
                    return new OutputTarget(out,
                            "/storage/emulated/0/Download/" + name, uri);
                }
            }
        }
        File dir = context.getExternalFilesDir(null);
        if (dir == null) dir = context.getFilesDir();
        File fallback = new File(dir, name);
        return new OutputTarget(new FileOutputStream(fallback),
                fallback.getAbsolutePath(), null);
    }

    private static String buildSummary(Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append("OPlus Headset Audio Helper feedback\n");
        sb.append("Generated: ").append(new Date()).append('\n');
        sb.append("Module: ").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")\n\n");
        sb.append("Device\n");
        sb.append("Brand: ").append(Build.BRAND).append('\n');
        sb.append("Manufacturer: ").append(Build.MANUFACTURER).append('\n');
        sb.append("Model: ").append(Build.MODEL).append('\n');
        sb.append("Device: ").append(Build.DEVICE).append('\n');
        sb.append("Product: ").append(Build.PRODUCT).append('\n');
        sb.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" / SDK ").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("Build: ").append(Build.DISPLAY).append('\n');
        sb.append("Fingerprint: ").append(Build.FINGERPRINT).append("\n\n");
        sb.append("Packages\n");
        for (String pkg : PACKAGES) {
            sb.append(pkg).append(": ").append(packageVersion(context, pkg)).append('\n');
        }
        sb.append('\n');
        SharedPreferences modulePrefs = context.getSharedPreferences(
                "module_prefs", Context.MODE_PRIVATE);
        sb.append("Module enabled: ").append(modulePrefs.getBoolean("enabled", true)).append('\n');
        sb.append("Launcher hidden: ")
                .append(modulePrefs.getBoolean("hide_launcher_icon", false)).append('\n');
        return sb.toString();
    }

    private static String buildDiagnostics(SharedPreferences sp) {
        StringBuilder sb = new StringBuilder();
        sb.append("Session\n");
        sb.append("ID: ").append(sp.getString(DiagnosticEvents.KEY_SESSION_ID, "-")).append('\n');
        sb.append("Started: ")
                .append(DiagnosticEvents.formatTime(
                        sp.getLong(DiagnosticEvents.KEY_SESSION_STARTED, 0L)))
                .append("\n\n");
        sb.append("Status\n");
        for (String key : STATUS_KEYS) {
            appendStatus(sb, sp, key, key);
        }
        sb.append("\nRemembered codec memory\n");
        sb.append(DiagnosticEvents.rememberedSummary(sp)).append('\n');
        sb.append("\nLast remembered replay chain\n");
        sb.append(DiagnosticEvents.replayChain(sp)).append('\n');
        sb.append("\nRaw diagnostics SharedPreferences\n");
        appendPrefs(sb, sp);
        sb.append("\nEvent ring\n");
        sb.append(sp.getString(DiagnosticEvents.KEY_EVENTS, ""));
        sb.append('\n');
        return sb.toString();
    }

    private static String buildMemoryReport(SharedPreferences diag) {
        StringBuilder sb = new StringBuilder();
        sb.append("Remembered codec memory\n");
        sb.append("Source: Melody private SharedPreferences snapshot mirrored by host hook.\n\n");
        sb.append(DiagnosticEvents.rememberedSummary(diag)).append("\n\n");
        sb.append("Last remembered replay chain\n");
        sb.append(DiagnosticEvents.replayChain(diag)).append('\n');
        return sb.toString();
    }

    private static String buildPrefsDump(Context context, SharedPreferences diag) {
        StringBuilder sb = new StringBuilder();
        sb.append("module_prefs\n");
        appendPrefs(sb, context.getSharedPreferences("module_prefs", Context.MODE_PRIVATE));
        sb.append("\n");
        sb.append("diagnostics\n");
        appendPrefs(sb, diag);
        sb.append("\n");
        sb.append("Note: host-app per-device remembered codec preferences live in the host app data. ")
                .append("The current values above are mirrored from Melody private prefs through ")
                .append("remember.snapshot.* events when the hook runs.\n");
        return sb.toString();
    }

    private static String buildStateJson(Context context, SharedPreferences sp) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        json(sb, "moduleVersion", BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")",
                true, 1);
        json(sb, "brand", Build.BRAND, true, 1);
        json(sb, "manufacturer", Build.MANUFACTURER, true, 1);
        json(sb, "model", Build.MODEL, true, 1);
        json(sb, "android", Build.VERSION.RELEASE + " / SDK " + Build.VERSION.SDK_INT, true, 1);
        json(sb, "build", Build.DISPLAY, true, 1);
        json(sb, "moduleEnabled", String.valueOf(context.getSharedPreferences(
                "module_prefs", Context.MODE_PRIVATE).getBoolean("enabled", true)), false, 1);
        json(sb, "launcherHidden", String.valueOf(context.getSharedPreferences(
                "module_prefs", Context.MODE_PRIVATE).getBoolean("hide_launcher_icon", false)),
                false, 1);
        sb.append("  \"statuses\": {\n");
        for (int i = 0; i < STATUS_KEYS.length; i++) {
            String key = STATUS_KEYS[i];
            sb.append("    \"").append(escape(key)).append("\": {");
            sb.append("\"status\":\"").append(escape(DiagnosticEvents.status(sp, key))).append("\",");
            sb.append("\"time\":\"").append(escape(DiagnosticEvents.formatTime(
                    DiagnosticEvents.time(sp, key)))).append("\",");
            sb.append("\"detail\":\"").append(escape(DiagnosticEvents.detail(sp, key))).append("\"");
            sb.append('}');
            sb.append(i + 1 < STATUS_KEYS.length ? ",\n" : "\n");
        }
        sb.append("  }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static void appendStatus(
            StringBuilder sb,
            SharedPreferences sp,
            String key,
            String label) {
        sb.append(label).append(": ")
                .append(DiagnosticEvents.status(sp, key))
                .append(" @ ")
                .append(DiagnosticEvents.formatTime(DiagnosticEvents.time(sp, key)));
        String detail = DiagnosticEvents.detail(sp, key);
        if (detail != null && !detail.isEmpty()) {
            sb.append(" | ").append(detail);
        }
        sb.append('\n');
    }

    private static void appendPrefs(StringBuilder sb, SharedPreferences sp) {
        Map<String, ?> values = new TreeMap<>(sp.getAll());
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            sb.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
    }

    private static String collectModuleLogcat() {
        String direct = runCommand(new String[]{
                "logcat", "-d", "-b", "all", "-t", "2000",
                "-s", "MelodyCodecLsp:V", "LSPosedFramework:I"
        });
        if (direct.trim().length() > 80) return direct;
        String rooted = runRootCommand(MODULE_LOGCAT_COMMAND);
        String rootFailure = rooted.startsWith("root command failed:") ? rooted : "";
        String filteredRooted = rootFailure.isEmpty() ? filterLog(rooted, MODULE_LOG_PATTERNS) : "";
        if (!filteredRooted.trim().isEmpty()) {
            return "direct logcat was empty; tagged su fallback used\n\n"
                    + filteredRooted;
        }
        String allRooted = runRootCommand("/system/bin/logcat -d -b all -t 12000");
        if (!allRooted.startsWith("root command failed:")) {
            String filtered = filterLog(allRooted, MODULE_LOG_PATTERNS);
            if (!filtered.trim().isEmpty()) {
                return "direct and tag-filtered root logcat were empty; "
                        + "filtered full root logcat used\n\n" + filtered;
            }
        }
        return "logcat unavailable from module app. Please also attach LSPosed module logs.\n\n"
                + direct
                + (rootFailure.isEmpty() ? "" : "\n--- root fallback ---\n" + rootFailure);
    }

    private static String collectBluetoothLogcatRoot() {
        String tagged = runRootCommand(BLUETOOTH_LOGCAT_COMMAND);
        if (!tagged.startsWith("root command failed:") && !tagged.trim().isEmpty()) {
            String filtered = filterLog(tagged, BLUETOOTH_LOG_PATTERNS);
            if (!filtered.trim().isEmpty()) {
                return filtered;
            }
        }

        String all = runRootCommand("/system/bin/logcat -d -b all -t 30000");
        if (all.startsWith("root command failed:")) {
            return "root logcat unavailable\n\n"
                    + (tagged.startsWith("root command failed:")
                    ? tagged + "\n\n--- full fallback ---\n" : "")
                    + all;
        }
        return filterLog(all, BLUETOOTH_LOG_PATTERNS);
    }

    private static String filterLog(String all, String[] patterns) {
        StringBuilder out = new StringBuilder();
        String[] lines = all.split("\\n");
        for (String line : lines) {
            for (String pattern : patterns) {
                if (line.contains(pattern) && keepMatchedLine(line, pattern)) {
                    out.append(line).append('\n');
                    break;
                }
            }
        }
        if (out.length() == 0) {
            return "root logcat succeeded, but no relevant bluetooth/module lines matched.\n";
        }
        return out.toString();
    }

    private static boolean keepMatchedLine(String line, String pattern) {
        if (!"LSPosedFramework".equals(pattern)) return true;
        return line.contains("MelodyCodecLsp") || line.contains(BuildConfig.APPLICATION_ID);
    }

    private static String runRootCommand(String command) {
        return runRootCommand(command, 10_000L);
    }

    private static String runRootCommand(String command, long timeoutMs) {
        StringBuilder failures = new StringBuilder();
        for (String su : SU_CANDIDATES) {
            String result = runCommand(new String[]{su, "-c", command}, timeoutMs);
            if (!looksLikeRootCommandFailure(result)) {
                return result;
            }
            failures.append("$ ").append(su).append(" -c ").append(command).append('\n')
                    .append(result).append('\n');
        }
        String shellResult = runCommand(new String[]{
                "/system/bin/sh",
                "-c",
                "PATH=/data/adb/ksu/bin:/data/adb/magisk:/system/bin:/system/xbin:/vendor/bin:/sbin:$PATH su -c \""
                        + shellEscape(command) + "\""
        }, timeoutMs);
        if (!looksLikeRootCommandFailure(shellResult)) {
            return shellResult;
        }
        failures.append("$ /system/bin/sh -c su -c ...\n").append(shellResult).append('\n');
        return "root command failed: no usable su was found or root access was denied\n\n"
                + failures;
    }

    private static boolean looksLikeRootCommandFailure(String result) {
        if (result == null) return true;
        if (looksLikeLogcatOutput(result)) return false;
        String lower = result.toLowerCase(Locale.ROOT);
        return lower.startsWith("command failed:")
                || lower.contains("cannot run program")
                || lower.contains("inaccessible or not found")
                || lower.contains("permission denied")
                || lower.contains("not allowed")
                || lower.contains("su: not found")
                || lower.contains("unknown option")
                || lower.contains("command timed out");
    }

    private static boolean looksLikeLogcatOutput(String result) {
        return result.contains("--------- beginning of ")
                || result.matches("(?s).*\\b\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d\\d\\d\\b.*");
    }

    private static String shellEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String runCommand(String[] command) {
        return runCommand(command, 10_000L);
    }

    private static String runCommand(String[] command, long timeoutMs) {
        Process process = null;
        StreamCollector out = null;
        StreamCollector err = null;
        try {
            process = Runtime.getRuntime().exec(command);
            out = new StreamCollector(process.getInputStream());
            err = new StreamCollector(process.getErrorStream());
            out.start();
            err.start();
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroy();
            }
            out.join(1000);
            err.join(1000);
            String stdout = out.text();
            String stderr = err.text();
            String suffix = stderr.isEmpty() ? "" : "\n--- stderr ---\n" + stderr;
            return stdout + (finished ? suffix : "\n(command timed out)\n" + suffix);
        } catch (Throwable t) {
            return "command failed: " + t + '\n';
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String readResource(Context context, String name) {
        try {
            InputStream in = context.getClassLoader().getResourceAsStream(name);
            if (in == null) return "missing: " + name + '\n';
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            in.close();
            return out.toString("UTF-8");
        } catch (Throwable t) {
            return "read failed: " + t + '\n';
        }
    }

    private static String packageVersion(Context context, String pkg) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(pkg, 0);
            long code = Build.VERSION.SDK_INT >= 28
                    ? info.getLongVersionCode() : info.versionCode;
            return String.valueOf(info.versionName) + " (" + code + ")";
        } catch (Throwable t) {
            return "not installed / unreadable";
        }
    }

    private static void write(ZipOutputStream zip, String name, String content) throws Exception {
        ZipEntry entry = new ZipEntry(name);
        zip.putNextEntry(entry);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void json(
            StringBuilder sb,
            String name,
            String value,
            boolean quoted,
            int indent) {
        for (int i = 0; i < indent; i++) sb.append("  ");
        sb.append('"').append(escape(name)).append('"').append(':');
        if (quoted) {
            sb.append('"').append(escape(value)).append('"');
        } else {
            sb.append(value);
        }
        sb.append(",\n");
    }

    private static String escape(String value) {
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

    private static final class OutputTarget {
        final OutputStream stream;
        final String displayPath;
        final Uri mediaUri;

        OutputTarget(OutputStream stream, String displayPath, Uri mediaUri) {
            this.stream = stream;
            this.displayPath = displayPath;
            this.mediaUri = mediaUri;
        }

        void finish(Context context) {
            if (mediaUri == null || Build.VERSION.SDK_INT < 29) return;
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            context.getContentResolver().update(mediaUri, values, null, null);
        }

        void abort(Context context) {
            try {
                stream.close();
            } catch (Throwable ignored) {
            }
            if (mediaUri != null && Build.VERSION.SDK_INT >= 29) {
                try {
                    context.getContentResolver().delete(mediaUri, null, null);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static final class StreamCollector extends Thread {
        private final InputStream in;
        private final StringBuilder text = new StringBuilder();

        StreamCollector(InputStream in) {
            super("OPlusHeadsetAudioHelper-stream");
            this.in = in;
        }

        @Override
        public void run() {
            try {
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while ((line = br.readLine()) != null) {
                    append(line);
                }
            } catch (Throwable ignored) {
            }
        }

        synchronized String text() {
            return text.toString();
        }

        private synchronized void append(String line) {
            text.append(line).append('\n');
            if (text.length() <= MAX_COMMAND_OUTPUT_CHARS) return;
            int trimTo = text.length() - MAX_COMMAND_OUTPUT_CHARS;
            int firstBreak = text.indexOf("\n", trimTo);
            if (firstBreak > 0 && firstBreak + 1 < text.length()) {
                text.delete(0, firstBreak + 1);
            } else {
                text.delete(0, trimTo);
            }
        }
    }
}
