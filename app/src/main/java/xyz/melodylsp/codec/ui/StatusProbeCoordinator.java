package xyz.melodylsp.codec.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import xyz.melodylsp.codec.bridge.CodecIpc;
import xyz.melodylsp.codec.diag.StatusProbe;
import xyz.melodylsp.codec.util.TrustedBroadcasts;

/**
 * 模块 App 侧的实时状态探测协调器：向每个作用域进程广播一次只读探测，收集回复并在窗口结束后
 * 回调。回复带随机 probe id，只有当前一轮的回复会被采用；Android 14+ 还校验发送者身份。
 */
public final class StatusProbeCoordinator {

    /** 一轮探测窗口结束后的回调（在主线程）。 */
    public interface Listener {
        void onReports(Map<String, JSONObject> reports);
    }

    private static final String TAG = "MelodyCodecLsp";
    private static final long COLLECT_WINDOW_MS = 1200L;
    private static final String[] ALLOWED_SENDERS = {
            CodecIpc.MELODY_PKG,
            CodecIpc.BLUETOOTH_PKG,
            "com.oplus.wirelesssettings",
            "com.android.settings",
    };

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, JSONObject> reports = new ConcurrentHashMap<>();
    private final Map<String, JSONObject> confirmedReports = new ConcurrentHashMap<>();
    private final SharedPreferences confirmations;
    private final String moduleApk;
    private final int bootCount;
    private final SecureRandom random = new SecureRandom();

    private BroadcastReceiver receiver;
    private boolean registered;
    private volatile long probeId;
    private volatile Listener listener;
    private volatile boolean probing;
    private volatile boolean probed;

    public StatusProbeCoordinator(Context context) {
        this.context = context.getApplicationContext();
        moduleApk = this.context.getApplicationInfo().sourceDir;
        bootCount = Settings.Global.getInt(this.context.getContentResolver(),
                Settings.Global.BOOT_COUNT, -1);
        confirmations = this.context.getSharedPreferences("status_probe", Context.MODE_PRIVATE);
        // 安装包路径每次重装都会变化；重启手机后也重新确认，不能沿用旧安装的绿勾。
        if (bootCount >= 0 && bootCount == confirmations.getInt("boot", -2)
                && moduleApk.equals(confirmations.getString("apk", ""))) {
            for (String target : StatusProbe.probeTargets()) {
                String scope = scopeForPackage(target);
                try {
                    JSONObject report = new JSONObject(confirmations.getString(scope, ""));
                    if (moduleApk.equals(report.optString("module_apk"))) {
                        confirmedReports.put(scope, report);
                    }
                } catch (Throwable ignored) {
                }
            }
        } else {
            confirmations.edit().clear().putInt("boot", bootCount)
                    .putString("apk", moduleApk).apply();
        }
    }

