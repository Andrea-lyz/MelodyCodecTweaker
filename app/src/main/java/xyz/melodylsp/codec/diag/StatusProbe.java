package xyz.melodylsp.codec.diag;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import xyz.melodylsp.codec.bridge.CodecIpc;
import xyz.melodylsp.codec.util.MLog;
import xyz.melodylsp.codec.util.TrustedBroadcasts;

/**
 * 实时状态探测协议：模块 App 主动向每个作用域进程要一份「当前事实」，状态页不再依赖
 * 「用户先触发过、日志里留下过事件」。
 *
 * <p>两个方向：模块 App 用 {@link CodecIpc#ACTION_STATUS_PROBE}（只带 token 与随机 probe id，
 * 只读）请求；作用域用 {@link CodecIpc#ACTION_STATUS_REPORT} 定向回给模块 App 包，正文是进程
 * 信息 + libxposed 框架信息 + 能力键当前状态。</p>
 *
 * <p>能力键（见 {@link #scopeForKey}）由实时报告覆盖；活动类状态行（最近写入 / 重放 / 警告 /
 * 错误）本质是历史事件，仍由诊断存储提供，UI 按「实时 / 最近事件 / 未加载」区分来源。</p>
 *
 * <p>实时表由 {@link MLog#emitEvent} 旁路喂入（{@link #observe}），事件名与诊断状态行的判定
 * 来源一致，不会出现两套口径。</p>
 */
public final class StatusProbe {

    public static final String SCOPE_HOST = "host";
    public static final String SCOPE_BLUETOOTH = "bluetooth";
    public static final String SCOPE_WIRELESS_SETTINGS = "wirelesssettings";
    public static final String SCOPE_SETTINGS = "settings";
    public static final String MODULE_PKG = "xyz.melodylsp.codec";

    private static final long REPLY_MIN_INTERVAL_MS = 250L;

    private static final String[][] PROBE_TARGETS = {
            {CodecIpc.MELODY_PKG, SCOPE_HOST},
            {CodecIpc.BLUETOOTH_PKG, SCOPE_BLUETOOTH},
            {"com.oplus.wirelesssettings", SCOPE_WIRELESS_SETTINGS},
            {"com.android.settings", SCOPE_SETTINGS},
    };

    /** 能力键 → 负责该键的作用域；不在此表内的状态行保持事件驱动。 */
    private static final String[][] KEY_SCOPES = {
            {"scope.host", SCOPE_HOST},
            {"host.controller", SCOPE_HOST},
            {"hook.host", SCOPE_HOST},
            {"inject.detail", SCOPE_HOST},
            {"inject.onespace", SCOPE_HOST},
            {"scope.bluetooth", SCOPE_BLUETOOTH},
            {"bridge.codec", SCOPE_BLUETOOTH},
            {"bridge.le.bt", SCOPE_BLUETOOTH},
            {"native.patch.bitrate", SCOPE_BLUETOOTH},
            {"native.patch.fast_switch", SCOPE_BLUETOOTH},
            {"lhdc.link.bqr", SCOPE_BLUETOOTH},
            {"lhdc.link.choppy", SCOPE_BLUETOOTH},
            {"lhdc.link.queue", SCOPE_BLUETOOTH},
            {"lhdc.link.shadow", SCOPE_BLUETOOTH},
            {"scope.wirelesssettings", SCOPE_WIRELESS_SETTINGS},
            {"bridge.le.ws", SCOPE_WIRELESS_SETTINGS},
            {"scope.settings", SCOPE_SETTINGS},
    };

    /**
     * 事件名 → 状态键 + 状态来源。第三列以 * 开头时表示状态取自事件 kv：*status 走补丁状态
     * 映射，*count 表示 hook 计数（0 记为异常）。
     */
    private static final String[][] EVENT_RULES = {
            {"scope.host.start", "scope.host", "loaded"},
            {"scope.host.context.ready", "scope.host", "loaded"},
            {"controller.ready", "host.controller", "ready"},
            {"preference.fragment.hooked", "hook.host", "hooked"},
            {"detailmain.activity.hooked", "hook.host", "hooked"},
            {"onespace.activity.hooked", "hook.host", "hooked"},
            {"activity.scan.registered", "hook.host", "hooked"},
            {"hires_anchored.injected", "inject.detail", "injected"},
            {"detailmain_fallback.injected", "inject.detail", "injected"},
            {"onespace.injected", "inject.onespace", "injected"},
            {"scope.system.start", "scope.bluetooth", "loaded"},
            {"scope.system.context.ready", "scope.bluetooth", "loaded"},
            {"system.bridge.registered", "bridge.codec", "registered"},
            {"codec.bt.receiver.registered", "bridge.codec", "registered"},
            {"le.bt.receiver.registered", "bridge.le.bt", "registered"},
            {"scope.wirelesssettings.start", "scope.wirelesssettings", "loaded"},
            {"scope.settings.start", "scope.settings", "loaded"},
            {"scope.wirelesssettings.context.ready", "scope.wirelesssettings", "loaded"},
            {"le.ws.receiver.registered", "bridge.le.ws", "registered"},
            {"lhdc.memory_patch.fast_switch", "native.patch.fast_switch", "*status"},
            {"lhdc.memory_patch", "native.patch.bitrate", "*status"},
            {"lhdc.link.bqr_hooks", "lhdc.link.bqr", "*count"},
            {"lhdc.governor.choppy_hooks", "lhdc.link.choppy", "*count"},
            {"lhdc.governor.queue_hooks", "lhdc.link.queue", "*count"},
            {"lhdc.link.stage_d", "lhdc.link.shadow", "armed"},
    };

