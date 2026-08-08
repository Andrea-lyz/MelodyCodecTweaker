package xyz.melodylsp.codec.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import xyz.melodylsp.codec.BuildConfig;
import xyz.melodylsp.codec.bridge.CodecIpc;
import xyz.melodylsp.codec.diag.DiagnosticEvents;
import xyz.melodylsp.codec.diag.FeedbackCollector;
import xyz.melodylsp.codec.diag.RootBluetoothLogCapture;
import xyz.melodylsp.codec.util.TrustedBroadcasts;

/** Hosts the diagnostics-v2 HTML design and supplies its read-only foreground snapshot bridge. */
public final class MasterSwitchActivity extends Activity {

    private static final String PREFS_NAME = "module_prefs";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_HIDE_LAUNCHER_ICON = "hide_launcher_icon";
    private static final String LAUNCHER_ALIAS =
            "xyz.melodylsp.codec.ui.LauncherActivity";
    private static final String DIAGNOSTICS_URL =
            "file:///android_asset/diagnostics-v2.html";
    private static final int LIGHT_BG = 0xFFF6F7FB;
    private static final int DARK_BG = 0xFF0F1218;
    private static final long[] RECORDING_CONTROL_RETRY_DELAYS_MS = {
            500L, 2_000L, 5_000L
    };

    private static final String[][] STATUS_ROWS = {
            {"无线耳机作用域", "scope.host"},
            {"Host 控制器", "host.controller"},
            {"页面 Hook", "hook.host"},
            {"DetailMain 注入", "inject.detail"},
            {"OneSpace 注入", "inject.onespace"},
            {"蓝牙作用域", "scope.bluetooth"},
            {"A2DP Bridge", "bridge.codec"},
            {"LE Audio 蓝牙桥", "bridge.le.bt"},
            {"无线设置作用域", "scope.wirelesssettings"},
            {"LE Audio 无线设置桥", "bridge.le.ws"},
            {"Native 内存补丁", "native.patch"},
            {"root 蓝牙持续日志", "diag.root.capture"},
            {"LHDC BQR Hook", "lhdc.link.bqr"},
            {"LHDC choppy Hook", "lhdc.link.choppy"},
            {"LHDC queue Hook", "lhdc.link.queue"},
            {"BQR 影子保护", "lhdc.link.shadow"},
            {"最近写入", "codec.write"},
            {"记忆写入", "remember.write"},
            {"重连重放", "remember.replay"},
            {"最近警告", "last.warning"},
            {"最近错误", "last.error"},
    };

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LiveBqrStore liveBqrStore = new LiveBqrStore();
    private SharedPreferences modulePrefs;
    private SharedPreferences diagPrefs;
    private WebView webView;
    private boolean pageReady;
    private volatile boolean foreground;
    private boolean liveReceiverRegistered;
    private volatile boolean feedbackBusy;
    private JSONObject cachedEnvironment;