    /** 注册回复接收器（幂等）；只在页面前台期间需要。 */
    public synchronized void register() {
        if (registered) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                TrustedBroadcasts.SenderIdentity sender = TrustedBroadcasts.captureSender(this);
                handleReport(intent, sender);
            }
        };
        IntentFilter filter = new IntentFilter(CodecIpc.ACTION_STATUS_REPORT);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            registered = true;
        } catch (Throwable t) {
            receiver = null;
            Log.w(TAG, "status probe receiver registration failed", t);
        }
    }

    public synchronized void unregister() {
        if (!registered || receiver == null) return;
        try {
            context.unregisterReceiver(receiver);
        } catch (Throwable ignored) {
        }
        receiver = null;
        registered = false;
        handler.removeCallbacks(finishWindow);
        probing = false;
        listener = null;
        reports.clear();
    }

    /** 广播一轮探测；窗口结束（或超时）后回调，只有最后一次监听器会被通知。 */
    public void probe(Listener listener) {
        this.listener = listener;
        reports.clear();
        probing = true;
        probeId = random.nextLong();
        for (String target : StatusProbe.probeTargets()) {
            try {
                Intent intent = new Intent(CodecIpc.ACTION_STATUS_PROBE);
                intent.setPackage(target);
                intent.putExtra(CodecIpc.EXTRA_TOKEN, CodecIpc.TOKEN);
                intent.putExtra(CodecIpc.EXTRA_PROBE_ID, probeId);
                TrustedBroadcasts.send(context, intent);
            } catch (Throwable t) {
                Log.w(TAG, "status probe send failed for " + target, t);
            }
        }
        handler.removeCallbacks(finishWindow);
        handler.postDelayed(finishWindow, COLLECT_WINDOW_MS);
    }

    /** 最近一轮探测已收到的报告（作用域名 → JSON）。 */
    public Map<String, JSONObject> snapshot() {
        return new LinkedHashMap<>(reports);
    }

    /** 本次开机、当前安装包已确认的报告；进程退出不撤销已加载这一事实。 */
    public Map<String, JSONObject> confirmedSnapshot() {
        return new LinkedHashMap<>(confirmedReports);
    }

    public boolean isProbing() {
        return probing;
    }

    public boolean hasProbed() {
        return probed;
    }

    private final Runnable finishWindow = () -> {
        probing = false;
        probed = true;
        Listener current = listener;
        if (current != null) current.onReports(snapshot());
    };

    private void handleReport(Intent intent, TrustedBroadcasts.SenderIdentity sender) {
        if (intent == null || !CodecIpc.ACTION_STATUS_REPORT.equals(intent.getAction())) return;
        if (!CodecIpc.TOKEN.equals(intent.getStringExtra(CodecIpc.EXTRA_TOKEN))) return;
        if (intent.getLongExtra(CodecIpc.EXTRA_PROBE_ID, -1L) != probeId) return;
        // 身份可用且不属于四个作用域包：拒绝；身份未共享时靠 token + 随机 probe id 把关。
        if (TrustedBroadcasts.supportsSenderIdentity() && sender != null && sender.available
                && !TrustedBroadcasts.isTrustedSender(context, sender, ALLOWED_SENDERS)) {
            Log.w(TAG, "status probe report rejected: untrusted sender");
            return;
        }
        String raw = intent.getStringExtra(CodecIpc.EXTRA_STATUS_REPORT);
        if (raw == null || raw.isEmpty()) return;
        try {
            JSONObject report = new JSONObject(raw);
            String scope = report.optString("scope", "");
            if (scope.isEmpty()) scope = intent.getStringExtra(CodecIpc.EXTRA_STATUS_SCOPE);
            if (scope == null || scope.isEmpty()) return;
            String expectedPackage = StatusProbe.packageForScope(scope);
            if (expectedPackage == null) return;
            if (sender != null && sender.available
                    && !TrustedBroadcasts.isTrustedSender(context, sender, expectedPackage)) return;
            if (!moduleApk.equals(report.optString("module_apk"))) return;
            report.put("confirmed_at", System.currentTimeMillis());
            reports.put(scope, report);
            confirmedReports.put(scope, report);
            confirmations.edit().putString(scope, report.toString()).apply();
            // 收到有效回执即刷新页面，不必等下一次 HTML 轮询或完整探测窗口。
            Listener current = listener;
            if (current != null) current.onReports(snapshot());
        } catch (Throwable t) {
            Log.w(TAG, "status probe report parse failed", t);
        }
    }

    private static String scopeForPackage(String pkg) {
        if (CodecIpc.MELODY_PKG.equals(pkg)) return StatusProbe.SCOPE_HOST;
        if (CodecIpc.BLUETOOTH_PKG.equals(pkg)) return StatusProbe.SCOPE_BLUETOOTH;
        if ("com.oplus.wirelesssettings".equals(pkg)) return StatusProbe.SCOPE_WIRELESS_SETTINGS;
        return StatusProbe.SCOPE_SETTINGS;
    }
}