    /** 实时表：状态键 → {state, detail}。 */
    private static final Map<String, String[]> LIVE = new ConcurrentHashMap<>();

    private static volatile String scopeName;
    private static volatile String moduleApk = "";
    private static volatile Context applicationContext;
    private static volatile String frameworkName = "";
    private static volatile String frameworkVersion = "";
    private static volatile int frameworkApi;
    private static volatile boolean responderRegistered;
    private static volatile long lastReplyAt;

    private StatusProbe() {
    }

    /** 作用域进程在入口安装时调用一次，带上 libxposed 框架信息。 */
    public static void attachScope(String scope, String fwName, String fwVersion, int api,
                                   String loadedModuleApk) {
        scopeName = scope;
        moduleApk = loadedModuleApk == null ? "" : loadedModuleApk;
        frameworkName = fwName == null ? "" : fwName;
        frameworkVersion = fwVersion == null ? "" : fwVersion;
        frameworkApi = api;
        ensureResponder();
    }

    /** Application 创建后即可应答，不等待耳机连接、面板或业务事件。 */
    public static void attachContext(Context context) {
        if (context == null || scopeName == null) return;
        applicationContext = context.getApplicationContext();
        record("scope." + scopeName, "loaded");
        ensureResponder();
    }

    public static String packageForScope(String scope) {
        for (String[] target : PROBE_TARGETS) {
            if (target[1].equals(scope)) return target[0];
        }
        return null;
    }

    /** 探测目标包名列表（模块 App 侧使用）。 */
    public static String[] probeTargets() {
        String[] out = new String[PROBE_TARGETS.length];
        for (int i = 0; i < out.length; i++) out[i] = PROBE_TARGETS[i][0];
        return out;
    }

    /** 返回负责该状态键的作用域名；活动类状态行返回 null。 */
    public static String scopeForKey(String key) {
        if (key == null) return null;
        for (String[] entry : KEY_SCOPES) {
            if (entry[0].equals(key)) return entry[1];
        }
        return null;
    }

    /** 记录一条当前事实；同一进程内后写覆盖先写。 */
    public static void record(String key, String state, String detail) {
        if (key == null || state == null) return;
        LIVE.put(key, new String[] {state, detail == null ? "" : detail});
    }

    public static void record(String key, String state) {
        record(key, state, "");
    }

    /** 本进程实时表里的当前状态；没有记录过返回 {@code null}。 */
    static String liveState(String key) {
        String[] entry = key == null ? null : LIVE.get(key);
        return entry == null ? null : entry[0];
    }

    /** MLog.event 旁路入口：把已有结构化事件同步进实时表。 */
    public static void observe(String event, Object... kvPairs) {
        if (event == null) return;
        try {
            for (String[] rule : EVENT_RULES) {
                if (!rule[0].equals(event)) continue;
                record(rule[1], resolveState(rule[2], kvPairs), describe(rule[2], kvPairs));
                break;
            }
            ensureResponder();
        } catch (Throwable ignored) {
            // 状态旁路绝不能影响主日志路径。
        }
    }

    /** 作用域侧：构造报告 JSON（进程 + 框架 + 能力键）。 */
    static String buildReportJson(Context context) {
        JSONObject root = new JSONObject();
        try {
            root.put("scope", scopeName == null ? "" : scopeName);
            root.put("module_apk", moduleApk);
            root.put("pid", Process.myPid());
            root.put("process", processName(context));
            root.put("framework", frameworkName);
            root.put("framework_version", frameworkVersion);
            root.put("api", frameworkApi);
            JSONObject keys = new JSONObject();
            for (Map.Entry<String, String[]> entry : LIVE.entrySet()) {
                JSONArray pair = new JSONArray();
                pair.put(entry.getValue()[0]);
                pair.put(entry.getValue()[1]);
                keys.put(entry.getKey(), pair);
            }
            root.put("keys", keys);
        } catch (Throwable t) {
            MLog.w("status probe report build failed", t);
        }
        return root.toString();
    }

