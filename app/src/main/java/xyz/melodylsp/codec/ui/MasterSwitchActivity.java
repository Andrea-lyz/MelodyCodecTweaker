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
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
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
    private static final int BLUE_SOFT = 0xFFEAF3FF;
    private static final int GREEN = 0xFF1F9D63;
    private static final int GREEN_SOFT = 0xFFE7F5EE;
    private static final int ORANGE = 0xFFD9822B;
    private static final int ORANGE_SOFT = 0xFFFDF1DE;
    private static final int RED = 0xFFD64545;
    private static final int RED_SOFT = 0xFFFCE3E3;
    private static final int MUTED = 0xFF8B96AA;

    private SharedPreferences modulePrefs;
    private SharedPreferences diagPrefs;
    private LinearLayout statusList;
    private LinearLayout packageList;
    private LinearLayout memoryList;
    private TextView recentEvents;
    private TextView enabledStatus;
    private TextView sessionStatus;
    private Switch launcherSwitch;
    private Button recordButton;
    private boolean memorySnapshotRefreshPending;

    // BQR 实时环境 / 历史窗口 / Reason 列表 / 边界状态 用的 view holder。
    private TextView bqrRetxValue;
    private TextView bqrNorxValue;
    private TextView bqrAfhUnusedValue;
    private TextView bqrAfhTotalValue;
    private TextView bqrActivity;
    private LinearLayout bqrHistoryHolder;
    private LinearLayout bqrReasonList;
    private LinearLayout bqrBoundary1;
    private LinearLayout bqrBoundary2;
    private TextView bqrCeilingLabel;

    // reason 关键字 → { 中文标签, 解释, 类别 }。类别影响颜色。
    private static final String[][] REASON_TABLE = {
            {"healthy_recovery_probe", "主动试探", "active",
                    "队列空闲时，重置试探看能不能上探到 900 / 1000 kbps"},
            {"probe_stable", "探针稳定", "stable",
                    "链路连续 3 个健康窗口，解除上限，恢复高码率"},
            {"probe_health_lost", "链路质量变差", "idle",
                    "健康窗口归零，关闭探针，等下次重新评估"},
            {"probe_stream_idle", "音乐暂停", "idle",
                    "没有 A2DP 流，关闭探针避免空跑"},
            {"probe_queue_congested", "队列积压", "queued",
                    "蓝牙缓冲队列超过 90%，链路跟不上，先撤回"},
            {"probe_choppy", "声音卡顿", "idle",
                    "检测到外部卡顿事件，立刻撤下探针"},
            {"boundary_locked", "升级门槛锁定", "locked",
                    "连续失败，目标码率被锁定，下调至上一档"},
            {"boundary_stable", "升级门槛解除", "stable",
                    "升级后稳定运行，撤销该档位的锁定证据"},
            {"quick_failure", "快速失败", "queued",
                    "升级后立刻报错，准备升级证据"},
            {"device_active", "设备激活", "stable",
                    "耳机连接起来，重新评估当前码率天花板"},
            {"device_reconnect", "设备重连", "stable",
                    "耳机刚重连，重新评估当前码率天花板"},
    };
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    // Foreground-only heartbeat. Re-reads the latest diagnostic SharedPreferences
    // every 4s so KPIs and event rows stay live while the user is staring at
    // the page. Cancelled in onPause() so we stop touching the prefs and stop
    // driving pref-change observers once the Activity is no longer visible.
    private static final long REFRESH_INTERVAL_MS = 4_000L;
    private final Runnable refreshTick = new Runnable() {
        @Override public void run() {
            if (isFinishing() || isDestroyed()) return;
            refresh();
            refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        modulePrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        diagPrefs = getSharedPreferences(DiagnosticEvents.PREFS, Context.MODE_PRIVATE);
        DiagnosticEvents.reconcileReceiverState(this);
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
        // Start the foreground heartbeat. Cancellable in onPause() so the
        // diagnostic view never keeps touching SharedPreferences or driving
        // pref observers while the page is in the background.
        refreshHandler.removeCallbacks(refreshTick);
        refreshHandler.postDelayed(refreshTick, REFRESH_INTERVAL_MS);
    }

    @Override
    protected void onPause() {
        refreshHandler.removeCallbacks(refreshTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        refreshHandler.removeCallbacks(refreshTick);
        super.onDestroy();
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
        root.addView(bqrRealtimeCard(), topMargin(matchWrap(), dp(12)));
        root.addView(bqrHistoryCard(), topMargin(matchWrap(), dp(12)));

        packageList = section(root, "环境信息");
        statusList = section(root, "诊断状态");
        memoryList = section(root, "记忆信息");

        LinearLayout eventCard = card();
        eventCard.addView(titleText("最近事件"), matchWrap());
        recentEvents = bodyText("", 12, 0xFF4D5968);
        recentEvents.setTypeface(Typeface.MONOSPACE);
        recentEvents.setPadding(0, dp(10), 0, 0);
        eventCard.addView(recentEvents, matchWrap());
        root.addView(eventCard, topMargin(matchWrap(), dp(12)));

        return scroll;
    }

    /** BQR 实时环境：4 个 KPI + 边界状态 + 最近 9 条 reason 摘要。 */
    private View bqrRealtimeCard() {
        LinearLayout card = card();
        LinearLayout head = row();
        card.addView(head, matchWrap());
        TextView title = titleText("LHDC 链路 · BQR 实时环境");
        head.addView(title, weightWrap());
        bqrActivity = pillOf("活动", GREEN, GREEN_SOFT, true);
        head.addView(bqrActivity, wrapWrap());

        bqrRetxValue = bqrKpiRow(card, "retx");
        bqrNorxValue = bqrKpiRow(card, "norx");
        bqrAfhUnusedValue = bqrKpiRow(card, "afh_unused");
        bqrAfhTotalValue = bqrKpiRow(card, "afh_total");

        TextView ceilingTitle = titleText("边界状态");
        LinearLayout.LayoutParams ceilingLp = new LinearLayout.LayoutParams(matchWrap());
        ceilingLp.topMargin = dp(18);
        card.addView(ceilingTitle, ceilingLp);
        bqrCeilingLabel = bodyText("ceiling —", 12, SUBTEXT);
        bqrCeilingLabel.setPadding(0, dp(4), 0, 0);
        card.addView(bqrCeilingLabel, matchWrap());

        bqrBoundary1 = boundaryRow(card, "500 → 900");
        bqrBoundary2 = boundaryRow(card, "900 → 1000");

        TextView reasonTitle = titleText("最近事件理由");
        LinearLayout.LayoutParams reasonLp = new LinearLayout.LayoutParams(matchWrap());
        reasonLp.topMargin = dp(18);
        card.addView(reasonTitle, reasonLp);
        bqrReasonList = new LinearLayout(this);
        bqrReasonList.setOrientation(LinearLayout.VERTICAL);
        card.addView(bqrReasonList, matchWrap());

        TextView hint = bodyText(
                "数据来自 LhdcLinkHealthController → evt=lhdc.link.bqr_summary 事件，"
                        + "本卡片只解析现有 pref，不向宿主进程发起新采集。",
                12, SUBTEXT);
        hint.setPadding(0, dp(14), 0, 0);
        card.addView(hint, matchWrap());
        return card;
    }

    /** BQR 历史窗口：最近 24 条 BQR 摘要，按时间分桶。 */
    private View bqrHistoryCard() {
        LinearLayout card = card();
        TextView title = titleText("LHDC 链路 · BQR 历史窗口");
        card.addView(title, matchWrap());
        TextView hint = bodyText("最近 24 条 BQR 摘要，按到达时间分桶（每桶 ≈ 1 条）。",
                12, SUBTEXT);
        hint.setPadding(0, dp(4), 0, 0);
        card.addView(hint, matchWrap());

        bqrHistoryHolder = new LinearLayout(this);
        bqrHistoryHolder.setOrientation(LinearLayout.HORIZONTAL);
        bqrHistoryHolder.setGravity(Gravity.BOTTOM | Gravity.START);
        LinearLayout.LayoutParams barsLp = new LinearLayout.LayoutParams(matchWrap());
        barsLp.topMargin = dp(14);
        barsLp.height = dp(72);
        card.addView(bqrHistoryHolder, barsLp);
        TextView legend = bodyText("t=当下  …  t-23=最早临近", 11, MUTED);
        legend.setPadding(0, dp(8), 0, 0);
        card.addView(legend, matchWrap());
        return card;
    }

    private TextView bqrKpiRow(LinearLayout parent, String label) {
        LinearLayout row = row();
        row.setPadding(0, dp(8), 0, 0);
        parent.addView(row, matchWrap());
        TextView name = bodyText(label, 13, SUBTEXT);
        row.addView(name, weightWrap());
        TextView value = bodyText("—", 16, TEXT, true);
        row.addView(value, wrapWrap());
        if ("retx".equals(label)) bqrRetxValue = value;
        else if ("norx".equals(label)) bqrNorxValue = value;
        else if ("afh_unused".equals(label)) bqrAfhUnusedValue = value;
        else if ("afh_total".equals(label)) bqrAfhTotalValue = value;
        return value;
    }

    private LinearLayout boundaryRow(LinearLayout parent, String name) {
        LinearLayout row = row();
        row.setPadding(0, dp(8), 0, 0);
        parent.addView(row, matchWrap());
        TextView label = bodyText(name, 13, SUBTEXT);
        row.addView(label, weightWrap());
        TextView status = bodyText("未知", 13, MUTED);
        row.addView(status, wrapWrap());
        return row;
    }

    private TextView pillOf(String text, int fg, int bg, boolean pulsing) {
        TextView pill = new TextView(this);
        pill.setText(text);
        pill.setTextSize(11);
        pill.setTextColor(fg);
        pill.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dp(10));
        pill.setBackground(d);
        if (pulsing) {
            pill.setCompoundDrawablePadding(dp(6));
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setSize(dp(6), dp(6));
            dot.setColor(fg);
            pill.setCompoundDrawablesWithIntrinsicBounds(dot, null, null, null);
        }
        return pill;
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
        recordButton = record;
        row1.addView(record, new LinearLayout.LayoutParams(0, dp(46), 1f));

        Button collect = button("生成反馈包", true);
        collect.setOnClickListener(v -> collectFeedback(collect));
        LinearLayout.LayoutParams collectLp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        collectLp.leftMargin = dp(10);
        row1.addView(collect, collectLp);

        Button refresh = button("重抓 Melody 记忆快照", false);
        refresh.setOnClickListener(v -> {
            requestRememberedSnapshot();
            refresh();
        });
        LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        refreshLp.topMargin = dp(10);
        card.addView(refresh, refreshLp);
        TextView refreshHint = bodyText(
                "诊断页在前台时会自动每 4 秒刷新一次上面的状态与事件；"
                        + "这个按钮仅用于主动向 Melody 进程请求一次记忆快照。",
                12,
                SUBTEXT);
        refreshHint.setPadding(0, dp(8), 0, 0);
        card.addView(refreshHint, matchWrap());
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
        long expires = diagPrefs.getLong(DiagnosticEvents.KEY_SESSION_EXPIRES, 0L);
        boolean recording = DiagnosticEvents.isRecording(this);
        if (session == null || session.isEmpty()) {
            sessionStatus.setText("尚未开始问题记录。平时不会在后台持续采集诊断事件。");
        } else if (recording) {
            sessionStatus.setText("正在记录：" + session + "，自动结束于 "
                    + DiagnosticEvents.formatTime(expires));
        } else {
            sessionStatus.setText("上次记录：" + session + "，开始于 "
                    + DiagnosticEvents.formatTime(started) + "（已结束）");
        }
        if (recordButton != null) {
            recordButton.setText(recording ? "停止记录" : "开始记录问题");
            setButtonPrimary(recordButton, !recording);
        }

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

        clearDynamicRows(memoryList);
        addInfoRow(memoryList, "当前 Melody 真实记忆",
                DiagnosticEvents.rememberedSummary(diagPrefs), 0);
        addInfoRow(memoryList, "上次记忆恢复链路",
                tailLines(DiagnosticEvents.replayChain(diagPrefs), 24), 0);

        String events = diagPrefs.getString(DiagnosticEvents.KEY_EVENTS, "");
        recentEvents.setText(tailLines(events, 12));

        refreshBqrAndReason();
    }

    /** 解析 BQR 实时 / 历史 / 边界 / Reason 列表。 仅消费 pref，不发起新采集。 */
    private void refreshBqrAndReason() {
        String jsonl = diagPrefs.getString(DiagnosticEvents.KEY_EVENTS_JSON, "");
        BqrAccumulator acc = new BqrAccumulator();
        if (jsonl != null && !jsonl.isEmpty()) {
            // 反向扫描，让最新事件先被处理。
            String[] lines = jsonl.split("\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                acc.consume(lines[i]);
            }
        }

        // —— 4 个 KPI —— //
        int retx = acc.lastRetx;
        int norx = acc.lastNorx;
        int afhUnused = acc.lastAfhUnused;
        int afhTotal = acc.lastAfhTotal;
        if (acc.hasBqr) {
            bqrRetxValue.setText(String.valueOf(retx));
            bqrRetxValue.setTextColor(kpiColor(retx, 60));
            bqrNorxValue.setText(String.valueOf(norx));
            bqrNorxValue.setTextColor(kpiColor(norx, 60));
            // 未用 AFH 信道：越少越好（说明被占用越少）。
            bqrAfhUnusedValue.setText(afhUnused + " / 79");
            bqrAfhUnusedValue.setTextColor(afhUnused <= 22 ? GREEN : (afhUnused <= 49 ? ORANGE : RED));
            // 可用 AFH：越多越好。
            bqrAfhTotalValue.setText(afhTotal + " / 79");
            bqrAfhTotalValue.setTextColor(afhTotal >= 30 ? GREEN : ORANGE);
            bqrActivity.setText("活动");
            bqrActivity.setTextColor(GREEN);
            ((GradientDrawable) bqrActivity.getBackground()).setColor(GREEN_SOFT);
        } else {
            bqrRetxValue.setText("—");
            bqrNorxValue.setText("—");
            bqrAfhUnusedValue.setText("— / —");
            bqrAfhTotalValue.setText("— / 79");
            bqrActivity.setText("等待数据");
            bqrActivity.setTextColor(MUTED);
            ((GradientDrawable) bqrActivity.getBackground()).setColor(0xFFEFF1F5);
        }

        // —— 边界状态 —— //
        renderBoundary(bqrBoundary1, 500, 900, acc.boundaryLocked500to900);
        renderBoundary(bqrBoundary2, 900, 1000, acc.boundaryLocked900to1000);
        bqrCeilingLabel.setText(
                acc.lastCeilingKbps > 0
                        ? "ceiling " + acc.lastCeilingKbps + " kbps"
                        : "ceiling —");

        // —— 历史窗口 —— //
        renderHistoryBars(acc.retxHistory);

        // —— Reason 列表 —— //
        renderReasonList(acc);
    }

    private int kpiColor(int value, int warnAbove) {
        if (value <= warnAbove / 2) return GREEN;
        if (value <= warnAbove) return ORANGE;
        return RED;
    }

    private void renderBoundary(LinearLayout row, int from, int to, boolean locked) {
        TextView status = (TextView) row.getChildAt(1);
        if (locked) {
            status.setText("锁定");
            status.setTextColor(RED);
        } else {
            status.setText("解锁");
            status.setTextColor(GREEN);
        }
    }

    private void renderHistoryBars(int[] values) {
        if (bqrHistoryHolder == null) return;
        bqrHistoryHolder.removeAllViews();
        int peak = 1;
        for (int v : values) if (v > peak) peak = v;
        int parentHeight = dp(72);
        for (int i = 0; i < values.length; i++) {
            int v = values[i];
            int heightPx = Math.max(2, (int) (parentHeight * (v / (float) peak)));
            FrameLayout cell = new FrameLayout(this);
            LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            cellLp.leftMargin = i == 0 ? 0 : dp(2);
            cell.setLayoutParams(cellLp);

            View bar = new View(this);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(2));
            // unusedAfh：未用信道越多 = 越拥塞 = 越红。
            if (v <= 0) {
                bg.setColor(0xFFE5EAF2);
            } else if (v > peak * 0.66) {
                bg.setColor(RED);
            } else if (v > peak * 0.33) {
                bg.setColor(ORANGE);
            } else {
                bg.setColor(GREEN);
            }
            bar.setBackground(bg);
            FrameLayout.LayoutParams inner = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, heightPx);
            inner.gravity = Gravity.BOTTOM;
            cell.addView(bar, inner);
            bqrHistoryHolder.addView(cell);
        }
    }

    private void renderReasonList(BqrAccumulator acc) {
        if (bqrReasonList == null) return;
        bqrReasonList.removeAllViews();
        long now = System.currentTimeMillis();
        for (String[] entry : REASON_TABLE) {
            String key = entry[0];
            String label = entry[1];
            String tier = entry[2];
            String desc = entry[3];
            int count = acc.reasonCount.getOrDefault(key, 0);
            long lastTime = acc.reasonLast.getOrDefault(key, 0L);

            LinearLayout row = card();
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(matchWrap());
            rp.topMargin = dp(8);
            row.setLayoutParams(rp);

            LinearLayout head = row();
            head.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(head, matchWrap());
            TextView keyChip = bodyText(key, 11, textColorForTier(tier));
            keyChip.setPadding(dp(8), dp(3), dp(8), dp(3));
            GradientDrawable keyBg = new GradientDrawable();
            keyBg.setColor(bgColorForTier(tier));
            keyBg.setCornerRadius(dp(8));
            keyChip.setBackground(keyBg);
            head.addView(keyChip, wrapWrap());
            TextView labelText = bodyText(label, 13, TEXT, true);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            labelLp.leftMargin = dp(8);
            head.addView(labelText, labelLp);

            TextView descText = bodyText(desc, 12, SUBTEXT);
            descText.setPadding(0, dp(6), 0, 0);
            row.addView(descText, matchWrap());

            String when;
            if (lastTime <= 0L) {
                when = "暂无记录";
            } else {
                long diff = Math.max(0, now - lastTime);
                if (diff < 60_000L) when = "刚刚";
                else if (diff < 3_600_000L) when = (diff / 60_000L) + " 分钟前";
                else if (diff < 86_400_000L) when = (diff / 3_600_000L) + " 小时前";
                else when = (diff / 86_400_000L) + " 天前";
            }
            TextView whenText = bodyText(
                    when + (count > 0 ? "  ·  本次会话 " + count + " 次" : ""),
                    11, MUTED);
            whenText.setPadding(0, dp(4), 0, 0);
            row.addView(whenText, matchWrap());
            bqrReasonList.addView(row);
        }
    }

    private int textColorForTier(String tier) {
        if ("stable".equals(tier)) return GREEN;
        if ("idle".equals(tier)) return MUTED;
        if ("queued".equals(tier)) return ORANGE;
        if ("locked".equals(tier)) return RED;
        if ("active".equals(tier)) return BLUE;
        return TEXT;
    }

    private int bgColorForTier(String tier) {
        if ("stable".equals(tier)) return GREEN_SOFT;
        if ("idle".equals(tier)) return 0xFFEFF1F5;
        if ("queued".equals(tier)) return ORANGE_SOFT;
        if ("locked".equals(tier)) return RED_SOFT;
        if ("active".equals(tier)) return BLUE_SOFT;
        return 0xFFEFF1F5;
    }

    /** 解析 JSONL 时累计 BQR / reason / 边界状态。 */
    private static final class BqrAccumulator {
        boolean hasBqr;
        int lastRetx;
        int lastNorx;
        int lastAfhUnused;
        int lastAfhTotal;
        int lastCeilingKbps;
        boolean boundaryLocked500to900;
        boolean boundaryLocked900to1000;
        final int[] retxHistory = new int[24];
        int historyCount;
        final java.util.HashMap<String, Integer> reasonCount = new java.util.HashMap<>();
        final java.util.HashMap<String, Long> reasonLast = new java.util.HashMap<>();

        void consume(String line) {
            if (line == null || line.isEmpty()) return;
            String trimmed = line.trim();
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return;
            String event = jsonStringField(trimmed, "event");
            String message = jsonStringField(trimmed, "message");
            long time = jsonLongField(trimmed, "time");

            if (event == null && message == null) return;
            if (event == null) event = "";
            if (message == null) message = "";

            // 1. BQR 实时环境：MLog.event("lhdc.link.bqr_summary", telemetry)
            //    telemetry 字段：mac, unusedAfh, usableAfh, unidealAfh,
            //                    retransmissions, retransmissionsPerSec,
            //                    noRx, noRxPerSec, ceilingKbps, ...
            if (event.endsWith("lhdc.link.bqr_summary") || message.contains("lhdc.link.bqr_summary")) {
                int retx = parseIntField(message, "retransmissionsPerSec");
                if (retx == 0) retx = parseIntField(message, "retransmissions");
                int norx = parseIntField(message, "noRxPerSec");
                if (norx == 0) norx = parseIntField(message, "noRx");
                int afhUnused = parseIntField(message, "unusedAfh");
                int afhTotal = parseIntField(message, "usableAfh");
                int ceil = parseIntField(message, "ceilingKbps");
                if (!hasBqr) {
                    lastRetx = retx;
                    lastNorx = norx;
                    lastAfhUnused = afhUnused;
                    lastAfhTotal = afhTotal;
                    lastCeilingKbps = ceil;
                    hasBqr = true;
                } else {
                    lastRetx = retx;
                    lastNorx = norx;
                    lastAfhUnused = afhUnused;
                    lastAfhTotal = afhTotal;
                    lastCeilingKbps = ceil;
                }
                // history 用 unusedAfh 当桶值，反映"信道拥挤度"的变化趋势，
                // 比瞬时 retransmissions 更稳定也更易区分。
                if (historyCount < retxHistory.length) {
                    retxHistory[retxHistory.length - 1 - historyCount] = afhUnused;
                    historyCount++;
                } else {
                    System.arraycopy(retxHistory, 1, retxHistory, 0, retxHistory.length - 1);
                    retxHistory[retxHistory.length - 1] = afhUnused;
                }
            }

            // 2. 边界状态：MLog.event("lhdc.link.governor_event",
            //                       type, fromKbps, toKbps, ceilingKbps, ...)
            if (event.endsWith("lhdc.link.governor_event") || message.contains("lhdc.link.governor_event")) {
                int from = parseIntField(message, "fromKbps");
                int to = parseIntField(message, "toKbps");
                String gevent = parseStringField(message, "type");
                if (gevent == null) gevent = parseStringField(message, "event");
                boolean locked = "boundary_locked".equals(gevent)
                        || "quick_failure".equals(gevent);
                if (from == 500 && to == 900) {
                    boundaryLocked500to900 = locked;
                } else if (from == 900 && to == 1000) {
                    boundaryLocked900to1000 = locked;
                }
            }

            // 3. Reason 计数：MLog.event("lhdc.link.probe_ceiling",
            //                          ceilingKbps, reason, ...)
            //    只在 probe_ceiling 这一类事件里计数，不误算 bqr_summary。
            if (event.endsWith("lhdc.link.probe_ceiling") || message.contains("lhdc.link.probe_ceiling")) {
                String reason = parseStringField(message, "reason");
                if (reason != null && !reason.isEmpty()) {
                    reasonCount.put(reason, reasonCount.getOrDefault(reason, 0) + 1);
                    if (time > 0) {
                        long prev = reasonLast.getOrDefault(reason, 0L);
                        if (time > prev) reasonLast.put(reason, time);
                    }
                }
            }
        }

        private static int parseIntField(String msg, String key) {
            String v = parseStringField(msg, key);
            if (v == null) return 0;
            try {
                // 兼容 "12.5" 这类 rateText("%.1f") 输出，只取整数部分。
                int dot = v.indexOf('.');
                String head = dot >= 0 ? v.substring(0, dot) : v;
                return Integer.parseInt(head);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        private static String parseStringField(String msg, String key) {
            String target = key + "=";
            int idx = msg.indexOf(target);
            if (idx < 0) return null;
            int start = idx + target.length();
            int end = msg.length();
            for (int i = start; i < msg.length(); i++) {
                char c = msg.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    end = i;
                    break;
                }
            }
            return msg.substring(start, end);
        }

        private static String jsonStringField(String json, String name) {
            String needle = "\"" + name + "\":";
            int idx = json.indexOf(needle);
            if (idx < 0) return null;
            int start = idx + needle.length();
            while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t')) start++;
            if (start >= json.length() || json.charAt(start) != '"') return null;
            start++;
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '\\' && i + 1 < json.length()) {
                    char n = json.charAt(i + 1);
                    if (n == 'n') sb.append('\n');
                    else if (n == 't') sb.append('\t');
                    else if (n == 'r') sb.append('\r');
                    else sb.append(n);
                    i++;
                    continue;
                }
                if (c == '"') return sb.toString();
                sb.append(c);
            }
            return null;
        }

        private static long jsonLongField(String json, String name) {
            String v = jsonStringField(json, name);
            if (v == null) {
                // 数字字段没有引号
                String needle = "\"" + name + "\":";
                int idx = json.indexOf(needle);
                if (idx < 0) return 0L;
                int start = idx + needle.length();
                while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t')) start++;
                int end = start;
                while (end < json.length()) {
                    char c = json.charAt(end);
                    if (c == ',' || c == '}' || c == ' ' || c == '\n' || c == '\r' || c == '\t') break;
                    end++;
                }
                try {
                    return Long.parseLong(json.substring(start, end));
                } catch (NumberFormatException e) {
                    return 0L;
                }
            }
            try {
                return Long.parseLong(v);
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
    }

    private void requestRememberedSnapshot() {
        DiagnosticEvents.requestRememberedSnapshot(this);
        if (memorySnapshotRefreshPending) return;
        memorySnapshotRefreshPending = true;
        View anchor = memoryList != null ? memoryList : getWindow().getDecorView();
        anchor.postDelayed(() -> {
            memorySnapshotRefreshPending = false;
            diagPrefs = getSharedPreferences(DiagnosticEvents.PREFS, Context.MODE_PRIVATE);
            refresh();
        }, 900L);
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
        long recordedAt = DiagnosticEvents.time(diagPrefs, key);
        String detail = DiagnosticEvents.detail(diagPrefs, key);
        String value = recordedAt <= 0L
                ? "尚未采集（不代表模块未生效）"
                : status + "  " + DiagnosticEvents.formatTime(recordedAt);
        addInfoRow(statusList, label, value
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
        if (text == null || text.trim().isEmpty()) {
            return "尚未采集到诊断事件；这不代表模块未生效。";
        }
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

    /** 重新套用 button() 的 primary/ghost 配色（不重建 View）。 */
    private void setButtonPrimary(Button b, boolean primary) {
        b.setTextColor(primary ? Color.WHITE : BLUE);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(primary ? BLUE : BLUE_SOFT);
        b.setBackground(bg);
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

    private TextView bodyText(String value, int sp, int color, boolean bold) {
        return text(value, sp, color, bold);
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
