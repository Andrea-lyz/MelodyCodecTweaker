package xyz.melodylsp.codec.ui;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import xyz.melodylsp.codec.BuildConfig;
import xyz.melodylsp.codec.R;
import xyz.melodylsp.codec.diag.DiagnosticEvents;
import xyz.melodylsp.codec.diag.FeedbackCollector;

public final class MasterSwitchActivity extends Activity {

    private static final String PREFS_NAME = "module_prefs";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_HIDE_LAUNCHER_ICON = "hide_launcher_icon";
    private static final String LAUNCHER_ALIAS =
            "xyz.melodylsp.codec.ui.LauncherActivity";

    private static final int BG = 0xFFF6F7FB;
    private static final int CARD = 0xFFFFFFFF;
    private static final int TEXT = 0xFF151B26;
    private static final int SUBTEXT = 0xFF687385;
    private static final int LINE = 0xFFE5EAF2;
    private static final int BLUE = 0xFF0A84FF;
    private static final int GREEN = 0xFF1F9D63;
    private static final int ORANGE = 0xFFD9822B;
    private static final int RED = 0xFFD64545;

    private SharedPreferences modulePrefs;
    private SharedPreferences diagPrefs;
    private LinearLayout statusList;
    private LinearLayout packageList;
    private TextView recentEvents;
    private TextView enabledStatus;
    private TextView sessionStatus;
    private Switch launcherSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        modulePrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        diagPrefs = getSharedPreferences(DiagnosticEvents.PREFS, Context.MODE_PRIVATE);
        applyLauncherIconState(modulePrefs.getBoolean(KEY_HIDE_LAUNCHER_ICON, false), false);

        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(BG);
            getWindow().setNavigationBarColor(BG);
        }
        if (Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        setContentView(buildContent());
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        scroll.addView(root, matchWrap());

        root.addView(header(), matchWrap());
        root.addView(moduleSwitchCard(), topMargin(matchWrap(), dp(18)));
        root.addView(launcherCard(), topMargin(matchWrap(), dp(12)));
        root.addView(feedbackCard(), topMargin(matchWrap(), dp(12)));

        packageList = section(root, "环境信息");
        statusList = section(root, "诊断状态");

        LinearLayout eventCard = card();
        eventCard.addView(titleText("最近事件"), matchWrap());
        recentEvents = bodyText("", 12, 0xFF4D5968);
        recentEvents.setTypeface(Typeface.MONOSPACE);
        recentEvents.setPadding(0, dp(10), 0, 0);
        eventCard.addView(recentEvents, matchWrap());
        root.addView(eventCard, topMargin(matchWrap(), dp(12)));

        return scroll;
    }

    private View header() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(56), dp(56));
        header.addView(icon, iconLp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(14);
        header.addView(texts, lp);

        TextView title = text("欧加耳机音质助手", 24, TEXT, true);
        texts.addView(title, matchWrap());
        TextView sub = bodyText("音质控制、LE Audio、诊断反馈集中在这里", 14, SUBTEXT);
        sub.setPadding(0, dp(4), 0, 0);
        texts.addView(sub, matchWrap());
        return header;
    }

    private View moduleSwitchCard() {
        LinearLayout card = card();
        LinearLayout row = row();
        card.addView(row, matchWrap());

        LinearLayout texts = column();
        row.addView(texts, weightWrap());
        texts.addView(titleText("模块总开关"), matchWrap());
        enabledStatus = bodyText("", 13, SUBTEXT);
        enabledStatus.setPadding(0, dp(6), 0, 0);
        texts.addView(enabledStatus, matchWrap());

        Switch sw = new Switch(this);
        sw.setChecked(modulePrefs.getBoolean(KEY_ENABLED, true));
        sw.setOnCheckedChangeListener(this::onModuleSwitchChanged);
        row.addView(sw, wrapWrap());
        return card;
    }

    private View launcherCard() {
        LinearLayout card = card();
        LinearLayout row = row();
        card.addView(row, matchWrap());

        LinearLayout texts = column();
        row.addView(texts, weightWrap());
        texts.addView(titleText("隐藏桌面图标"), matchWrap());
        TextView desc = bodyText("隐藏后仍可从 LSPosed、系统应用详情或已打开页面进入诊断页。", 13, SUBTEXT);
        desc.setPadding(0, dp(6), 0, 0);
        texts.addView(desc, matchWrap());

        launcherSwitch = new Switch(this);
        launcherSwitch.setChecked(modulePrefs.getBoolean(KEY_HIDE_LAUNCHER_ICON, false));
        launcherSwitch.setOnCheckedChangeListener(this::onHideLauncherChanged);
        row.addView(launcherSwitch, wrapWrap());
        return card;
    }

    private View feedbackCard() {
        LinearLayout card = card();
        card.addView(titleText("反馈工具"), matchWrap());
        TextView desc = bodyText(
                "遇到问题时先点开始记录，复现一次，再生成反馈包。反馈包会包含时间线、结构化事件、状态快照和可用日志。",
                13,
                SUBTEXT);
        desc.setPadding(0, dp(6), 0, dp(14));
        card.addView(desc, matchWrap());

        sessionStatus = bodyText("", 13, 0xFF4D5968);
        sessionStatus.setPadding(0, 0, 0, dp(12));
        card.addView(sessionStatus, matchWrap());

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(row1, matchWrap());

        Button record = button("开始记录问题", true);
        record.setOnClickListener(v -> startRecordSession());
        row1.addView(record, new LinearLayout.LayoutParams(0, dp(46), 1f));

        Button collect = button("生成反馈包", true);
        collect.setOnClickListener(v -> collectFeedback(collect));
        LinearLayout.LayoutParams collectLp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        collectLp.leftMargin = dp(10);
        row1.addView(collect, collectLp);

        Button refresh = button("刷新状态", false);
        refresh.setOnClickListener(v -> refresh());
        LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        refreshLp.topMargin = dp(10);
        card.addView(refresh, refreshLp);
        return card;
    }

    private LinearLayout section(LinearLayout root, String title) {
        LinearLayout box = card();
        box.addView(titleText(title), matchWrap());
        root.addView(box, topMargin(matchWrap(), dp(12)));
        return box;
    }

    private void refresh() {
        boolean enabled = modulePrefs.getBoolean(KEY_ENABLED, true);
        enabledStatus.setText(enabled
                ? "已启用。重启无线耳机 App 后会注入音质控制项。"
                : "已停用。重启无线耳机 App 后宿主页会恢复原状。");

        if (launcherSwitch != null) {
            launcherSwitch.setOnCheckedChangeListener(null);
            launcherSwitch.setChecked(modulePrefs.getBoolean(KEY_HIDE_LAUNCHER_ICON, false));
            launcherSwitch.setOnCheckedChangeListener(this::onHideLauncherChanged);
        }

        String session = diagPrefs.getString(DiagnosticEvents.KEY_SESSION_ID, "");
        long started = diagPrefs.getLong(DiagnosticEvents.KEY_SESSION_STARTED, 0L);
        sessionStatus.setText(session == null || session.isEmpty()
                ? "当前没有独立复现记录。"
                : "当前记录：" + session + "，开始于 "
                        + DiagnosticEvents.formatTime(started));

        clearDynamicRows(packageList);
        addInfoRow(packageList, "模块版本",
                BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")", 0);
        addInfoRow(packageList, "系统",
                Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE,
                0);
        addInfoRow(packageList, "无线耳机", packageVersion("com.oplus.melody"), 0);
        addInfoRow(packageList, "蓝牙", packageVersion("com.android.bluetooth"), 0);
        addInfoRow(packageList, "无线设置", packageVersion("com.oplus.wirelesssettings"), 0);

        clearDynamicRows(statusList);
        addStatusRow("无线耳机作用域", "scope.host");
        addStatusRow("Host 控制器", "host.controller");
        addStatusRow("页面 Hook", "hook.host");
        addStatusRow("DetailMain 注入", "inject.detail");
        addStatusRow("OneSpace 注入", "inject.onespace");
        addStatusRow("蓝牙作用域", "scope.bluetooth");
        addStatusRow("A2DP Bridge", "bridge.codec");
        addStatusRow("LE Audio 蓝牙桥", "bridge.le.bt");
        addStatusRow("无线设置作用域", "scope.wirelesssettings");
        addStatusRow("LE Audio 无线设置桥", "bridge.le.ws");
        addStatusRow("Native 内存补丁", "native.patch");
        addStatusRow("最近写入", "codec.write");
        addStatusRow("记忆写入", "remember.write");
        addStatusRow("重连重放", "remember.replay");
        addStatusRow("最近警告", "last.warning");
        addStatusRow("最近错误", "last.error");

        String events = diagPrefs.getString(DiagnosticEvents.KEY_EVENTS, "");
        recentEvents.setText(tailLines(events, 12));
    }

    private void onModuleSwitchChanged(CompoundButton buttonView, boolean isChecked) {
        modulePrefs.edit().putBoolean(KEY_ENABLED, isChecked).apply();
        refresh();
    }

    private void onHideLauncherChanged(CompoundButton buttonView, boolean hidden) {
        modulePrefs.edit().putBoolean(KEY_HIDE_LAUNCHER_ICON, hidden).apply();
        boolean applied = applyLauncherIconState(hidden, true);
        Toast.makeText(this,
                applied
                        ? (hidden ? "桌面图标已隐藏" : "桌面图标已恢复")
                        : "桌面图标状态更新失败",
                Toast.LENGTH_SHORT).show();
        refresh();
    }

    private boolean applyLauncherIconState(boolean hidden, boolean notifyLauncher) {
        try {
            ComponentName alias = new ComponentName(this, LAUNCHER_ALIAS);
            int state = hidden
                    ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
            int flags = PackageManager.DONT_KILL_APP;
            if (Build.VERSION.SDK_INT >= 29) {
                flags |= PackageManager.SYNCHRONOUS;
            }
            getPackageManager().setComponentEnabledSetting(
                    alias,
                    state,
                    flags);
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
            if (launcher != null && !launcher.isEmpty()) changed.setPackage(launcher);
            sendBroadcast(changed);
        } catch (Throwable ignored) {
        }
    }

    private String defaultLauncherPackage() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo info = getPackageManager().resolveActivity(home, 0);
            if (info == null || info.activityInfo == null) return "";
            return info.activityInfo.packageName;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private void startRecordSession() {
        Toast.makeText(this, "正在检测 root 权限...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            boolean rootGranted;
            try {
                rootGranted = FeedbackCollector.hasRootAccess();
            } catch (Throwable t) {
                rootGranted = false;
            }
            boolean finalRootGranted = rootGranted;
            runOnUiThread(() -> {
                String id = DiagnosticEvents.startSession(this);
                diagPrefs = getSharedPreferences(DiagnosticEvents.PREFS, Context.MODE_PRIVATE);
                Toast.makeText(this,
                        finalRootGranted
                                ? "已开始记录：" + id
                                : "没有 root 权限，反馈包将缺少蓝牙日志",
                        Toast.LENGTH_SHORT).show();
                refresh();
            });
        }, "OPlusHeadsetAudioHelper-root-check").start();
    }

    private static void clearDynamicRows(LinearLayout parent) {
        int count = parent.getChildCount();
        if (count > 1) {
            parent.removeViews(1, count - 1);
        }
    }

    private void collectFeedback(Button button) {
        button.setEnabled(false);
        button.setText("正在打包...");
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
                button.setEnabled(true);
                button.setText("生成反馈包");
                if (finalError == null) {
                    Toast.makeText(this, "反馈包已保存：" + finalPath, Toast.LENGTH_LONG).show();
                    refresh();
                } else {
                    Toast.makeText(this, "打包失败：" + finalError.getMessage(),
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "OPlusHeadsetAudioHelper-feedback").start();
    }

    private void addStatusRow(String label, String key) {
        String status = DiagnosticEvents.status(diagPrefs, key);
        String time = DiagnosticEvents.formatTime(DiagnosticEvents.time(diagPrefs, key));
        String detail = DiagnosticEvents.detail(diagPrefs, key);
        addInfoRow(statusList, label, status + "  " + time
                + (detail == null || detail.isEmpty() ? "" : "\n" + detail),
                colorForStatus(status));
    }

    private void addInfoRow(LinearLayout parent, String label, String value, int accent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(13), 0, 0);
        parent.addView(row, matchWrap());
        TextView l = bodyText(label, 13, SUBTEXT);
        row.addView(l, matchWrap());
        TextView v = bodyText(value, 14, TEXT);
        v.setPadding(0, dp(4), 0, 0);
        if (accent != 0) v.setTextColor(accent);
        row.addView(v, matchWrap());
    }

    private String packageVersion(String pkg) {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(pkg, 0);
            long code = Build.VERSION.SDK_INT >= 28
                    ? info.getLongVersionCode() : info.versionCode;
            return String.valueOf(info.versionName) + " (" + code + ")";
        } catch (Throwable t) {
            return "未安装 / 不可读取";
        }
    }

    private static String tailLines(String text, int maxLines) {
        if (text == null || text.trim().isEmpty()) return "还没有收到 Hook 事件。";
        String[] lines = text.split("\\n");
        int start = Math.max(0, lines.length - maxLines);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString().trim();
    }

    private int colorForStatus(String status) {
        if ("ok".equals(status)
                || "ready".equals(status)
                || "loaded".equals(status)
                || "hooked".equals(status)
                || "injected".equals(status)
                || "registered".equals(status)) {
            return GREEN;
        }
        if ("attention".equals(status) || "error".equals(status)) return RED;
        if ("pending".equals(status) || "warning".equals(status)) return ORANGE;
        return 0xFF4D5968;
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(18));
        bg.setStroke(1, LINE);
        box.setBackground(bg);
        return box;
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextSize(14);
        b.setTextColor(primary ? Color.WHITE : BLUE);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(primary ? BLUE : 0xFFEAF3FF);
        b.setBackground(bg);
        return b;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private TextView titleText(String value) {
        return text(value, 17, TEXT, true);
    }

    private TextView bodyText(String value, int sp, int color) {
        return text(value, sp, color, false);
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setLineSpacing(dp(2), 1.0f);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static ViewGroup.LayoutParams matchWrap() {
        return new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static ViewGroup.LayoutParams wrapWrap() {
        return new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams weightWrap() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private static ViewGroup.MarginLayoutParams topMargin(
            ViewGroup.LayoutParams base,
            int top) {
        ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(base);
        lp.topMargin = top;
        return lp;
    }
}