    /**
     * 注册探测接收器。请求只是只读快照，因此不要求发送者持有 OPlus 签名权限（模块 App 自身
     * 并未声明该权限）；Android 14+ 用框架提供的发送者身份校验为模块 App 包，旧版本依赖 token。
     */
    private static synchronized void ensureResponder() {
        if (responderRegistered || scopeName == null) return;
        Context context = applicationContext != null ? applicationContext : currentApplication();
        if (context == null) return;
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    handleProbe(ctx, this, intent);
                }
            };
            IntentFilter filter = new IntentFilter(CodecIpc.ACTION_STATUS_PROBE);
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            responderRegistered = true;
        } catch (Throwable t) {
            MLog.w("status probe responder registration failed", t);
        }
    }

    private static void handleProbe(Context context, BroadcastReceiver receiver, Intent intent) {
        if (context == null || intent == null
                || !CodecIpc.ACTION_STATUS_PROBE.equals(intent.getAction())) {
            return;
        }
        if (!CodecIpc.TOKEN.equals(intent.getStringExtra(CodecIpc.EXTRA_TOKEN))) return;
        if (TrustedBroadcasts.supportsSenderIdentity()) {
            TrustedBroadcasts.SenderIdentity sender = TrustedBroadcasts.captureSender(receiver);
            // 身份可用但不是模块 App：拒绝；身份不可用（发送方未共享身份）时按 token 放行——
            // 请求只是只读快照，且模块 App 需要在不依赖权限的前提下能拿到状态。
            if (sender != null && sender.available
                    && !TrustedBroadcasts.isTrustedSender(context, sender, MODULE_PKG)) {
                return;
            }
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastReplyAt < REPLY_MIN_INTERVAL_MS) return;
        lastReplyAt = now;
        try {
            Intent reply = new Intent(CodecIpc.ACTION_STATUS_REPORT);
            reply.setPackage(MODULE_PKG);
            reply.putExtra(CodecIpc.EXTRA_TOKEN, CodecIpc.TOKEN);
            reply.putExtra(CodecIpc.EXTRA_PROBE_ID,
                    intent.getLongExtra(CodecIpc.EXTRA_PROBE_ID, 0L));
            reply.putExtra(CodecIpc.EXTRA_STATUS_SCOPE, scopeName == null ? "" : scopeName);
            reply.putExtra(CodecIpc.EXTRA_STATUS_REPORT, buildReportJson(context));
            TrustedBroadcasts.send(context, reply);
        } catch (Throwable t) {
            MLog.w("status probe reply failed", t);
        }
    }

    private static Context currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getMethod("currentApplication");
            Object app = currentApplication.invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String processName(Context context) {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method current = activityThread.getMethod("currentProcessName");
            Object name = current.invoke(null);
            if (name instanceof String && !((String) name).isEmpty()) return (String) name;
        } catch (Throwable ignored) {
        }
        if (context != null && context.getApplicationInfo() != null
                && context.getApplicationInfo().processName != null) {
            return context.getApplicationInfo().processName;
        }
        return "";
    }

    private static String kv(String key, Object[] kvPairs) {
        if (kvPairs == null) return null;
        for (int i = 0; i + 1 < kvPairs.length; i += 2) {
            if (key.equals(String.valueOf(kvPairs[i]))) {
                return String.valueOf(kvPairs[i + 1]);
            }
        }
        return null;
    }

    private static String resolveState(String spec, Object[] kvPairs) {
        if (spec == null || !spec.startsWith("*")) return spec;
        String field = spec.substring(1);
        String value = kv(field, kvPairs);
        if ("count".equals(field)) {
            return parseCount(value) > 0 ? "hooked" : "attention";
        }
        if (value == null || value.isEmpty()) return "seen";
        return mapPatchStatus(value);
    }

    private static String describe(String spec, Object[] kvPairs) {
        if (spec == null || !spec.startsWith("*")) return "";
        String field = spec.substring(1);
        String value = kv(field, kvPairs);
        return value == null ? "" : field + "=" + value;
    }

    /** 与诊断存储保持同一套状态语义：ok / native / pending / attention / failed。 */
    static String mapPatchStatus(String status) {
        if ("patched".equals(status) || "already_patched".equals(status)) return "ok";
        if ("not_required".equals(status)) return "native";
        if ("pending".equals(status)) return "pending";
        if ("failed".equals(status)) return "failed";
        if ("unsupported".equals(status)) return "attention";
        return status;
    }

    private static int parseCount(String value) {
        if (value == null) return 0;
        try {
            return Integer.parseInt(value.trim());
        } catch (Throwable t) {
            return 0;
        }
    }
}