    private final BroadcastReceiver liveBqrReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null
                    || !CodecIpc.ACTION_LHDC_DIAGNOSTIC_LIVE_SAMPLE.equals(intent.getAction())
                    || !CodecIpc.TOKEN.equals(intent.getStringExtra(CodecIpc.EXTRA_TOKEN))) {
                return;
            }
            if (TrustedBroadcasts.supportsSenderIdentity()
                    && !TrustedBroadcasts.isTrustedSender(
                    context,
                    TrustedBroadcasts.captureSender(this),
                    CodecIpc.BLUETOOTH_PKG)) {
                return;
            }
            String payload = intent.getStringExtra(
                    CodecIpc.EXTRA_DIAGNOSTIC_LIVE_PAYLOAD);
            if (payload == null || payload.isEmpty()) return;
            try {
                liveBqrStore.accept(new JSONObject(payload));
                if (foreground) pushSnapshotToPage();
            } catch (Throwable ignored) {
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        modulePrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        diagPrefs = getSharedPreferences(DiagnosticEvents.PREFS, Context.MODE_PRIVATE);
        DiagnosticEvents.reconcileReceiverState(this);
        applyLauncherIconState(modulePrefs.getBoolean(KEY_HIDE_LAUNCHER_ICON, false), false);
        cachedEnvironment = buildEnvironmentSnapshot();
        configureSystemBars();
        setContentView(createWebView());
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        DiagnosticEvents.refreshRecordingState(this);
        registerLiveBqrReceiver();
        sendLiveSubscription(true);
        if (webView != null) webView.onResume();
        evaluate("window.MelodyDiagnostics&&window.MelodyDiagnostics.start()");
        pushSnapshotToPage();
    }

    @Override
    protected void onPause() {
        foreground = false;
        evaluate("window.MelodyDiagnostics&&window.MelodyDiagnostics.stop()");
        sendLiveSubscription(false);
        unregisterLiveBqrReceiver();
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        foreground = false;
        sendLiveSubscription(false);
        unregisterLiveBqrReceiver();
        mainHandler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.removeJavascriptInterface("MelodyDiagBridge");
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private void configureSystemBars() {
        boolean dark = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int color = dark ? DARK_BG : LIGHT_BG;
        getWindow().setStatusBarColor(color);
        getWindow().setNavigationBarColor(color);
        if (!dark) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private WebView createWebView() {
        WebView view = new WebView(this);
        view.setBackgroundColor(LIGHT_BG);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setDatabaseEnabled(false);
        settings.setGeolocationEnabled(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccess(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        view.addJavascriptInterface(new DiagnosticsBridge(), "MelodyDiagBridge");
        view.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView webView, String url) {
                pageReady = DIAGNOSTICS_URL.equals(url);
                if (pageReady && foreground) {
                    evaluate("window.MelodyDiagnostics&&window.MelodyDiagnostics.start()");
                    pushSnapshotToPage();
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request != null ? request.getUrl() : null;
                return uri == null || !DIAGNOSTICS_URL.equals(uri.toString());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return url == null || !DIAGNOSTICS_URL.equals(url);
            }
        });
        webView = view;
        view.loadUrl(DIAGNOSTICS_URL);
        return view;
    }

    private void registerLiveBqrReceiver() {
        if (liveReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(CodecIpc.ACTION_LHDC_DIAGNOSTIC_LIVE_SAMPLE);
        liveReceiverRegistered = TrustedBroadcasts.registerExportedReceiver(
                this,
                liveBqrReceiver,
                filter,
                TrustedBroadcasts.PERMISSION_BLUETOOTH_PRIVILEGED,
                mainHandler);
    }

    private void unregisterLiveBqrReceiver() {
        if (!liveReceiverRegistered) return;
        try {
            unregisterReceiver(liveBqrReceiver);
        } catch (Throwable ignored) {
        }
        liveReceiverRegistered = false;
    }

    private void sendLiveSubscription(boolean enabled) {
        if (enabled && !foreground) return;
        Intent intent = new Intent(CodecIpc.ACTION_LHDC_DIAGNOSTIC_LIVE_CONTROL);
        intent.setPackage(CodecIpc.BLUETOOTH_PKG);
        intent.putExtra(CodecIpc.EXTRA_TOKEN, CodecIpc.TOKEN);
        intent.putExtra(CodecIpc.EXTRA_DIAGNOSTIC_LIVE_ENABLED, enabled);
        TrustedBroadcasts.send(this, intent);
    }

    private void pushSnapshotToPage() {
        if (!foreground || !pageReady || webView == null) return;
        String json = buildSnapshotJson().toString();
        evaluate("window.MelodyDiagnostics&&window.MelodyDiagnostics.applyJson("
                + JSONObject.quote(json) + ")");
    }

    private void evaluate(String script) {
        WebView view = webView;
        if (view == null || !pageReady) return;
        view.post(() -> {
            WebView current = webView;
            if (current != null) current.evaluateJavascript(script, null);
        });
    }

    private JSONObject buildSnapshotJson() {
        JSONObject root = new JSONObject();
        try {
            root.put("enabled", modulePrefs.getBoolean(KEY_ENABLED, true));
            root.put("hideLauncherIcon",
                    modulePrefs.getBoolean(KEY_HIDE_LAUNCHER_ICON, false));
            root.put("snapshotTakenAt", System.currentTimeMillis());
            root.put("recording", buildRecordingSnapshot());
            root.put("environment", cachedEnvironment != null
                    ? cachedEnvironment : buildEnvironmentSnapshot());
            root.put("statuses", buildStatusSnapshot());
            root.put("memory", buildMemorySnapshot());
            root.put("events", buildRecentEventsSnapshot());
            root.put("bqr", liveBqrStore.snapshot());
            root.put("feedbackBusy", feedbackBusy);
        } catch (Throwable ignored) {
        }
        return root;
    }

    private JSONObject buildRecordingSnapshot() throws Exception {
        JSONObject out = new JSONObject();
        out.put("id", valueOrEmpty(diagPrefs.getString(DiagnosticEvents.KEY_SESSION_ID, "")));
        out.put("startedAt", diagPrefs.getLong(DiagnosticEvents.KEY_SESSION_STARTED, 0L));
        out.put("expiresAt", diagPrefs.getLong(DiagnosticEvents.KEY_SESSION_EXPIRES, 0L));
        out.put("endedAt", diagPrefs.getLong(DiagnosticEvents.KEY_SESSION_ENDED, 0L));
        out.put("active", DiagnosticEvents.isRecording(this));
        return out;
    }

    private JSONObject buildEnvironmentSnapshot() {
        JSONObject out = new JSONObject();
        try {
            out.put("module", BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
            out.put("system", Build.MANUFACTURER + " " + Build.MODEL
                    + " / Android " + Build.VERSION.RELEASE);
            out.put("melody", packageVersion(CodecIpc.MELODY_PKG));
            out.put("bluetooth", packageVersion(CodecIpc.BLUETOOTH_PKG));
            out.put("wirelessSettings", packageVersion("com.oplus.wirelesssettings"));
        } catch (Throwable ignored) {
        }
        return out;
    }

    private JSONArray buildStatusSnapshot() {
        JSONArray rows = new JSONArray();
        for (String[] entry : STATUS_ROWS) {
            try {
                String key = entry[1];
                JSONObject row = new JSONObject();
                row.put("label", entry[0]);
                row.put("key", key);
                row.put("status", DiagnosticEvents.status(diagPrefs, key));
                row.put("detail", valueOrEmpty(DiagnosticEvents.detail(diagPrefs, key)));
                row.put("time", DiagnosticEvents.time(diagPrefs, key));
                rows.put(row);
            } catch (Throwable ignored) {
            }
        }
        return rows;
    }

    private JSONObject buildMemorySnapshot() throws Exception {
        JSONObject out = new JSONObject();
        out.put("remembered", DiagnosticEvents.rememberedSummary(diagPrefs));
        out.put("replay", tailLines(DiagnosticEvents.replayChain(diagPrefs), 24));
        return out;
    }

    private JSONArray buildRecentEventsSnapshot() {
        JSONArray out = new JSONArray();
        String raw = diagPrefs.getString(DiagnosticEvents.KEY_EVENTS, "");
        if (raw == null || raw.trim().isEmpty()) return out;
        String[] lines = raw.split("\\n");
        for (int i = lines.length - 1; i >= 0 && out.length() < 12; i--) {
            String line = lines[i];
            if (line == null || line.trim().isEmpty()) continue;
            try {
                JSONObject event = new JSONObject();
                int open = line.indexOf('[');
                int close = line.indexOf(']', open + 1);
                String time = open > 6 ? line.substring(6, open).trim() : "";
                String header = open >= 0 && close > open
                        ? line.substring(open + 1, close) : "";
                int slash = header.lastIndexOf('/');
                String level = slash >= 0 ? header.substring(slash + 1) : "I";
                String message = close >= 0 && close + 1 < line.length()
                        ? line.substring(close + 1).trim() : line;
                event.put("time", time);
                event.put("level", level);
                event.put("message", message);
                out.put(event);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private final class DiagnosticsBridge {
        @JavascriptInterface
        public String refresh() {
            if (foreground) sendLiveSubscription(true);
            return buildSnapshotJson().toString();
        }

        @JavascriptInterface
        public void setEnabled(boolean enabled) {
            runOnUiThread(() -> {
                modulePrefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
                pushSnapshotToPage();
            });
        }

        @JavascriptInterface
        public void setHideLauncherIcon(boolean hidden) {
            runOnUiThread(() -> {
                modulePrefs.edit().putBoolean(KEY_HIDE_LAUNCHER_ICON, hidden).apply();
                boolean applied = applyLauncherIconState(hidden, true);
                Toast.makeText(MasterSwitchActivity.this,
                        applied
                                ? (hidden ? "桌面图标已隐藏" : "桌面图标已恢复")
                                : "桌面图标状态更新失败",
                        Toast.LENGTH_SHORT).show();
                pushSnapshotToPage();
            });
        }

        @JavascriptInterface
        public void toggleRecording() {
            runOnUiThread(() -> {
                if (DiagnosticEvents.isRecording(MasterSwitchActivity.this)) {
                    DiagnosticEvents.stopSession(MasterSwitchActivity.this, "user");
                    Toast.makeText(MasterSwitchActivity.this,
                            "问题记录已停止", Toast.LENGTH_SHORT).show();
                    pushSnapshotToPage();
                } else {
                    startRecordSession();
                }
            });
        }

        @JavascriptInterface
        public void generateFeedback() {
            runOnUiThread(MasterSwitchActivity.this::collectFeedback);
        }

        @JavascriptInterface
        public void requestMemorySnapshot() {
            runOnUiThread(MasterSwitchActivity.this::requestRememberedSnapshot);
        }
    }

    private void startRecordSession() {
        if (feedbackBusy) return;
        feedbackBusy = true;
        pushSnapshotToPage();
        Toast.makeText(this, "正在检测 root 权限...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            Context appContext = getApplicationContext();
            boolean rootGranted;
            try {
                rootGranted = FeedbackCollector.hasRootAccess();
            } catch (Throwable t) {
                rootGranted = false;
            }
            if (!rootGranted) {
                runOnUiThread(() -> {
                    feedbackBusy = false;
                    Toast.makeText(MasterSwitchActivity.this,
                            "记录问题需要授予模块 root 权限，请在 Root 管理器授权后重试",
                            Toast.LENGTH_LONG).show();
                    pushSnapshotToPage();
                });
                return;
            }
            // prepare -> startRootCapture -> commit: an uncommitted session must never look
            // packageable, and a failed capture must not leave a half-open session behind.
            String id = DiagnosticEvents.prepareSessionId();
            RootBluetoothLogCapture.StartResult captureResult =
                    RootBluetoothLogCapture.start(appContext, id);
            if (captureResult == null || !captureResult.started) {
                RootBluetoothLogCapture.retireSession(appContext, id, "capture_failed");
                runOnUiThread(() -> {
                    feedbackBusy = false;
                    Toast.makeText(MasterSwitchActivity.this,
                            "root 蓝牙日志启动失败，未开始记录："
                                    + (captureResult == null ? "未知错误" : captureResult.detail),
                            Toast.LENGTH_LONG).show();
                    pushSnapshotToPage();
                });
                return;
            }
            DiagnosticEvents.commitSession(appContext, id);
            runOnUiThread(() -> {
                feedbackBusy = false;
                scheduleRecordingControlRetries();
                Toast.makeText(MasterSwitchActivity.this,
                        "已开始记录：" + id + "（root 蓝牙日志持续采集中）",
                        Toast.LENGTH_SHORT).show();
                pushSnapshotToPage();
            });
        }, "OPlusHeadsetAudioHelper-root-check").start();
    }

    private void scheduleRecordingControlRetries() {
        for (long delayMs : RECORDING_CONTROL_RETRY_DELAYS_MS) {
            mainHandler.postDelayed(() -> {
                if (foreground) DiagnosticEvents.refreshRecordingState(this);
            }, delayMs);
        }
    }

    private void collectFeedback() {
        if (feedbackBusy) return;
        feedbackBusy = true;
        pushSnapshotToPage();
        new Thread(() -> {
            String path;
            Throwable error = null;
            try {
                path = FeedbackCollector.collect(this);
            } catch (Throwable t) {
                path = null;
                error = t;
            }
            String finalPath = path;
            Throwable finalError = error;
            runOnUiThread(() -> {
                feedbackBusy = false;
                if (finalError == null) {
                    Toast.makeText(this, "反馈包已保存：" + finalPath,
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "打包失败：" + finalError.getMessage(),
                            Toast.LENGTH_LONG).show();
                }
                pushSnapshotToPage();
            });
        }, "OPlusHeadsetAudioHelper-feedback").start();
    }

    private void requestRememberedSnapshot() {
        DiagnosticEvents.requestRememberedSnapshot(this);
        mainHandler.postDelayed(this::pushSnapshotToPage, 900L);
    }

    private boolean applyLauncherIconState(boolean hidden, boolean notifyLauncher) {
        try {
            ComponentName alias = new ComponentName(this, LAUNCHER_ALIAS);
            int state = hidden
                    ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
            int flags = PackageManager.DONT_KILL_APP | PackageManager.SYNCHRONOUS;
            getPackageManager().setComponentEnabledSetting(alias, state, flags);
            if (notifyLauncher) notifyLauncherChanged(alias);
            return true;
        } catch (Throwable t) {
            Toast.makeText(this, "无法更新桌面图标状态：" + t.getMessage(),
                    Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void notifyLauncherChanged(ComponentName alias) {
        try {
            Intent changed = new Intent(Intent.ACTION_PACKAGE_CHANGED);
            changed.setData(Uri.fromParts("package", getPackageName(), null));
            changed.putExtra(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST,
                    new String[]{alias.flattenToString()});
            changed.putExtra(Intent.EXTRA_DONT_KILL_APP, true);
            String launcher = defaultLauncherPackage();
            if (!launcher.isEmpty()) changed.setPackage(launcher);
            sendBroadcast(changed);
        } catch (Throwable ignored) {
        }
    }

    private String defaultLauncherPackage() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo info = getPackageManager().resolveActivity(home, 0);
            return info != null && info.activityInfo != null
                    ? valueOrEmpty(info.activityInfo.packageName) : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String packageVersion(String pkg) {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(pkg, 0);
            return String.valueOf(info.versionName) + " (" + info.getLongVersionCode() + ")";
        } catch (Throwable t) {
            return "未安装 / 不可读取";
        }
    }

    private static String tailLines(String text, int maxLines) {
        if (text == null || text.trim().isEmpty()) {
            return "尚未采集到诊断事件；这不代表模块未生效。";
        }
        String[] lines = text.split("\\n");
        int start = Math.max(0, lines.length - maxLines);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static final class LiveBqrStore {
        private static final int MAX_SAMPLES = 320;
        private final ArrayDeque<JSONObject> samples = new ArrayDeque<>();
        private final Map<String, Integer> reasonCounts = new HashMap<>();
        private final Map<String, Long> reasonTimes = new HashMap<>();
        private JSONObject latest;

        synchronized void accept(JSONObject payload) throws Exception {
            latest = copy(payload);
            if ("bqr".equals(payload.optString("kind"))) {
                samples.addLast(copy(payload));
                while (samples.size() > MAX_SAMPLES) samples.removeFirst();
            }
            boolean hasRemoteReasonHistory = payload.has("reasonCounts")
                    || payload.has("reasonTimes");
            replaceIntegerMap(payload.optJSONObject("reasonCounts"), reasonCounts);
            replaceLongMap(payload.optJSONObject("reasonTimes"), reasonTimes);
            String reason = payload.optString("reason", "");
            if (!hasRemoteReasonHistory && !reason.isEmpty()) {
                reasonCounts.put(reason, reasonCounts.getOrDefault(reason, 0) + 1);
                reasonTimes.put(reason, payload.optLong("time", System.currentTimeMillis()));
            }
        }

        synchronized JSONObject snapshot() throws Exception {
            JSONObject out = new JSONObject();
            out.put("latest", latest == null ? JSONObject.NULL : copy(latest));
            JSONArray history = new JSONArray();
            for (JSONObject sample : samples) history.put(copy(sample));
            out.put("history", history);
            JSONObject counts = new JSONObject();
            for (Map.Entry<String, Integer> entry : reasonCounts.entrySet()) {
                counts.put(entry.getKey(), entry.getValue());
            }
            JSONObject times = new JSONObject();
            for (Map.Entry<String, Long> entry : reasonTimes.entrySet()) {
                times.put(entry.getKey(), entry.getValue());
            }
            out.put("reasonCounts", counts);
            out.put("reasonTimes", times);
            return out;
        }

        private static JSONObject copy(JSONObject object) throws Exception {
            return new JSONObject(object.toString());
        }

        private static void replaceIntegerMap(
                JSONObject source, Map<String, Integer> destination) {
            if (source == null) return;
            destination.clear();
            Iterator<String> keys = source.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                destination.put(key, source.optInt(key, 0));
            }
        }

        private static void replaceLongMap(
                JSONObject source, Map<String, Long> destination) {
            if (source == null) return;
            destination.clear();
            Iterator<String> keys = source.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                destination.put(key, source.optLong(key, 0L));
            }
        }
    }
}
