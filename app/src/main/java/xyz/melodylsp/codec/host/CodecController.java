package xyz.melodylsp.codec.host;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListPopupWindow;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dalvik.system.DexFile;

import xyz.melodylsp.codec.bridge.CodecIpc;
import xyz.melodylsp.codec.bridge.CodecRequest;
import xyz.melodylsp.codec.bridge.CodecSnapshot;
import xyz.melodylsp.codec.bt.BluetoothCodecReflect;
import xyz.melodylsp.codec.diag.DiagnosticEvents;
import xyz.melodylsp.codec.label.CodecLabelTable;
import xyz.melodylsp.codec.storage.PreferenceStore;
import xyz.melodylsp.codec.util.MLog;
import xyz.melodylsp.codec.util.TrustedBroadcasts;

/**
 * Single host-side state owner. Each Surface attaches its {@link CodecPreferences} bag, the
 * controller refreshes UI state from {@code BluetoothA2dp.getCodecStatus} and routes write
 * intents through {@link CodecBridgeClient}.
 *
 * <p>Quality and sample-rate rows are plain {@code Preference} entries whose click handler
 * pops a native themed {@link ListPopupWindow}. We deliberately avoid {@code ListPreference}: R8
 * stripped {@code setEntries} / {@code setEntryValues} from the host APK because the host
 * never calls them in code, so the AOSP {@code ListPreferenceDialogFragmentCompat} crashes
 * the moment the user taps the row, regardless of how we try to populate the entries by
 * reflection.</p>
 */
public final class CodecController {

    private static final String ACTION_CODEC_CONFIG_CHANGED =
            "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED";
    private static final String ACTION_CONNECTION_STATE_CHANGED =
            "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED";
    private static final String EXTRA_CONNECTION_STATE = "android.bluetooth.profile.extra.STATE";
    private static final int SAMPLE_RATE_48000_BIT = 0x2;
    private static final int SAMPLE_RATE_96000_BIT = 0x8;
    private static final int SAMPLE_RATE_192000_BIT = 0x20;
    private static final int SAMPLE_RATE_48000_HZ = 48_000;
    private static final int CODEC_MODE_HIGH = 0;
    private static final int CODEC_MODE_AAC = 1;
    private static final int CODEC_MODE_STANDARD = 2;
    private static final long CLASSIC_RESTORE_WINDOW_MS = 30_000L;
    private static final long HIGH_QUALITY_RETRY_DELAY_MS = 900L;
    private static final long AAC_HIGH_QUALITY_WARMUP_DELAY_MS = 650L;
    private static final long REMEMBER_CONFIRM_RECHECK_DELAY_MS = 2_000L;
    private static final String STATE_RESTORING_CLASSIC =
            "\u6b63\u5728\u6062\u590d\u7ecf\u5178\u84dd\u7259\u97f3\u9891...";
    private static volatile boolean couiPopupDiscoveryAttempted;
    private static volatile String[] discoveredCouiPopupBinding;

    private final Context context;
    private final BluetoothCodecReflect reflect;
    private final CodecBridgeClient bridge;
    private final PreferenceStore prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final A2dpRouteReadiness routeReadiness;
    private final ConnectionStateReplayer replayer;
    private final SurfaceRescanRequester surfaceRescanRequester;
    private final Map<Object, Subscription> subscriptions = new HashMap<>();
    /** Latest A2DP state is device-scoped; a single global value can write earphone A to B. */
    private final Map<String, CodecSnapshot> lastSnapshotsByMac = new HashMap<>();
    private final xyz.melodylsp.codec.leaudio.LeAudioManager leAudioManager;
    private final Set<String> classicRestorePending = new LinkedHashSet<>();
    private final Map<String, Long> classicRestoreDeadlines = new HashMap<>();
    private final Map<String, CodecSnapshot> lastHighQualitySnapshots = new HashMap<>();
    private final Map<String, Long> codecWriteGenerations = new HashMap<>();
    private BroadcastReceiver memorySnapshotReceiver;
    private volatile boolean nativePatchUnsupported;

    public interface SurfaceRescanRequester {
        void request(String reason);
    }

    public CodecController(
            Context context,
            BluetoothCodecReflect reflect,
            CodecBridgeClient bridge,
            PreferenceStore prefs,
            SurfaceRescanRequester surfaceRescanRequester) {
        this.context = context.getApplicationContext();
        this.reflect = reflect;
        this.bridge = bridge;
        this.prefs = prefs;
        this.surfaceRescanRequester = surfaceRescanRequester;
        this.routeReadiness = new A2dpRouteReadiness(this.context);
        this.replayer = new ConnectionStateReplayer(
                this.context, bridge, prefs, routeReadiness);
        this.replayer.start();
        this.bridge.addSnapshotListener(this::onPushedSnapshot);
        this.leAudioManager = new xyz.melodylsp.codec.leaudio.LeAudioManager(
                this.context, this::onLeAudioStateChanged);
        registerActivityCleanup();
        registerMemorySnapshotRequestReceiver();
        prefs.emitDiagnosticSnapshot("controller_ready");
        registerNativePatchStateReceiver();
        queryNativePatchStateSoon();
    }

    public void onOfficialGameModeState(
            String mac,
            int type,
            boolean active,
            String source) {
        replayer.onOfficialGameModeState(mac, type, active, source);
        broadcastOfficialGameModeState(mac, type, active, source);
    }

    private void broadcastOfficialGameModeState(
            String mac,
            int type,
            boolean active,
            String source) {
        if (mac == null || mac.isEmpty()) return;
        Intent intent = new Intent(CodecIpc.ACTION_GAME_MODE_STATE);
        intent.setPackage(context.getPackageName());
        intent.putExtra(CodecIpc.EXTRA_TOKEN, CodecIpc.TOKEN);
        intent.putExtra(CodecIpc.EXTRA_MAC, mac);
        intent.putExtra(CodecIpc.EXTRA_GAME_MODE_ACTIVE, active);
        intent.putExtra(CodecIpc.EXTRA_GAME_MODE_TYPE, type);
        intent.putExtra(CodecIpc.EXTRA_GAME_MODE_SOURCE,
                source != null && !source.isEmpty() ? source : "host.game_sound");
        try {
            if (!TrustedBroadcasts.send(context, intent)) {
                MLog.w("game mode state broadcast was not delivered");
            }
        } catch (Throwable t) {
            MLog.w("game mode state broadcast failed", t);
        }
    }

    private void markClassicRestorePending(String mac) {
        if (mac == null) return;
        classicRestorePending.add(mac);
        classicRestoreDeadlines.put(mac, System.currentTimeMillis() + CLASSIC_RESTORE_WINDOW_MS);
    }

    private void clearClassicRestorePending(String mac) {
        if (mac == null) return;
        classicRestorePending.remove(mac);
        classicRestoreDeadlines.remove(mac);
    }

    private boolean isClassicRestorePending(String mac) {
        if (mac == null || !classicRestorePending.contains(mac)) return false;
        Long deadline = classicRestoreDeadlines.get(mac);
        if (deadline != null && deadline < System.currentTimeMillis()) {
            clearClassicRestorePending(mac);
            return false;
        }
        return true;
    }

    private void requestSurfaceRescan(String reason) {
        if (surfaceRescanRequester == null) return;
        try {
            surfaceRescanRequester.request(reason);
        } catch (Throwable t) {
            MLog.w("surface rescan request failed", t);
        }
    }

    private void requestSurfaceRescanDelayed(String reason, long delayMs) {
        mainHandler.postDelayed(() -> requestSurfaceRescan(reason), delayMs);
    }

    private void registerMemorySnapshotRequestReceiver() {
        if (memorySnapshotReceiver != null) return;
        String processName = resolveProcessName(context);
        if (!shouldRegisterMemorySnapshotReceiver(context, processName)) {
            MLog.event("remember.snapshot.receiver",
                    "registered", false,
                    "process", safeProcessName(processName),
                    "reason", "non_owner_process");
            return;
        }
        int priority = memorySnapshotReceiverPriority(context, processName);
        memorySnapshotReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent == null) return;
                if (!DiagnosticEvents.ACTION_MEMORY_SNAPSHOT_REQUEST.equals(intent.getAction())) return;
                prefs.emitDiagnosticSnapshot("request");
                if (isOrderedBroadcast()) {
                    abortBroadcast();
                }
            }
        };
        IntentFilter filter = new IntentFilter(DiagnosticEvents.ACTION_MEMORY_SNAPSHOT_REQUEST);
        filter.setPriority(priority);
        if (TrustedBroadcasts.registerExportedReceiver(
                context,
                memorySnapshotReceiver,
                filter,
                DiagnosticEvents.PERMISSION_MEMORY_SNAPSHOT_REQUEST,
                mainHandler)) {
            MLog.event("remember.snapshot.receiver",
                    "registered", true,
                    "process", safeProcessName(processName),
                    "priority", priority);
        } else {
            memorySnapshotReceiver = null;
            MLog.w("remember snapshot receiver registration failed");
        }
    }

    private static String resolveProcessName(Context context) {
        try {
            String name = Application.getProcessName();
            if (name != null && !name.isEmpty()) return name;
        } catch (Throwable ignored) {
        }
        try {
            Class<?> cls = Class.forName("android.app.ActivityThread");
            Object value = cls.getMethod("currentProcessName").invoke(null);
            if (value instanceof String && !((String) value).isEmpty()) return (String) value;
        } catch (Throwable ignored) {
        }
        try {
            return context != null ? context.getPackageName() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean shouldRegisterMemorySnapshotReceiver(Context context, String processName) {
        String packageName = packageName(context);
        if (packageName == null || packageName.isEmpty()) return true;
        if (processName == null || processName.isEmpty()) return true;
        if (packageName.equals(processName)) return true;
        return (packageName + ":fg").equals(processName);
    }

    private static int memorySnapshotReceiverPriority(Context context, String processName) {
        String packageName = packageName(context);
        if (packageName != null && !packageName.isEmpty()
                && (packageName + ":fg").equals(processName)) {
            return 1000;
        }
        return 0;
    }

    private static String packageName(Context context) {
        try {
            return context != null ? context.getPackageName() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String safeProcessName(String processName) {
        return processName != null && !processName.isEmpty() ? processName : "unknown";
    }

    private static String deviceKey(String mac) {
        if (mac == null) return null;
        String value = mac.trim();
        return value.isEmpty() ? null : value.toUpperCase(Locale.ROOT);
    }

    static boolean sameDevice(String first, String second) {
        String firstKey = deviceKey(first);
        String secondKey = deviceKey(second);
        return firstKey != null && firstKey.equals(secondKey);
    }

    private CodecSnapshot snapshotFor(Subscription sub) {
        return sub != null ? snapshotForMac(sub.mac) : null;
    }

    private CodecSnapshot snapshotForMac(String mac) {
        String key = deviceKey(mac);
        if (key == null) return null;
        CodecSnapshot snapshot = lastSnapshotsByMac.get(key);
        return snapshot != null && sameDevice(mac, snapshot.mac) ? snapshot : null;
    }

    private void cacheSnapshot(CodecSnapshot snapshot) {
        if (snapshot == null) return;
        String key = deviceKey(snapshot.mac);
        if (key != null) lastSnapshotsByMac.put(key, snapshot);
    }

    private void clearSnapshot(String mac) {
        String key = deviceKey(mac);
        if (key != null) lastSnapshotsByMac.remove(key);
    }

    private static boolean isSubscriptionActive(Subscription sub) {
        return sub != null && sub.active;
    }

    private void registerNativePatchStateReceiver() {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (TrustedBroadcasts.supportsSenderIdentity()
                        && !TrustedBroadcasts.isTrustedSender(
                        ctx,
                        TrustedBroadcasts.captureSender(this),
                        CodecIpc.BLUETOOTH_PKG)) {
                    MLog.w("native patch state rejected: untrusted sender");
                    return;
                }
                handleNativePatchState(intent);
            }
        };
        IntentFilter filter = new IntentFilter(CodecIpc.ACTION_NATIVE_PATCH_STATE);
        if (!TrustedBroadcasts.registerExportedReceiver(
                context,
                receiver,
                filter,
                TrustedBroadcasts.PERMISSION_BLUETOOTH_PRIVILEGED,
                mainHandler)) {
            MLog.w("native patch state receiver registration failed");
        }
    }

    private void queryNativePatchStateSoon() {
        mainHandler.postDelayed(this::queryNativePatchState, 300L);
        mainHandler.postDelayed(this::queryNativePatchState, 1_500L);
        mainHandler.postDelayed(this::queryNativePatchState, 5_000L);
    }

    private void queryNativePatchState() {
        try {
            Intent intent = new Intent(CodecIpc.ACTION_QUERY_NATIVE_PATCH);
            intent.setPackage(CodecIpc.BLUETOOTH_PKG);
            intent.putExtra(CodecIpc.EXTRA_TOKEN, CodecIpc.TOKEN);
            if (!TrustedBroadcasts.send(context, intent)) {
                MLog.w("native patch state query was not delivered");
            }
        } catch (Throwable t) {
            MLog.w("native patch state query failed", t);
        }
    }

    private void handleNativePatchState(Intent intent) {
        if (intent == null
                || !CodecIpc.ACTION_NATIVE_PATCH_STATE.equals(intent.getAction())
                || !CodecIpc.TOKEN.equals(intent.getStringExtra(CodecIpc.EXTRA_TOKEN))) {
            return;
        }
        String status = intent.getStringExtra(CodecIpc.EXTRA_NATIVE_PATCH_STATUS);
        int patched = intent.getIntExtra(CodecIpc.EXTRA_NATIVE_PATCH_PATCHED, -1);
        int original = intent.getIntExtra(CodecIpc.EXTRA_NATIVE_PATCH_ORIGINAL, -1);
        nativePatchUnsupported = "unsupported".equals(status)
                && patched == 0
                && original == 0;
        MLog.event("native.patch.state.recv",
                "status", status,
                "patched", patched,
                "original", original,
                "unsupported", nativePatchUnsupported);
    }

    /**
     * Re-render every subscription bound to {@code mac} when its LE Audio state changes. This
     * is what lets OneSpace — which has no LE Audio switch of its own — still react (codec name
     * → LC3, hide quality / sample-rate rows) when LE Audio is toggled from DetailMain or the
     * system.
     */
    private void onLeAudioStateChanged(String mac) {
        boolean leOn = leAudioManager.isEnabled(mac);
        boolean leConnected = leAudioManager.isConnected(mac);
        for (Subscription sub : subscriptions.values()) {
            if (!isSubscriptionActive(sub) || !sameDevice(sub.mac, mac)) continue;
            applyLeAudioToSwitch(sub);
            if (leOn) {
                if (isClassicRestorePending(mac)) {
                    // Disable is in flight and stale LE replies may still report enabled=true.
                    // Keep the classic rows visible while the A2DP profile comes back.
                    restoreClassicAudioRows(sub);
                    scheduleClassicAudioRefresh(sub);
                    continue;
                }
                if (!leConnected) {
                    renderLeAudioConnecting(sub);
                    continue;
                }
                // LC3 takes over the LE transport and A2DP reports DISCONNECTED. Keep the
                // injected block interactive instead of treating that as "no earphone".
                sub.connected = Boolean.TRUE;
                CodecSnapshot snap = snapshotFor(sub);
                if (snap != null && !Boolean.FALSE.equals(sub.connected)) {
                    renderSnapshot(snap, sub, /* fromCache= */ false);
                } else {
                    // LE Audio took over the transport; renderUnknown shows the LC3 state.
                    renderUnknown(sub);
                }
            } else {
                // LE Audio turned off: A2DP is coming back, so re-read the real codec status
                // instead of trusting the now-stale snapshot. Keep the pending flag until a
                // real A2DP snapshot arrives; Melody may briefly rebuild its PreferenceScreen
                // while the LE profile disconnects.
                boolean alreadyPending = isClassicRestorePending(mac);
                boolean shouldRestoreClassic = alreadyPending || sub.renderedLeAudioActive;
                if (!shouldRestoreClassic) {
                    refreshSnapshot(sub);
                    continue;
                }
                if (!alreadyPending) {
                    markClassicRestorePending(mac);
                }
                restoreClassicAudioRows(sub);
                if (!alreadyPending) {
                    scheduleClassicAudioRefresh(sub);
                    requestSurfaceRescanDelayed("le.disabled", 300L);
                    requestSurfaceRescanDelayed("le.disabled", 1600L);
                    requestSurfaceRescanDelayed("le.disabled", 4200L);
                }
            }
        }
    }

    /**
     * Tear down a Subscription when its host Activity is destroyed (TODO A5). The original code
     * relied on an {@code androidx.lifecycle.LifecycleEventObserver} whose FQN failed to resolve
     * under R8, so {@link Subscription#unregisterReceiver()} was never called: every OneSpace /
     * DetailMain visit leaked a {@link BroadcastReceiver} and a {@code subscriptions} map entry.
     * We instead listen on {@code Application.ActivityLifecycleCallbacks} — a framework class
     * whose name R8 cannot rename — and drop every subscription bound to the destroyed Activity.
     */
    private void registerActivityCleanup() {
        try {
            android.app.Application app = resolveApplication(context);
            if (app == null) {
                MLog.w("registerActivityCleanup: no Application; subscriptions cleaned lazily only");
                return;
            }
            app.registerActivityLifecycleCallbacks(new SubscriptionCleanupCallbacks());
            MLog.event("subscription.cleanup.registered");
        } catch (Throwable t) {
            MLog.w("registerActivityCleanup failed", t);
        }
    }

    private static android.app.Application resolveApplication(Context ctx) {
        if (ctx instanceof android.app.Application) return (android.app.Application) ctx;
        Context app = ctx != null ? ctx.getApplicationContext() : null;
        if (app instanceof android.app.Application) return (android.app.Application) app;
        return null;
    }

    /** Drop every subscription whose host Activity matches {@code activity}. */
    private void onActivityDestroyed(Activity activity) {
        if (activity == null) return;
        java.util.Iterator<Map.Entry<Object, Subscription>> it = subscriptions.entrySet().iterator();
        int removed = 0;
        while (it.hasNext()) {
            Subscription sub = it.next().getValue();
            if (sub == null) {
                it.remove();
                continue;
            }
            Activity host = sub.hostActivity != null ? sub.hostActivity.get() : null;
            if (host == null || host == activity) {
                sub.dispose();
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            MLog.event("subscription.cleanup", "removed", removed,
                    "remaining", subscriptions.size());
        }
    }

    private final class SubscriptionCleanupCallbacks
            implements android.app.Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(Activity activity, android.os.Bundle savedInstanceState) {
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityResumed(Activity activity) {
        }

        @Override
        public void onActivityPaused(Activity activity) {
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, android.os.Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
            // A configuration change creates a new Fragment/PreferenceScreen. Keeping the old
            // receiver alive until a later attach leaks the destroyed Activity and can render
            // asynchronous results into its dead view tree.
            CodecController.this.onActivityDestroyed(activity);
        }
    }

    /** Bind a {@link CodecPreferences} bag to a fragment lifecycle. */
    public void attach(String mac, CodecPreferences pref, Object fragment) {
        // If this fragment instance was already attached (host rebuilt its screen), retire the
        // previous subscription first so its BroadcastReceiver is not orphaned (TODO A5).
        Subscription previous = subscriptions.remove(fragment);
        if (previous != null) {
            previous.dispose();
        }
        Subscription sub = new Subscription(mac, pref, fragment);
        sub.hostActivity = new java.lang.ref.WeakReference<>(activityFromFragment(fragment));
        subscriptions.put(fragment, sub);

        wireClickListeners(sub);
        wireRememberToggle(sub);
        wireLeAudio(sub);
        sub.registerReceiver();
        mainHandler.post(() -> {
            if (isSubscriptionActive(sub)) refreshSnapshot(sub);
        });
    }

    /** Bring the optional LE Audio switch to life (TODO B1 / B2). No-op when absent. */
    private void wireLeAudio(Subscription sub) {
        // Always track LE Audio state for this MAC (even without a switch on this surface), so
        // OneSpace can hide the codec rows when LE Audio is on.
        leAudioManager.ensureTracking(sub.mac);
        if (sub.prefs.leAudioSwitch == null) return;
        ClassLoader cl = context.getClassLoader();
        Class<?> changeListenerCls = resolveChangeListenerInterface(cl, sub.prefs.leAudioSwitch);
        if (changeListenerCls != null) {
            Object listener = Proxy.newProxyInstance(cl, new Class[]{changeListenerCls},
                    (proxy, method, args) -> {
                        if (args == null || args.length < 2 || !(args[1] instanceof Boolean)) {
                            return false;
                        }
                        boolean requested = (Boolean) args[1];
                        // Show a confirmation dialog in melody's Activity context before
                        // sending the toggle request to the wirelesssettings bridge.
                        showLeAudioConfirmDialog(sub, requested);
                        // Return false: do not flip the switch yet. The authoritative state
                        // arrives via the bridge reply and applyLeAudioToSwitch flips it then.
                        return false;
                    });
            invokeSetChangeListener(sub.prefs.leAudioSwitch, listener, changeListenerCls);
        }
        applyLeAudioToSwitch(sub);
    }

    /**
     * Show a confirmation dialog in melody's Activity before toggling LE Audio. COUI cannot
     * inflate from a background privileged process, but it is safe with Melody's themed Activity;
     * use host Melody/COUI builders first, then host AppCompat with COUI styles, and keep the
     * framework dialog as a final fallback.
     */
    private void showLeAudioConfirmDialog(Subscription sub, boolean enable) {
        if (!isSubscriptionActive(sub)) return;
        Activity activity = resolveLiveActivity(sub);
        if (activity == null || activity.isFinishing()) {
            MLog.w("showLeAudioConfirmDialog: no live activity; skip");
            return;
        }
        String title = enable
                ? xyz.melodylsp.codec.leaudio.LeAudioStrings.DIALOG_TITLE_ON
                : xyz.melodylsp.codec.leaudio.LeAudioStrings.DIALOG_TITLE_OFF;
        String message = enable
                ? xyz.melodylsp.codec.leaudio.LeAudioStrings.DIALOG_MSG_ON
                : xyz.melodylsp.codec.leaudio.LeAudioStrings.DIALOG_MSG_OFF;
        String positive = enable
                ? xyz.melodylsp.codec.leaudio.LeAudioStrings.CONFIRM
                : xyz.melodylsp.codec.leaudio.LeAudioStrings.CONFIRM_OFF;
        String negative = xyz.melodylsp.codec.leaudio.LeAudioStrings.CANCEL;

        if (showMelodyAlertDialog(activity, title, message, positive, negative,
                () -> requestLeAudioToggle(sub, enable))) {
            return;
        }
        if (showStyledAppCompatDialog(activity, title, message, positive, negative,
                () -> requestLeAudioToggle(sub, enable))) {
            return;
        }

        AlertDialog dialog = new android.app.AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(positive, (d, w) -> {
                    d.dismiss();
                    requestLeAudioToggle(sub, enable);
                })
                .setNegativeButton(negative, (d, w) -> d.dismiss())
                .setCancelable(true)
                .create();
        dialog.show();
    }

    private void requestLeAudioToggle(Subscription sub, boolean enable) {
        if (!isSubscriptionActive(sub)) return;
        String mac = sub.mac;
        if (!enable) {
            markClassicRestorePending(mac);
            restoreClassicAudioRowsForMac(mac);
            requestSurfaceRescan("le.disable.request");
        } else {
            clearClassicRestorePending(mac);
        }
        leAudioManager.requestToggle(mac, enable);
        if (!enable) {
            scheduleClassicAudioRefreshForMac(mac);
            requestSurfaceRescanDelayed("le.disable.sent", 1200L);
            requestSurfaceRescanDelayed("le.disable.sent", 3500L);
            requestSurfaceRescanDelayed("le.disable.sent", 7000L);
            mainHandler.postDelayed(() -> {
                if (!isClassicRestorePending(mac)) return;
                if (leAudioManager.isEnabled(mac)) {
                    onLeAudioStateChanged(mac);
                } else {
                    scheduleClassicAudioRefreshForMac(mac);
                    requestSurfaceRescan("le.disable.waiting_classic");
                }
            }, 12_000L);
        }
    }

    private interface ConfirmAction {
        void run();
    }

    private static boolean showMelodyAlertDialog(
            Activity activity,
            String title,
            String message,
            String positive,
            String negative,
            ConfirmAction action) {
        String[] builders = {
                "R7.b",
                "D2.e",
                "E4.b",
                "h1.e",
                "com.oplus.melody.common.widget.MelodyAlertDialogBuilder",
                "o6.C1381b",
                "o6.b",
                "B2.e"
        };
        Set<String> builderNames = new LinkedHashSet<>();
        for (String name : builders) {
            builderNames.add(name);
        }
        discoverMelodyDialogBuilders(activity, builderNames);
        for (String name : builderNames) {
            try {
                Class<?> builderCls = Class.forName(name, false, activity.getClassLoader());
                if (!isHostCouiDialogBuilderClass(builderCls)) continue;
                Object builder = newMelodyDialogBuilder(activity, builderCls);
                if (builder == null) continue;
                configureAndShowDialog(builder, title, message, positive, negative, action);
                MLog.event("le.melody.dialog", "builder", name);
                return true;
            } catch (Throwable t) {
                MLog.w("LE Audio melody dialog builder failed: " + name, t);
            }
        }
        return false;
    }

    private static boolean showStyledAppCompatDialog(
            Activity activity,
            String title,
            String message,
            String positive,
            String negative,
            ConfirmAction action) {
        try {
            String[] builderNames = {
                    "androidx.appcompat.app.g$a",
                    "androidx.appcompat.app.AlertDialog$Builder",
                    "androidx.appcompat.app.AlertDialog$a"
            };
            for (String builderName : builderNames) {
                Class<?> builderCls;
                try {
                    builderCls = Class.forName(builderName, false, activity.getClassLoader());
                } catch (Throwable ignored) {
                    continue;
                }
                Object builder = newHostStyledBuilder(activity, builderCls);
                if (builder == null) continue;
                configureAndShowDialog(builder, title, message, positive, negative, action);
                MLog.event("le.melody.dialog", "builder", "appcompat+coui-style",
                        "class", builderName);
                return true;
            }
            return false;
        } catch (Throwable t) {
            MLog.w("LE Audio styled AppCompat dialog failed", t);
            return false;
        }
    }

    private static void discoverMelodyDialogBuilders(Activity activity, Set<String> out) {
        DexFile dex = null;
        try {
            String sourceDir = activity.getApplicationInfo() != null
                    ? activity.getApplicationInfo().sourceDir : null;
            if (sourceDir == null) return;
            dex = new DexFile(sourceDir);
            Enumeration<String> entries = dex.entries();
            int inspected = 0;
            int added = 0;
            while (entries.hasMoreElements() && inspected < 2000) {
                String name = entries.nextElement();
                if (!maybeHostDialogBuilderName(name)) continue;
                inspected++;
                try {
                    Class<?> cls = Class.forName(name, false, activity.getClassLoader());
                    if (isHostCouiDialogBuilderClass(cls)) {
                        out.add(name);
                        added++;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (added > 0) {
                MLog.event("le.melody.dialog.discovered", "count", added);
            }
        } catch (Throwable t) {
            MLog.w("LE Audio dialog dex scan failed", t);
        } finally {
            if (dex != null) {
                try {
                    dex.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static boolean maybeHostDialogBuilderName(String name) {
        if (name == null) return false;
        if (name.startsWith("android.")
                || name.startsWith("androidx.")
                || name.startsWith("java.")
                || name.startsWith("kotlin.")
                || name.startsWith("kotlinx.")) {
            return false;
        }
        if (name.contains("AlertDialog") || name.contains("DialogBuilder")) return true;
        int dot = name.indexOf('.');
        if (dot <= 0 || dot != name.lastIndexOf('.')) return false;
        String pkg = name.substring(0, dot);
        String simple = name.substring(dot + 1);
        return pkg.length() <= 3 && simple.length() <= 3;
    }

    private static boolean isHostCouiDialogBuilderClass(Class<?> cls) {
        if (!isDialogBuilderShape(cls)) return false;
        if (isAppCompatBuilderClass(cls)) return false;
        return hasAppCompatBuilderInHierarchy(cls);
    }

    private static boolean isDialogBuilderShape(Class<?> cls) {
        if (cls == null) return false;
        try {
            cls.getConstructor(Context.class);
        } catch (NoSuchMethodException e) {
            try {
                cls.getConstructor(Context.class, int.class);
            } catch (NoSuchMethodException ignored) {
                return false;
            }
        } catch (Throwable ignored) {
            return false;
        }
        return findMethod(cls, "setTitle", CharSequence.class) != null
                && findMethod(cls, "setMessage", CharSequence.class) != null
                && findMethod(cls, "setPositiveButton", CharSequence.class,
                android.content.DialogInterface.OnClickListener.class) != null
                && findMethod(cls, "setNegativeButton", CharSequence.class,
                android.content.DialogInterface.OnClickListener.class) != null
                && findMethod(cls, "show") != null;
    }

    private static boolean isAppCompatBuilderClass(Class<?> cls) {
        String name = cls != null ? cls.getName() : "";
        return "androidx.appcompat.app.g$a".equals(name)
                || "androidx.appcompat.app.AlertDialog$Builder".equals(name)
                || "androidx.appcompat.app.AlertDialog$a".equals(name);
    }

    private static boolean hasAppCompatBuilderInHierarchy(Class<?> cls) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            if (isAppCompatBuilderClass(c)) return true;
            c = c.getSuperclass();
        }
        return false;
    }

    private static Object newMelodyDialogBuilder(Activity activity, Class<?> builderCls) {
        try {
            try {
                return builderCls.getConstructor(Context.class).newInstance(activity);
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) {
            }
            String[] styles = {
                    "COUIAlertDialog_BottomWarning",
                    "COUIAlertDialog_Center",
                    "COUIAlertDialog_Bottom"
            };
            for (String name : styles) {
                int style = activity.getResources().getIdentifier(
                        name, "style", activity.getPackageName());
                if (style == 0) continue;
                try {
                    return builderCls.getConstructor(Context.class, int.class)
                            .newInstance(activity, style);
                } catch (NoSuchMethodException ignored) {
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            return null;
        }
        return null;
    }

    private static Object newHostStyledBuilder(Activity activity, Class<?> builderCls) {
        String[] styles = {
                "COUIAlertDialog_BottomWarning",
                "COUIAlertDialog_Bottom",
                "COUIAlertDialog_Center"
        };
        for (String name : styles) {
            int style = activity.getResources().getIdentifier(
                    name, "style", activity.getPackageName());
            if (style == 0) continue;
            try {
                return builderCls.getConstructor(Context.class, int.class)
                        .newInstance(activity, style);
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) {
            }
        }
        try {
            return builderCls.getConstructor(Context.class).newInstance(activity);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void configureAndShowDialog(
            Object builder,
            String title,
            String message,
            String positive,
            String negative,
            ConfirmAction action) throws Exception {
        invokeDialogBuilder(builder, "setTitle",
                new Class[]{CharSequence.class}, new Object[]{title});
        invokeDialogBuilder(builder, "setMessage",
                new Class[]{CharSequence.class}, new Object[]{message});
        android.content.DialogInterface.OnClickListener ok = (dialog, which) -> {
            dialog.dismiss();
            action.run();
        };
        android.content.DialogInterface.OnClickListener cancel =
                (dialog, which) -> dialog.dismiss();
        invokeDialogBuilder(builder, "setPositiveButton",
                new Class[]{
                        CharSequence.class,
                        android.content.DialogInterface.OnClickListener.class
                },
                new Object[]{positive, ok});
        invokeDialogBuilder(builder, "setNegativeButton",
                new Class[]{
                        CharSequence.class,
                        android.content.DialogInterface.OnClickListener.class
                },
                new Object[]{negative, cancel});
        invokeDialogBuilder(builder, "setCancelable",
                new Class[]{boolean.class}, new Object[]{true});
        Method show = findMethod(builder.getClass(), "show");
        if (show == null) {
            throw new NoSuchMethodException("show");
        }
        show.setAccessible(true);
        show.invoke(builder);
    }

    private static void invokeDialogBuilder(
            Object builder, String name, Class<?>[] params, Object[] args) throws Exception {
        Method method = findMethod(builder.getClass(), name, params);
        if (method == null) {
            throw new NoSuchMethodException(name);
        }
        method.setAccessible(true);
        method.invoke(builder, args);
    }

    private static Method findMethod(Class<?> startCls, String name, Class<?>... params) {
        Class<?> cls = startCls;
        while (cls != null && cls != Object.class) {
            try {
                return cls.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
                cls = cls.getSuperclass();
            }
        }
        return null;
    }

    /** Reflect the tracked LE Audio state onto the switch widget (visibility + checked + summary). */
    private void applyLeAudioToSwitch(Subscription sub) {
        if (!isSubscriptionActive(sub)) return;
        Object sw = sub.prefs.leAudioSwitch;
        if (sw == null) return;
        boolean supported = leAudioManager.isSupported(sub.mac);
        PrefRef.setVisible(sw, supported);
        if (!supported) return;
        boolean enabled = leAudioManager.isEnabled(sub.mac);
        boolean connected = leAudioManager.isConnected(sub.mac);
        PrefRef.setChecked(sw, enabled);
        PrefRef.setSummary(sw,
                !enabled
                        ? Strings.LE_AUDIO_SUMMARY_OFF
                        : connected
                        ? Strings.LE_AUDIO_SUMMARY_ON
                        : Strings.LE_AUDIO_SUMMARY_CONNECTING);
    }

    private void restoreClassicAudioRowsForMac(String mac) {
        if (mac == null) return;
        markClassicRestorePending(mac);
        for (Subscription sub : subscriptions.values()) {
            if (isSubscriptionActive(sub) && sameDevice(mac, sub.mac)) {
                restoreClassicAudioRows(sub);
            }
        }
    }

    private void scheduleClassicAudioRefreshForMac(String mac) {
        if (mac == null) return;
        for (Subscription sub : subscriptions.values()) {
            if (isSubscriptionActive(sub) && sameDevice(mac, sub.mac)) {
                scheduleClassicAudioRefresh(sub);
            }
        }
    }

    private void restoreClassicAudioRows(Subscription sub) {
        if (!isSubscriptionActive(sub)) return;
        sub.connected = Boolean.TRUE;
        sub.renderedLeAudioActive = false;
        if (sub.prefs.codecDisplay != null) {
            PrefRef.setTitle(sub.prefs.codecDisplay,
                    Strings.CODEC_BLOCK_TITLE + " : " + STATE_RESTORING_CLASSIC);
        }
        PrefRef.setVisible(sub.prefs.qualityOption, true);
        PrefRef.setVisible(sub.prefs.sampleRateOption, true);
        if (PrefRef.isVisible(sub.prefs.codecModeOption)) {
            PrefRef.setSummary(sub.prefs.codecModeOption, STATE_RESTORING_CLASSIC);
        }
        PrefRef.setSummary(sub.prefs.qualityOption, STATE_RESTORING_CLASSIC);
        PrefRef.setSummary(sub.prefs.sampleRateOption, STATE_RESTORING_CLASSIC);
        setBlockDisabled(sub, true);
        if (sub.prefs.leAudioSwitch != null) {
            PrefRef.setVisible(sub.prefs.leAudioSwitch, leAudioManager.isSupported(sub.mac));
            PrefRef.setChecked(sub.prefs.leAudioSwitch, false);
            PrefRef.setSummary(sub.prefs.leAudioSwitch, Strings.LE_AUDIO_SUMMARY_OFF);
        }
        if (sub.prefs.rememberToggle != null) {
            PrefRef.setChecked(sub.prefs.rememberToggle, prefs.isRemembered(sub.mac));
        }
    }

    private void scheduleClassicAudioRefresh(Subscription sub) {
        if (!isSubscriptionActive(sub)) return;
        refreshSnapshot(sub);
        refreshSnapshotDelayed(sub, 600L);
        refreshSnapshotDelayed(sub, 1200L);
        refreshSnapshotDelayed(sub, 2500L);
        refreshSnapshotDelayed(sub, 4500L);
        refreshSnapshotDelayed(sub, 7000L);
    }

    /**
     * Wire up click listeners on the codec, quality and sample-rate Preferences. Tap → pop a
     * hand-rolled floating dialog with the current options. We resolve
     * {@code OnPreferenceClickListener} via reflection because the inner-class FQN is
     * R8-renamed inside the host APK.
     */
    private void wireClickListeners(Subscription sub) {
        ClassLoader cl = context.getClassLoader();
        Class<?> clickListenerCls = resolveClickListenerInterface(cl, sub.prefs.qualityOption);
        if (clickListenerCls == null) {
            MLog.w("OnPreferenceClickListener interface not resolvable; UI is read-only");
            return;
        }
        Object codecModeListener = Proxy.newProxyInstance(cl, new Class[]{clickListenerCls},
                (proxy, method, args) -> {
                    try {
                        Object sourcePref = args != null && args.length > 0
                                ? args[0] : sub.prefs.codecModeOption;
                        showCodecModePicker(sub, sourcePref);
                    } catch (Throwable t) {
                        MLog.e("showCodecModePicker failed", t);
                        Toast.makeText(context, Strings.TOAST_APPLY_FAILED, Toast.LENGTH_SHORT).show();
                    }
                    return true;
                });
        Object qualityListener = Proxy.newProxyInstance(cl, new Class[]{clickListenerCls},
                (proxy, method, args) -> {
                    try {
                        Object sourcePref = args != null && args.length > 0
                                ? args[0] : sub.prefs.qualityOption;
                        showQualityPicker(sub, sourcePref);
                    } catch (Throwable t) {
                        MLog.e("showQualityPicker failed", t);
                        Toast.makeText(context, Strings.TOAST_APPLY_FAILED, Toast.LENGTH_SHORT).show();
                    }
                    return true;
                });
        Object sampleListener = Proxy.newProxyInstance(cl, new Class[]{clickListenerCls},
                (proxy, method, args) -> {
                    try {
                        Object sourcePref = args != null && args.length > 0
                                ? args[0] : sub.prefs.sampleRateOption;
                        showSampleRatePicker(sub, sourcePref);
                    } catch (Throwable t) {
                        MLog.e("showSampleRatePicker failed", t);
                        Toast.makeText(context, Strings.TOAST_APPLY_FAILED, Toast.LENGTH_SHORT).show();
                    }
                    return true;
                });
        if (sub.prefs.codecDisplay != null
                && sub.prefs.codecDisplay != sub.prefs.codecModeOption) {
            invokeSetClickListener(sub.prefs.codecDisplay, codecModeListener, clickListenerCls);
        }
        invokeSetClickListener(sub.prefs.codecModeOption, codecModeListener, clickListenerCls);
        invokeSetClickListener(sub.prefs.qualityOption, qualityListener, clickListenerCls);
        invokeSetClickListener(sub.prefs.sampleRateOption, sampleListener, clickListenerCls);
    }

    /** Wire the remember toggle's change handler. Safe no-op when the toggle is absent. */
    private void wireRememberToggle(Subscription sub) {
        if (sub.prefs.rememberToggle == null) return;
        ClassLoader cl = context.getClassLoader();
        Class<?> changeListenerCls = resolveChangeListenerInterface(cl, sub.prefs.rememberToggle);
        if (changeListenerCls == null) return;
        Object listener = Proxy.newProxyInstance(cl, new Class[]{changeListenerCls},
                (proxy, method, args) -> {
                    if (args == null || args.length < 2) return false;
                    return handleRememberChange(sub, args[1]);
                });
        invokeSetChangeListener(sub.prefs.rememberToggle, listener, changeListenerCls);
    }

    /**
     * Pop the official-style high-quality / standard codec mode selector. High-quality and
     * standard modes write the target codec directly, then sync the platform optional flag in the
     * background so OPPO's slow optional confirmation path never blocks the visible switch.
     */
    private void showCodecModePicker(Subscription sub, Object sourcePref) {
        if (!ensureA2dpReadyForUser(sub)) return;
        CodecSnapshot snapshot = snapshotFor(sub);
        if (snapshot == null) {
            Toast.makeText(context, Strings.STATE_CODEC_UNKNOWN, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isCodecModeSwitchAvailable(snapshot)) {
            Toast.makeText(context,
                    Strings.TOAST_CODEC_MODE_UNSUPPORTED, Toast.LENGTH_SHORT).show();
            return;
        }
        ArrayList<CharSequence> entryList = new ArrayList<>();
        ArrayList<Integer> actionList = new ArrayList<>();
        int checked = -1;
        if (isHighQualityChoiceAvailable(snapshot)) {
            if (isHighQualityMode(snapshot)) {
                checked = entryList.size();
            }
            entryList.add(codecModeEntry(snapshot, true));
            actionList.add(CODEC_MODE_HIGH);
        }
        if (isAacChoiceAvailable(snapshot)) {
            if (snapshot.activeCodecType == CodecLabelTable.CODEC_AAC) {
                checked = entryList.size();
            }
            entryList.add(Strings.CODEC_LABEL_AAC);
            actionList.add(CODEC_MODE_AAC);
        }
        if (isStandardChoiceAvailable(snapshot)) {
            if (isStandardMode(snapshot)) {
                checked = entryList.size();
            }
            entryList.add(codecModeEntry(snapshot, false));
            actionList.add(CODEC_MODE_STANDARD);
        }
        if (entryList.isEmpty()) {
            Toast.makeText(context,
                    Strings.TOAST_CODEC_MODE_UNSUPPORTED, Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence[] entries = entryList.toArray(new CharSequence[0]);
        int[] actions = new int[actionList.size()];
        for (int i = 0; i < actionList.size(); i++) actions[i] = actionList.get(i);
        Context dialogContext = resolveLiveDialogContext(sub);
        if (dialogContext == null) {
            MLog.w("showCodecModePicker skipped: no live activity context");
            return;
        }
        try {
            showChoicePopup(sub, sourcePref, entries, checked, which -> {
                int action = actions[which];
                if (action == CODEC_MODE_AAC) {
                    if (snapshot.activeCodecType == CodecLabelTable.CODEC_AAC) {
                        refreshSnapshot(sub);
                    } else {
                        applyCodecTypeWrite(sub, snapshot, CodecLabelTable.CODEC_AAC);
                    }
                } else if (action == CODEC_MODE_STANDARD
                        && isStandardMode(snapshot)) {
                    refreshSnapshot(sub);
                } else if (action == CODEC_MODE_HIGH) {
                    applyHighQualityCodecWrite(sub, snapshot);
                } else {
                    applyStandardCodecWrite(sub, snapshot);
                }
            });
        } catch (Throwable t) {
            MLog.e("showCodecModePicker dialog.show failed", t);
            Toast.makeText(context, Strings.TOAST_APPLY_FAILED, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Pop a floating dialog letting the user pick a quality step. Picks a sensible options list
     * even when AOSP {@code getCodecsSelectableCapabilities} returned nothing for this codec
     * (vendor codec quirk on every OPPO LHDC variant).
     */
    private void showQualityPicker(Subscription sub, Object sourcePref) {
        if (!ensureA2dpReadyForUser(sub)) return;
        CodecSnapshot snapshot = snapshotFor(sub);
        if (snapshot == null) {
            Toast.makeText(context, Strings.STATE_CODEC_UNKNOWN, Toast.LENGTH_SHORT).show();
            return;
        }
        long[] options = qualityOptionsForUi(snapshot);
        boolean preserveLhdcHighBits = CodecLabelTable.isLhdc(snapshot.activeCodecType);
        if (options == null || options.length == 0) {
            Toast.makeText(context,
                    "当前编解码器不支持播放质量调整", Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence[] entries = new CharSequence[options.length];
        int checked = -1;
        for (int i = 0; i < options.length; i++) {
            entries[i] = CodecLabelTable.qualityLabel(context, snapshot.activeCodecType, options[i]);
            if (qualityValueMatches(snapshot.activeCodecType,
                    options[i], snapshot.activeCodecSpecific1)) {
                checked = i;
            }
        }
        long[] finalOptions = options;
        Context dialogContext = resolveLiveDialogContext(sub);
        if (dialogContext == null) {
            MLog.w("showQualityPicker skipped: no live activity context");
            return;
        }
        boolean finalPreserveLhdcHighBits = preserveLhdcHighBits;
        try {
            showChoicePopup(sub, sourcePref, entries, checked, which -> {
                long picked = finalOptions[which];
                if (finalPreserveLhdcHighBits) {
                    picked = (snapshot.activeCodecSpecific1 & ~0xFFL) | (picked & 0xFFL);
                }
                CodecRequest.Builder builder = CodecRequest.fromActive(snapshot)
                        .withSpecific1(picked);
                builder.withSampleRate(linkedSampleRateForQuality(snapshot, picked));
                CodecRequest req = builder.build();
                applyWrite(sub, req);
            });
        } catch (Throwable t) {
            MLog.e("showQualityPicker dialog.show failed", t);
            Toast.makeText(context, Strings.TOAST_APPLY_FAILED, Toast.LENGTH_SHORT).show();
        }
    }

    private static long[] qualityOptionsForUi(CodecSnapshot snapshot) {
        if (snapshot == null) return new long[0];
        if (CodecLabelTable.isLhdc(snapshot.activeCodecType)) {
            return CodecLabelTable.qualityFallback(snapshot.activeCodecType);
        }
        long[] options = snapshot.selectableCodecSpecific1;
        if (options == null || options.length == 0) {
            options = CodecLabelTable.qualityFallback(snapshot.activeCodecType);
        }
        return options != null ? options : new long[0];
    }

    private void showSampleRatePicker(Subscription sub, Object sourcePref) {
        if (!ensureA2dpReadyForUser(sub)) return;
        CodecSnapshot snapshot = snapshotFor(sub);
        if (snapshot == null) {
            Toast.makeText(context, Strings.STATE_CODEC_UNKNOWN, Toast.LENGTH_SHORT).show();
            return;
        }
        int rateMask = snapshot.selectableSampleRateMask;
        if (rateMask == 0 || CodecSnapshot.decodeSampleRateBits(rateMask).length == 0) {
            rateMask = sampleRateFallbackMask(snapshot.activeCodecType, snapshot.activeSampleRate);
        }
        int[] rates = CodecSnapshot.decodeSampleRateBits(rateMask);
        if (rates.length == 0) {
            Toast.makeText(context,
                    "当前编解码器没有可调采样率", Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence[] entries = new CharSequence[rates.length];
        int activeHz = sampleRateBitToHz(snapshot.activeSampleRate);
        if (activeHz <= 0) activeHz = SAMPLE_RATE_48000_HZ;
        int checked = -1;
        for (int i = 0; i < rates.length; i++) {
            entries[i] = CodecLabelTable.sampleRateLabel(rates[i]);
            if (rates[i] == activeHz) checked = i;
        }
        int[] finalRates = rates;
        Context dialogContext = resolveLiveDialogContext(sub);
        if (dialogContext == null) {
            MLog.w("showSampleRatePicker skipped: no live activity context");
            return;
        }
        try {
            showChoicePopup(sub, sourcePref, entries, checked, which -> {
                int hz = finalRates[which];
                int bit = sampleRateHzToBit(hz);
                if (bit < 0) return;
                CodecRequest.Builder builder = CodecRequest.fromActive(snapshot)
                        .withSampleRate(bit);
                builder.withSpecific1(linkedQualityForSampleRate(snapshot, bit));
                CodecRequest req = builder.build();
                applyWrite(sub, req);
            });
        } catch (Throwable t) {
            MLog.e("showSampleRatePicker dialog.show failed", t);
            Toast.makeText(context, Strings.TOAST_APPLY_FAILED, Toast.LENGTH_SHORT).show();
        }
    }

    private interface ChoiceCallback {
        void onChoice(int which);
    }

    private static void showChoicePopup(
            Subscription sub,
            Object sourcePref,
            CharSequence[] entries,
            int checked,
            ChoiceCallback callback) {
        Activity activity = resolveLiveActivity(sub);
        if (activity == null || activity.getWindow() == null) return;
        View anchor = findPreferenceView(sub, activity, sourcePref);
        if (anchor == null) anchor = fragmentRootView(sub.fragment);
        if (anchor == null) anchor = activity.getWindow().getDecorView();
        if (anchor == null) return;

        Context couiContext = resolveLiveDialogContext(sub);
        if (couiContext == null) couiContext = activity;
        if (showCouiChoicePopup(couiContext, anchor, entries, checked, callback)) return;

        // Compatibility fallback for Melody builds where the bundled COUI popup was renamed.
        ArrayAdapter<CharSequence> adapter = new ArrayAdapter<>(
                activity,
                android.R.layout.simple_list_item_single_choice,
                android.R.id.text1,
                entries);
        ListPopupWindow popup = new ListPopupWindow(activity);
        popup.setAdapter(adapter);
        popup.setAnchorView(anchor);
        popup.setModal(true);
        popup.setDropDownGravity(Gravity.END);
        popup.setWidth(dp(activity, 208));
        popup.setVerticalOffset(-anchor.getHeight());
        popup.setOnItemClickListener((parent, view, position, id) -> {
            popup.dismiss();
            callback.onChoice(position);
        });
        popup.show();

        ListView list = popup.getListView();
        if (list != null) {
            list.setChoiceMode(AbsListView.CHOICE_MODE_SINGLE);
            if (checked >= 0 && checked < entries.length) {
                list.setItemChecked(checked, true);
                popup.setSelection(checked);
            }
        }
    }

    private static boolean showCouiChoicePopup(
            Context context,
            View anchor,
            CharSequence[] entries,
            int checked,
            ChoiceCallback callback) {
        String[][] bindings = {
                {
                        "L2.e", "L2.o", "g", "i", "j", "v"
                },
                {
                        "J2.C0399e", "J2.q", "f2733g", "f2735i", "f2736j", "f2645v"
                },
                {
                        "Y1.C0480e", "Y1.o", "f", "f5923g", "f5924h", "f5840v"
                }
        };
        for (String[] binding : bindings) {
            if (tryShowCouiChoicePopup(
                    context, anchor, entries, checked, callback, binding)) return true;
        }

        String[] discovered = discoverCouiPopupBinding(context);
        if (discovered != null
                && tryShowCouiChoicePopup(
                        context, anchor, entries, checked, callback, discovered)) {
            return true;
        }
        MLog.event("ui.choice.popup", "impl", "framework_fallback", "count", entries.length);
        return false;
    }

    private static boolean tryShowCouiChoicePopup(
            Context context,
            View anchor,
            CharSequence[] entries,
            int checked,
            ChoiceCallback callback,
            String[] binding) {
        try {
            ClassLoader loader = context.getClassLoader();
            Class<?> popupClass = Class.forName(binding[0], false, loader);
            Class<?> itemClass = Class.forName(binding[1], false, loader);
            Object popupObject = popupClass.getConstructor(Context.class).newInstance(context);
            if (!(popupObject instanceof PopupWindow)) return false;
            PopupWindow popup = (PopupWindow) popupObject;

            ArrayList<Object> items = new ArrayList<>(entries.length);
            for (int i = 0; i < entries.length; i++) {
                Object item = itemClass.getDeclaredConstructor().newInstance();
                setField(item, binding[2], String.valueOf(entries[i]));
                setField(item, binding[3], i == checked);
                setField(item, binding[4], true);
                items.add(item);
            }

            Method setItems = findCouiSetItemsMethod(popupClass);
            if (setItems == null) throw new NoSuchMethodException(binding[0] + " setItems");
            setItems.invoke(popupObject, items);
            popup.setTouchable(true);
            popup.setFocusable(true);
            popup.setOutsideTouchable(true);
            popup.update();

            AdapterView.OnItemClickListener listener = (parent, view, position, id) -> {
                popup.dismiss();
                callback.onChoice(position);
            };
            setField(popupObject, binding[5], listener);

            Method show = findCouiPopupShowMethod(popupClass);
            if (show == null) throw new NoSuchMethodException(binding[0] + " show");
            // COUI treats explicit offsets as a point inside the anchor.  Put that point on
            // the row's physical right edge so the popup opens inward and its right edge
            // follows the preference/card boundary.  MIN_VALUE asks COUI to center on the
            // whole anchor, which leaves the popup fixed in the middle of a full-width row.
            int anchorX = anchor.getWidth();
            int anchorY = anchor.getHeight() / 2;
            Class<?>[] params = show.getParameterTypes();
            if (View.class.isAssignableFrom(params[0])) {
                show.invoke(popupObject, anchor, anchorX, anchorY);
            } else {
                show.invoke(popupObject, anchorX, anchorY, anchor);
            }
            MLog.event("ui.choice.popup", "impl", binding[0], "count", entries.length);
            return true;
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            MLog.event("ui.choice.popup.candidate_failed",
                    "impl", binding[0],
                    "error", cause.getClass().getSimpleName());
            return false;
        }
    }

    private static Method findCouiSetItemsMethod(Class<?> popupClass) {
        for (Method method : popupClass.getMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 1
                    && List.class.isAssignableFrom(params[0])
                    && method.getReturnType() == void.class) {
                return method;
            }
        }
        return null;
    }

    private static Method findCouiPopupShowMethod(Class<?> popupClass) {
        for (Method method : popupClass.getMethods()) {
            if (method.getDeclaringClass() != popupClass
                    || method.getReturnType() != void.class
                    || Modifier.isStatic(method.getModifiers())) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 3) continue;
            boolean viewFirst = View.class.isAssignableFrom(params[0])
                    && params[1] == int.class && params[2] == int.class;
            boolean viewLast = params[0] == int.class && params[1] == int.class
                    && View.class.isAssignableFrom(params[2]);
            if (viewFirst || viewLast) return method;
        }
        return null;
    }

    private static String[] discoverCouiPopupBinding(Context context) {
        if (couiPopupDiscoveryAttempted) return discoveredCouiPopupBinding;
        synchronized (CodecController.class) {
            if (couiPopupDiscoveryAttempted) return discoveredCouiPopupBinding;
            discoveredCouiPopupBinding = scanCouiPopupBinding(context);
            couiPopupDiscoveryAttempted = true;
            return discoveredCouiPopupBinding;
        }
    }

    private static String[] scanCouiPopupBinding(Context context) {
        DexFile dex = null;
        try {
            String sourceDir = context.getApplicationInfo() != null
                    ? context.getApplicationInfo().sourceDir : null;
            if (sourceDir == null) return null;
            dex = new DexFile(sourceDir);
            Enumeration<String> entries = dex.entries();
            ArrayList<String> compactClasses = new ArrayList<>();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement();
                if (isCompactObfuscatedClass(name)) compactClasses.add(name);
            }

            ClassLoader loader = context.getClassLoader();
            for (String popupName : compactClasses) {
                Class<?> popupClass;
                try {
                    popupClass = Class.forName(popupName, false, loader);
                } catch (Throwable ignored) {
                    continue;
                }
                Class<?> parent = popupClass.getSuperclass();
                if (parent == null
                        || !"com.coui.appcompat.poplist.a".equals(parent.getName())
                        || !PopupWindow.class.isAssignableFrom(popupClass)
                        || findCouiSetItemsMethod(popupClass) == null
                        || findCouiPopupShowMethod(popupClass) == null) {
                    continue;
                }
                try {
                    popupClass.getConstructor(Context.class);
                } catch (Throwable ignored) {
                    continue;
                }

                Field listener = firstFieldOfType(
                        popupClass, AdapterView.OnItemClickListener.class);
                if (listener == null) continue;
                String packageName = popupClass.getPackage() != null
                        ? popupClass.getPackage().getName() : "";
                for (String itemName : compactClasses) {
                    if (!itemName.startsWith(packageName + ".")) continue;
                    Class<?> itemClass;
                    try {
                        itemClass = Class.forName(itemName, false, loader);
                        itemClass.getDeclaredConstructor();
                    } catch (Throwable ignored) {
                        continue;
                    }
                    Field[] itemFields = popupItemFields(itemClass);
                    if (itemFields == null) continue;
                    String[] binding = {
                            popupClass.getName(),
                            itemClass.getName(),
                            itemFields[0].getName(),
                            itemFields[1].getName(),
                            itemFields[2].getName(),
                            listener.getName()
                    };
                    MLog.event("ui.choice.popup.discovered",
                            "impl", binding[0], "item", binding[1]);
                    return binding;
                }
            }
        } catch (Throwable t) {
            MLog.event("ui.choice.popup.discovery_failed",
                    "error", t.getClass().getSimpleName());
        } finally {
            if (dex != null) {
                try {
                    dex.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static boolean isCompactObfuscatedClass(String name) {
        if (name == null || name.indexOf('$') >= 0) return false;
        int dot = name.indexOf('.');
        if (dot <= 0 || dot != name.lastIndexOf('.')) return false;
        String packageName = name.substring(0, dot);
        return packageName.length() <= 3;
    }

    private static Field firstFieldOfType(Class<?> cls, Class<?> fieldType) {
        ArrayList<Field> matches = new ArrayList<>();
        for (Field field : cls.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && field.getType() == fieldType) {
                matches.add(field);
            }
        }
        if (matches.isEmpty()) return null;
        matches.sort((left, right) -> left.getName().compareTo(right.getName()));
        return matches.get(0);
    }

    /** Returns text, selected and enabled fields for the bundled PopupListItem model. */
    private static Field[] popupItemFields(Class<?> itemClass) {
        if (itemClass.getSuperclass() != Object.class
                || !Modifier.isFinal(itemClass.getModifiers())) return null;
        Field text = null;
        int stringCount = 0;
        int intCount = 0;
        int drawableCount = 0;
        int listCount = 0;
        ArrayList<Field> booleans = new ArrayList<>();
        for (Field field : itemClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            Class<?> type = field.getType();
            if (type == String.class) {
                text = field;
                stringCount++;
            }
            else if (type == boolean.class) booleans.add(field);
            else if (type == int.class) intCount++;
            else if ("android.graphics.drawable.Drawable".equals(type.getName())) drawableCount++;
            else if (ArrayList.class.isAssignableFrom(type)) listCount++;
        }
        if (stringCount != 1 || text == null || booleans.size() != 2 || intCount < 4
                || drawableCount != 1 || listCount != 1) return null;
        booleans.sort((left, right) -> left.getName().compareTo(right.getName()));
        return new Field[]{text, booleans.get(0), booleans.get(1)};
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field;
        try {
            field = target.getClass().getField(name);
        } catch (NoSuchFieldException ignored) {
            field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
        }
        field.set(target, value);
    }

    private static Activity resolveLiveActivity(Subscription sub) {
        if (!isSubscriptionActive(sub)) return null;
        Context ui = sub != null && sub.prefs != null ? sub.prefs.uiContext : null;
        Activity activity = findActivity(ui);
        if (activity == null && sub != null) {
            activity = activityFromFragment(sub.fragment);
        }
        if (activity == null) return null;
        if (activity.isFinishing()) return null;
        if (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed()) return null;
        return activity;
    }

    private static Context resolveLiveDialogContext(Subscription sub) {
        Context ui = sub != null && sub.prefs != null ? sub.prefs.uiContext : null;
        Activity activity = resolveLiveActivity(sub);
        if (activity == null) return null;
        return ui != null ? ui : activity;
    }

    private static Activity findActivity(Context ctx) {
        Context cur = ctx;
        while (cur != null) {
            if (cur instanceof Activity) return (Activity) cur;
            if (!(cur instanceof ContextWrapper)) return null;
            Context next = ((ContextWrapper) cur).getBaseContext();
            if (next == cur) return null;
            cur = next;
        }
        return null;
    }

    private static Activity activityFromFragment(Object fragment) {
        if (fragment == null) return null;
        try {
            Method m = fragment.getClass().getMethod("getActivity");
            Object activity = m.invoke(fragment);
            if (activity instanceof Activity) return (Activity) activity;
        } catch (Throwable ignored) {
        }
        try {
            Method m = fragment.getClass().getMethod("requireActivity");
            Object activity = m.invoke(fragment);
            if (activity instanceof Activity) return (Activity) activity;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static View findPreferenceView(Subscription sub, Activity activity, Object pref) {
        if (activity == null || activity.getWindow() == null || pref == null) return null;
        CharSequence title = PrefRef.getTitle(pref);
        View root = fragmentRootView(sub != null ? sub.fragment : null);
        View text = findTextView(root, title);
        if (text == null) {
            text = findTextView(root, PrefRef.getSummary(pref));
        }
        if (text == null) {
            root = activity.getWindow().getDecorView();
            text = findTextView(root, title);
            if (text == null) {
                text = findTextView(root, PrefRef.getSummary(pref));
            }
        }
        if (text == null) return null;
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int minHeight = dp(activity, 36);
        int maxHeight = dp(activity, 180);
        View cur = text;
        for (int i = 0; i < 8 && cur != null; i++) {
            int height = cur.getHeight();
            if (cur.getWidth() > metrics.widthPixels / 2
                    && height >= minHeight
                    && height <= maxHeight) {
                return cur;
            }
            Object parent = cur.getParent();
            cur = parent instanceof View ? (View) parent : null;
        }
        return text;
    }

    private static View fragmentRootView(Object fragment) {
        if (fragment == null) return null;
        try {
            Method m = fragment.getClass().getMethod("getView");
            Object view = m.invoke(fragment);
            return view instanceof View ? (View) view : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static View findTextView(View root, CharSequence text) {
        if (root == null || text == null || text.length() == 0) return null;
        if (root instanceof TextView) {
            CharSequence candidate = ((TextView) root).getText();
            if (candidate != null && text.toString().contentEquals(candidate)) {
                return root;
            }
        }
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = findTextView(group.getChildAt(i), text);
            if (found != null) return found;
        }
        return null;
    }

    /** Resolve {@code Preference.OnPreferenceClickListener} (1-arg, returns boolean). */
    private Class<?> resolveClickListenerInterface(ClassLoader cl, Object prefSample) {
        Class<?> prefBase = PrefRef.load(cl, "androidx.preference.Preference");
        if (prefBase == null) return null;
        Class<?> sampleCls = prefSample != null ? prefSample.getClass() : prefBase;
        Class<?> cls = sampleCls;
        while (cls != null && cls != Object.class) {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getParameterCount() != 1) continue;
                Class<?> p = m.getParameterTypes()[0];
                if (!p.isInterface()) continue;
                Method[] ifaceMethods = p.getDeclaredMethods();
                if (ifaceMethods.length != 1) continue;
                Method only = ifaceMethods[0];
                if (only.getReturnType() != boolean.class) continue;
                if (only.getParameterCount() != 1) continue;
                if (!prefBase.isAssignableFrom(only.getParameterTypes()[0])) continue;
                return p;
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    /** Resolve {@code Preference.OnPreferenceChangeListener} (2-arg, returns boolean). */
    private Class<?> resolveChangeListenerInterface(ClassLoader cl, Object prefSample) {
        Class<?> prefBase = PrefRef.load(cl, "androidx.preference.Preference");
        if (prefBase == null) return null;
        Class<?> sampleCls = prefSample != null ? prefSample.getClass() : prefBase;
        Class<?> cls = sampleCls;
        while (cls != null && cls != Object.class) {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getParameterCount() != 1) continue;
                Class<?> p = m.getParameterTypes()[0];
                if (!p.isInterface()) continue;
                Method[] ifaceMethods = p.getDeclaredMethods();
                if (ifaceMethods.length != 1) continue;
                Method only = ifaceMethods[0];
                if (only.getReturnType() != boolean.class) continue;
                if (only.getParameterCount() != 2) continue;
                if (!prefBase.isAssignableFrom(only.getParameterTypes()[0])) continue;
                return p;
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    private static void invokeSetClickListener(Object pref, Object listener, Class<?> ifaceCls) {
        invokeSetListener(pref, listener, ifaceCls, "setOnPreferenceClickListener");
    }

    private static void invokeSetChangeListener(Object pref, Object listener, Class<?> ifaceCls) {
        invokeSetListener(pref, listener, ifaceCls, "setOnPreferenceChangeListener");
    }

    private static void invokeSetListener(Object pref, Object listener, Class<?> ifaceCls,
            String label) {
        if (pref == null || listener == null) return;
        try {
            Method m = findUnaryAcceptingType(pref.getClass(), ifaceCls);
            if (m != null) {
                m.setAccessible(true);
                m.invoke(pref, listener);
            } else {
                MLog.w(label + ": no matching setter on " + pref.getClass().getName());
            }
        } catch (Throwable t) {
            MLog.w(label + " failed", t);
        }
    }

    private static Method findUnaryAcceptingType(Class<?> startCls, Class<?> paramType) {
        Class<?> cls = startCls;
        while (cls != null && cls != Object.class) {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getParameterCount() != 1) continue;
                if (m.isSynthetic() || m.isBridge()) continue;
                Class<?> p = m.getParameterTypes()[0];
                if (p == paramType) return m;
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    private boolean handleRememberChange(Subscription sub, Object value) {
        if (!isSubscriptionActive(sub) || !(value instanceof Boolean)) return false;
        boolean enabled = (Boolean) value;
        prefs.setRemembered(sub.mac, enabled);
        if (enabled) {
            boolean a2dpReady = isA2dpReadyOrUnknown(sub);
            if (a2dpReady) {
                captureInitialRememberedSnapshotAsync(sub.mac, "toggle_enabled");
            } else {
                MLog.event("remember.write.skip",
                        "reason", "a2dp_waiting_pending_capture",
                        "mac", A2dpRouteReadiness.redactMac(sub.mac));
            }
        }
        return true;
    }

    private void captureInitialRememberedSnapshotAsync(String mac, String reason) {
        if (mac == null || !prefs.isRemembered(mac) || prefs.readSnapshot(mac) != null) return;
        Thread worker = new Thread(() -> {
            CodecSnapshot snapshot = bridge.getStatus(mac);
            mainHandler.post(() -> {
                if (snapshot != null && sameDevice(mac, snapshot.mac)) {
                    maybeInitializeRememberedSnapshot(snapshot, reason);
                }
            });
        }, "MelodyCodecLsp-rememberCapture");
        worker.setDaemon(true);
        worker.start();
    }

    private void maybeInitializeRememberedSnapshot(CodecSnapshot snapshot, String reason) {
        if (snapshot == null || snapshot.mac == null) return;
        if (!prefs.isRemembered(snapshot.mac) || prefs.readSnapshot(snapshot.mac) != null) return;
        prefs.writeSnapshot(
                snapshot.mac,
                snapshot.activeCodecType,
                snapshot.activeCodecSpecific1,
                snapshot.activeSampleRate);
        MLog.event("remember.write.initialized",
                "mac", A2dpRouteReadiness.redactMac(snapshot.mac),
                "reason", reason,
                "codec", snapshot.activeCodecType,
                "specific1", snapshot.activeCodecSpecific1,
                "rate", snapshot.activeSampleRate);
    }

    private boolean ensureA2dpReadyForUser(Subscription sub) {
        if (!isSubscriptionActive(sub)) return false;
        if (isA2dpReadyOrUnknown(sub)) return true;
        renderA2dpWaiting(sub);
        Toast.makeText(context, Strings.TOAST_A2DP_WAITING, Toast.LENGTH_SHORT).show();
        MLog.event("a2dp.waiting.user",
                "mac", A2dpRouteReadiness.redactMac(sub != null ? sub.mac : null));
        return false;
    }

    private boolean isA2dpReadyOrUnknown(Subscription sub) {
        return isSubscriptionActive(sub) && routeReadiness.isReadyOrUnknown(sub.mac);
    }

    private void applyWrite(Subscription sub, CodecRequest request) {
        applyWrite(sub, request, null);
    }

    private void applyWrite(Subscription sub, CodecRequest request, WriteFailureHandler failureHandler) {
        applyWrite(sub, request, failureHandler, nextCodecWriteGeneration(sub));
    }

    private void applyWrite(
            Subscription sub,
            CodecRequest request,
            WriteFailureHandler failureHandler,
            long generation) {
        applyWrite(sub, request, failureHandler, generation, null);
    }

    private void applyWrite(
            Subscription sub,
            CodecRequest request,
            WriteFailureHandler failureHandler,
            long generation,
            WriteSuccessHandler successHandler) {
        applyWrite(sub, request, failureHandler, generation, successHandler, true);
    }

    private void applyWrite(
            Subscription sub,
            CodecRequest request,
            WriteFailureHandler failureHandler,
            long generation,
            WriteSuccessHandler successHandler,
            boolean rememberOnConfirmed) {
        if (!isSubscriptionActive(sub)
                || request == null
                || !sameDevice(sub.mac, request.mac)) {
            MLog.event("write.skip.device_mismatch",
                    "subMac", redactedMac(sub),
                    "requestMac", A2dpRouteReadiness.redactMac(
                            request != null ? request.mac : null));
            return;
        }
        if (!isA2dpReadyOrUnknown(sub)) {
            renderA2dpWaiting(sub);
            Toast.makeText(context, Strings.TOAST_A2DP_WAITING, Toast.LENGTH_SHORT).show();
            MLog.event("write.skip.a2dp_waiting",
                    "generation", generation,
                    "request", request);
            return;
        }
        replayer.onUserCodecWrite(sub != null ? sub.mac : null, request, "codec_write");
        bridge.setCodec(request, () -> isCurrentCodecWrite(sub, generation))
                .whenComplete((result, ex) -> mainHandler.post(() -> {
            if (!isCurrentCodecWrite(sub, generation)) {
                MLog.event("write.stale.ignore",
                        "generation", generation,
                        "request", request,
                        "outcome", result != null ? result.outcome : "exception");
                return;
            }
            if (ex != null) {
                MLog.e("setCodec future failed", ex);
                if (failureHandler != null && failureHandler.onFailure(null, ex)) return;
                showWriteFailedToast(request);
                refreshSnapshot(sub);
                return;
            }
            switch (result.outcome) {
                case CONFIRMED:
                    if (result.path == WriteResult.Path.SETTINGS_GLOBAL) {
                        Toast.makeText(context, Strings.BANNER_VIA_SETTINGS, Toast.LENGTH_LONG).show();
                    } else if (result.path == WriteResult.Path.ROOT_SHELL) {
                        Toast.makeText(context, Strings.BANNER_VIA_ROOT, Toast.LENGTH_LONG).show();
                    }
                    if (rememberOnConfirmed && prefs.isRemembered(sub.mac)) {
                        writeRememberedConfirmedSnapshot(sub, request, result.rollbackSnapshot);
                    }
                    if (successHandler != null && successHandler.onConfirmed(result)) return;
                    refreshSnapshot(sub);
                    break;
                case TIMEOUT_ROLLED_BACK:
                    if (rememberOnConfirmed
                            && failureHandler == null
                            && prefs.isRemembered(sub.mac)) {
                        recheckRememberedWriteAfterTimeout(sub, request, generation);
                    }
                    if (failureHandler != null && failureHandler.onFailure(result, null)) return;
                    showWriteFailedToast(request);
                    if (result.rollbackSnapshot != null) {
                        publish(result.rollbackSnapshot, sub);
                    } else {
                        refreshSnapshot(sub);
                    }
                    break;
                case FAILED:
                default:
                    if (failureHandler != null && failureHandler.onFailure(result, null)) return;
                    showWriteFailedToast(request);
                    refreshSnapshot(sub);
                    break;
            }
        }));
    }

    private void showWriteFailedToast(CodecRequest request) {
        Toast.makeText(context,
                shouldShowNativePatchUnsupportedToast(request)
                        ? Strings.TOAST_NATIVE_PATCH_UNSUPPORTED
                        : Strings.TOAST_APPLY_FAILED,
                Toast.LENGTH_SHORT).show();
    }

    private void writeRememberedConfirmedSnapshot(
            Subscription sub,
            CodecRequest request,
            CodecSnapshot confirmedSnapshot) {
        if (sub == null || sub.mac == null || request == null) return;
        if (confirmedSnapshot != null
                && sameDevice(sub.mac, confirmedSnapshot.mac)
                && confirmedSnapshot.activeCodecType == request.codecType) {
            prefs.writeSnapshot(
                    sub.mac,
                    confirmedSnapshot.activeCodecType,
                    confirmedSnapshot.activeCodecSpecific1,
                    confirmedSnapshot.activeSampleRate);
            return;
        }
        prefs.writeSnapshot(
                sub.mac, request.codecType, request.codecSpecific1, request.sampleRate);
    }

    /**
     * Vendor Bluetooth stacks can apply a requested LHDC sample rate after the regular
     * confirmation window closes. Preserve the user's per-device memory only once that delayed
     * state can be read back; a failed request is never persisted optimistically.
     */
    private void recheckRememberedWriteAfterTimeout(
            Subscription sub,
            CodecRequest request,
            long generation) {
        if (sub == null || sub.mac == null || request == null) return;
        mainHandler.postDelayed(() -> {
            if (!isSubscriptionActive(sub)
                    || !isCurrentCodecWrite(sub, generation)
                    || !prefs.isRemembered(sub.mac)) return;
            Thread worker = new Thread(() -> {
                CodecSnapshot snapshot = bridge.getStatus(sub.mac);
                if (!isSubscriptionActive(sub)
                        || !isCurrentCodecWrite(sub, generation)
                        || !prefs.isRemembered(sub.mac)) {
                    return;
                }
                if (!matchesRememberedRequest(snapshot, request)) {
                    MLog.event("remember.write.delayed_skip",
                            "request", request,
                            "live", String.valueOf(snapshot));
                    return;
                }
                writeRememberedConfirmedSnapshot(sub, request, snapshot);
                MLog.event("remember.write.delayed_confirmed",
                        "request", request,
                        "live", snapshot);
                mainHandler.post(() -> {
                    if (isSubscriptionActive(sub) && isCurrentCodecWrite(sub, generation)) {
                        publish(snapshot, sub);
                    }
                });
            }, "MelodyCodecLsp-rememberConfirm");
            worker.setDaemon(true);
            worker.start();
        }, REMEMBER_CONFIRM_RECHECK_DELAY_MS);
    }

    private static boolean matchesRememberedRequest(
            CodecSnapshot snapshot,
            CodecRequest request) {
        if (snapshot == null || request == null) return false;
        if (!sameDevice(snapshot.mac, request.mac)) return false;
        if (snapshot.activeCodecType != request.codecType) return false;
        if (request.sampleRate != 0 && snapshot.activeSampleRate != request.sampleRate) {
            return false;
        }
        if (request.codecType == CodecLabelTable.CODEC_AAC) return true;
        if (CodecLabelTable.isLhdc(request.codecType)) {
            long active = snapshot.activeCodecSpecific1 & 0xFFL;
            long requested = request.codecSpecific1 & 0xFFL;
            return active == requested || isLhdcFixedCeilingPair(active, requested);
        }
        return snapshot.activeCodecSpecific1 == request.codecSpecific1;
    }

    private static boolean isLhdcFixedCeilingPair(long first, long second) {
        return (first == CodecLabelTable.LHDC_QUALITY_FIXED_900
                && second == CodecLabelTable.LHDC_QUALITY_FIXED_1000)
                || (first == CodecLabelTable.LHDC_QUALITY_FIXED_1000
                && second == CodecLabelTable.LHDC_QUALITY_FIXED_900);
    }

    private boolean shouldShowNativePatchUnsupportedToast(CodecRequest request) {
        return nativePatchUnsupported
                && request != null
                && CodecLabelTable.isLhdc(request.codecType);
    }

    private void showWriteFailedToastForSnapshot(CodecSnapshot snapshot) {
        Toast.makeText(context,
                nativePatchUnsupported
                        && snapshot != null
                        && CodecLabelTable.isLhdc(snapshot.activeCodecType)
                        ? Strings.TOAST_NATIVE_PATCH_UNSUPPORTED
                        : Strings.TOAST_APPLY_FAILED,
                Toast.LENGTH_SHORT).show();
    }

    private interface WriteFailureHandler {
        boolean onFailure(WriteResult result, Throwable error);
    }

    private interface WriteSuccessHandler {
        boolean onConfirmed(WriteResult result);
    }

    private synchronized long nextCodecWriteGeneration(Subscription sub) {
        if (!isSubscriptionActive(sub) || sub.mac == null) return 0L;
        long next = codecWriteGenerations.containsKey(sub.mac)
                ? codecWriteGenerations.get(sub.mac) + 1L
                : 1L;
        codecWriteGenerations.put(sub.mac, next);
        return next;
    }

    private synchronized boolean isCurrentCodecWrite(Subscription sub, long generation) {
        if (!isSubscriptionActive(sub) || sub.mac == null || generation == 0L) return false;
        Long current = codecWriteGenerations.get(sub.mac);
        return current != null && current == generation;
    }

    private void applyOptionalCodecWrite(Subscription sub, boolean enable) {
        if (!isSubscriptionActive(sub)) return;
        if (!ensureA2dpReadyForUser(sub)) return;
        setCodecModeStatus(sub, Strings.STATE_SWITCHING_CODEC);
        setBlockDisabled(sub, true);
        long generation = nextCodecWriteGeneration(sub);
        replayer.onUserCodecWrite(
                sub != null ? sub.mac : null,
                null,
                enable ? "optional_enable" : "optional_disable");
        bridge.setOptionalCodecs(sub.mac, enable, () -> isCurrentCodecWrite(sub, generation))
                .whenComplete((result, ex) -> mainHandler.post(() -> {
            if (!isCurrentCodecWrite(sub, generation)) {
                MLog.event("write.optional.stale.ignore",
                        "generation", generation,
                        "mac", sub != null ? sub.mac : "?",
                        "enable", enable,
                        "outcome", result != null ? result.outcome : "exception");
                return;
            }
            setBlockDisabled(sub, false);
            if (ex != null) {
                MLog.e("setOptionalCodecs future failed", ex);
                showWriteFailedToastForSnapshot(snapshotFor(sub));
                refreshSnapshot(sub);
                return;
            }
            switch (result.outcome) {
                case CONFIRMED:
                    refreshSnapshot(sub);
                    refreshSnapshotDelayed(sub, 700L);
                    refreshSnapshotDelayed(sub, 1800L);
                    refreshSnapshotDelayed(sub, 3600L);
                    break;
                case TIMEOUT_ROLLED_BACK:
                    if (enable && applyHighQualityCodecFallback(sub, result.rollbackSnapshot)) {
                        return;
                    }
                    showWriteFailedToastForSnapshot(result.rollbackSnapshot);
                    if (result.rollbackSnapshot != null) {
                        publish(result.rollbackSnapshot, sub);
                    } else {
                        refreshSnapshot(sub);
                    }
                    break;
                case FAILED:
                default:
                    if (enable && applyHighQualityCodecFallback(sub, snapshotFor(sub))) {
                        return;
                    }
                    showWriteFailedToastForSnapshot(snapshotFor(sub));
                    refreshSnapshot(sub);
                    break;
            }
        }));
    }

    private boolean applyHighQualityCodecFallback(Subscription sub, CodecSnapshot live) {
        CodecRequest request = buildHighQualityCodecRequest(sub, live);
        if (request == null) return false;
        MLog.event("write.high_quality.fallback", "request", request);
        setCodecModeStatus(sub, Strings.STATE_SWITCHING_CODEC);
        applyWrite(sub, request);
        return true;
    }

    private void applyHighQualityCodecWrite(Subscription sub, CodecSnapshot snapshot) {
        CodecRequest request = buildHighQualityCodecRequest(sub, snapshot);
        if (request == null) {
            MLog.event("write.high_quality.fastpath.skip",
                    "reason", "no_selectable_request",
                    "live", String.valueOf(snapshot));
            applyOptionalCodecWrite(sub, true);
            return;
        }
        setCodecModeStatus(sub, Strings.STATE_SWITCHING_CODEC);
        MLog.event("write.high_quality.fastpath",
                "from", snapshot.activeCodecType,
                "request", request);
        long generation = nextCodecWriteGeneration(sub);
        boolean fromAac = snapshot.activeCodecType == CodecLabelTable.CODEC_AAC;
        applyHighQualityCodecWriteAttempt(sub, request, generation, 0, fromAac);
    }

    private void applyHighQualityCodecWriteAttempt(
            Subscription sub,
            CodecRequest request,
            long generation,
            int attempt,
            boolean fromAac) {
        applyWrite(sub, request, (result, error) -> {
            if (error != null) {
                MLog.event("write.high_quality.fastpath.error_fallback",
                        "mac", redactedMac(sub),
                        "attempt", attempt,
                        "cause", MLog.compactThrowable(error));
            } else {
                MLog.event("write.high_quality.fastpath.fallback",
                        "outcome", result != null ? result.outcome : "unknown",
                        "path", result != null ? result.path : "unknown",
                        "attempt", attempt);
            }
            CodecSnapshot live = result != null ? result.rollbackSnapshot : snapshotFor(sub);
            if (isHighQualityMode(live)) {
                MLog.event("write.high_quality.partial_confirmed",
                        "attempt", attempt,
                        "live", live);
                refreshSnapshot(sub);
                return true;
            }
            if (attempt == 0 && isCurrentCodecWrite(sub, generation)) {
                MLog.event("write.high_quality.retry",
                        "attempt", attempt + 1,
                        "delayMs", HIGH_QUALITY_RETRY_DELAY_MS,
                        "request", request);
                mainHandler.postDelayed(() -> {
                    if (!isCurrentCodecWrite(sub, generation)) {
                        MLog.event("write.high_quality.retry.stale",
                                "attempt", attempt + 1,
                                "request", request);
                        return;
                    }
                    applyHighQualityCodecWriteAttempt(
                            sub, request, generation, attempt + 1, fromAac);
                }, HIGH_QUALITY_RETRY_DELAY_MS);
                return true;
            }
            if (fromAac && isCurrentCodecWrite(sub, generation)) {
                applyAacHighQualityWarmupFallback(sub, request, live, generation);
                return true;
            }
            applyOptionalCodecWrite(sub, true);
            return true;
        }, generation);
    }

    private void applyAacHighQualityWarmupFallback(
            Subscription sub,
            CodecRequest highQualityRequest,
            CodecSnapshot live,
            long generation) {
        if (!isCurrentCodecWrite(sub, generation)) return;
        if (live == null || !sameDevice(live.mac, highQualityRequest.mac)) {
            MLog.event("write.high_quality.aac_warmup.skip",
                    "reason", "no_live_snapshot",
                    "request", highQualityRequest);
            applyOptionalCodecWrite(sub, true);
            return;
        }
        CodecRequest warmup = CodecRequest.fromActive(live)
                .codecType(CodecLabelTable.CODEC_SBC)
                .codecSpecific1(0L)
                .codecSpecific2(0L)
                .codecSpecific3(0L)
                .codecSpecific4(0L)
                .sampleRate(0)
                .bitsPerSample(0)
                .channelMode(0)
                .build();
        MLog.event("write.high_quality.aac_warmup",
                "warmup", warmup,
                "target", highQualityRequest);
        applyWrite(sub, warmup, (result, error) -> {
            if (error != null) {
                MLog.event("write.high_quality.aac_warmup.error_fallback",
                        "mac", redactedMac(sub),
                        "cause", MLog.compactThrowable(error));
            } else {
                MLog.event("write.high_quality.aac_warmup.fallback",
                        "outcome", result != null ? result.outcome : "unknown",
                        "path", result != null ? result.path : "unknown");
            }
            applyOptionalCodecWrite(sub, true);
            return true;
        }, generation, result -> {
            mainHandler.postDelayed(() -> {
                if (!isCurrentCodecWrite(sub, generation)) {
                    MLog.event("write.high_quality.aac_warmup.stale",
                            "target", highQualityRequest);
                    return;
                }
                MLog.event("write.high_quality.aac_warmup.target",
                        "delayMs", AAC_HIGH_QUALITY_WARMUP_DELAY_MS,
                        "request", highQualityRequest);
                applyHighQualityCodecWriteAttempt(
                        sub, highQualityRequest, generation, 1, false);
            }, AAC_HIGH_QUALITY_WARMUP_DELAY_MS);
            return true;
        }, false);
    }

    private CodecRequest buildHighQualityCodecRequest(Subscription sub, CodecSnapshot live) {
        if (!isSubscriptionActive(sub)
                || live == null
                || !sameDevice(sub.mac, live.mac)) return null;
        int codecType = -1;
        long specific1 = 0L;
        long specific2 = 0L;
        long specific3 = 0L;
        long specific4 = 0L;
        int sampleRate = 0;
        int bitsPerSample = 0;
        int channelMode = 0;

        CodecSnapshot remembered = lastHighQualitySnapshots.get(sub.mac);
        PreferenceStore.RememberedValue rememberedValue = prefs.readSnapshot(sub.mac);
        int rememberedCodec = resolveSelectableCodecType(live, remembered);
        boolean rememberedValueMatchesTarget = false;
        if (rememberedCodec >= 0) {
            codecType = rememberedCodec;
            specific1 = remembered.activeCodecSpecific1;
            specific2 = remembered.activeCodecSpecific2;
            specific3 = remembered.activeCodecSpecific3;
            specific4 = remembered.activeCodecSpecific4;
        } else {
            codecType = bestSelectableHighQualityCodec(live.selectableCodecTypes);
            if (codecType < 0) return null;
            rememberedValueMatchesTarget = rememberedValue != null
                    && sameCodecFamily(rememberedValue.codecType, codecType);
            specific1 = rememberedValueMatchesTarget
                    ? rememberedValue.codecSpecific1
                    : defaultHighQualitySpecific1(codecType);
        }
        int capIndex = selectableCodecIndex(live, codecType);
        long capSpecific1 = selectableLongValue(live.selectableCodecSpecific1Values, capIndex);
        if (specific1 == 0L && capSpecific1 != 0L) {
            specific1 = capSpecific1;
        }
        int rememberedRate = remembered != null
                ? remembered.activeSampleRate
                : rememberedValueMatchesTarget ? rememberedValue.sampleRate : 0;
        sampleRate = chooseSampleRate(
                selectableIntValue(live.selectableCodecSampleRates, capIndex),
                rememberedRate,
                codecType);
        bitsPerSample = chooseBitsPerSample(
                selectableIntValue(live.selectableCodecBitsPerSample, capIndex),
                remembered != null ? remembered.activeBitsPerSample : 0);
        channelMode = chooseChannelMode(
                selectableIntValue(live.selectableCodecChannelModes, capIndex),
                remembered != null ? remembered.activeChannelMode : 0);

        return CodecRequest.fromActive(live)
                .codecType(codecType)
                .codecSpecific1(specific1)
                .codecSpecific2(specific2)
                .codecSpecific3(specific3)
                .codecSpecific4(specific4)
                .sampleRate(sampleRate)
                .bitsPerSample(bitsPerSample)
                .channelMode(channelMode)
                .build();
    }

    private void applyCodecTypeWrite(Subscription sub, CodecSnapshot snapshot, int codecType) {
        setCodecModeStatus(sub, Strings.STATE_SWITCHING_CODEC);
        CodecRequest request = CodecRequest.fromActive(snapshot)
                .codecType(codecType)
                .codecSpecific1(0L)
                .codecSpecific2(0L)
                .codecSpecific3(0L)
                .codecSpecific4(0L)
                .sampleRate(0)
                .bitsPerSample(0)
                .channelMode(0)
                .build();
        MLog.event("write.codec.fastpath", "from", snapshot.activeCodecType, "request", request);
        applyWrite(sub, request);
    }

    private void applyStandardCodecWrite(Subscription sub, CodecSnapshot snapshot) {
        setCodecModeStatus(sub, Strings.STATE_SWITCHING_CODEC);
        CodecRequest request = CodecRequest.fromActive(snapshot)
                .codecType(CodecLabelTable.CODEC_SBC)
                .codecSpecific1(0L)
                .codecSpecific2(0L)
                .codecSpecific3(0L)
                .codecSpecific4(0L)
                .sampleRate(0)
                .bitsPerSample(0)
                .channelMode(0)
                .build();
        MLog.event("write.standard.fastpath",
                "from", snapshot.activeCodecType,
                "request", request);
        applyWrite(sub, request, (result, error) -> {
            if (error != null) {
                MLog.event("write.standard.fastpath.error_fallback",
                        "mac", redactedMac(sub),
                        "cause", MLog.compactThrowable(error));
            } else {
                MLog.event("write.standard.fastpath.fallback",
                        "outcome", result != null ? result.outcome : "unknown",
                        "path", result != null ? result.path : "unknown");
            }
            applyOptionalCodecWrite(sub, false);
            return true;
        });
    }

    private void refreshSnapshot(Subscription sub) {
        if (!isSubscriptionActive(sub)) return;
        long refreshGeneration = sub.beginRefresh();
        if (refreshGeneration < 0L) return;
        Thread worker = new Thread(() -> {
            if (!isSubscriptionActive(sub)) return;
            CodecSnapshot snapshot;
            try {
                snapshot = bridge.getStatus(sub.mac);
            } catch (Throwable t) {
                MLog.e("refreshSnapshot threw", t);
                snapshot = null;
            }
            CodecSnapshot finalSnapshot = snapshot;
            mainHandler.post(() -> {
                if (!isSubscriptionActive(sub)
                        || !sub.isCurrentRefresh(refreshGeneration)) {
                    MLog.event("snapshot.refresh.stale.ignore",
                            "generation", refreshGeneration,
                            "mac", redactedMac(sub));
                    return;
                }
                if (Boolean.FALSE.equals(sub.connected)) {
                    publish(null, sub);
                } else {
                    publish(finalSnapshot, sub);
                }
            });
        }, "MelodyCodecLsp-refresh");
        worker.setDaemon(true);
        worker.start();
    }

    private void refreshSnapshotDelayed(Subscription sub, long delayMs) {
        if (!isSubscriptionActive(sub)) return;
        mainHandler.postDelayed(() -> {
            if (isSubscriptionActive(sub)) refreshSnapshot(sub);
        }, delayMs);
    }

    private void publish(CodecSnapshot snapshot, Subscription sub) {
        if (!isSubscriptionActive(sub)) return;
        // A pushed/rollback snapshot or the winning refresh supersedes every older query.
        sub.invalidateRefreshes();
        if (snapshot != null) {
            if (!sameDevice(sub.mac, snapshot.mac)) {
                MLog.event("snapshot.publish.device_mismatch",
                        "subMac", redactedMac(sub),
                        "snapshotMac", A2dpRouteReadiness.redactMac(snapshot.mac));
                return;
            }
            cacheSnapshot(snapshot);
            maybeInitializeRememberedSnapshot(snapshot, "status_publish");
            rememberHighQualitySnapshot(snapshot);
        }
        if (snapshot == null) {
            clearSnapshot(sub.mac);
            renderUnknown(sub);
            return;
        }
        renderSnapshot(snapshot, sub, /* fromCache= */ false);
    }

    private void onPushedSnapshot(CodecSnapshot snapshot) {
        if (snapshot == null || snapshot.mac == null) return;
        mainHandler.post(() -> {
            cacheSnapshot(snapshot);
            maybeInitializeRememberedSnapshot(snapshot, "bridge_push");
            rememberHighQualitySnapshot(snapshot);
            for (Subscription sub : subscriptions.values()) {
                if (isSubscriptionActive(sub) && sameDevice(snapshot.mac, sub.mac)) {
                    sub.invalidateRefreshes();
                    sub.connected = Boolean.TRUE;
                    renderSnapshot(snapshot, sub, /* fromCache= */ false);
                }
            }
        });
    }

    private void renderUnknown(Subscription sub) {
        if (!isSubscriptionActive(sub)) return;
        if (isClassicRestorePending(sub.mac)) {
            restoreClassicAudioRows(sub);
            return;
        }
        if (leAudioManager.isEnabled(sub.mac) && !leAudioManager.isConnected(sub.mac)) {
            renderLeAudioConnecting(sub);
            return;
        }
        sub.renderedLeAudioActive = false;
        // When LE Audio is enabled the A2DP codec status is unavailable (the device is on the
        // LE transport), but that is NOT a disconnect — show LC3 and keep the switch usable.
        if (shouldRenderLeAudioActive(sub)) {
            renderLeAudioActive(sub);
            return;
        }
        boolean connected = Boolean.TRUE.equals(sub.connected);
        if (connected && !isA2dpReadyOrUnknown(sub)) {
            renderA2dpWaiting(sub);
            return;
        }
        String state = connected ? Strings.STATE_CODEC_UNKNOWN : Strings.STATE_NO_DEVICE;
        if (sub.prefs.codecDisplay != null) {
            PrefRef.setTitle(sub.prefs.codecDisplay,
                    Strings.CODEC_BLOCK_TITLE + " : " + state);
        }
        PrefRef.setSummary(sub.prefs.qualityOption, state);
        PrefRef.setSummary(sub.prefs.sampleRateOption, state);
        PrefRef.setVisible(sub.prefs.codecModeOption, false);
        PrefRef.setVisible(sub.prefs.qualityOption, true);
        PrefRef.setVisible(sub.prefs.sampleRateOption, true);
        setBlockDisabled(sub, true);
        if (sub.prefs.rememberToggle != null) {
            PrefRef.setChecked(sub.prefs.rememberToggle, prefs.isRemembered(sub.mac));
        }
        applyLeAudioToSwitch(sub);
    }

    private void renderA2dpWaiting(Subscription sub) {
        if (!isSubscriptionActive(sub) || sub.prefs == null) return;
        sub.connected = Boolean.TRUE;
        sub.renderedLeAudioActive = false;
        if (sub.prefs.codecDisplay != null) {
            PrefRef.setTitle(sub.prefs.codecDisplay,
                    Strings.CODEC_BLOCK_TITLE + " : " + Strings.STATE_A2DP_WAITING);
        }
        PrefRef.setVisible(sub.prefs.codecModeOption, false);
        PrefRef.setVisible(sub.prefs.qualityOption, true);
        PrefRef.setVisible(sub.prefs.sampleRateOption, true);
        PrefRef.setSummary(sub.prefs.qualityOption, Strings.STATE_A2DP_WAITING);
        PrefRef.setSummary(sub.prefs.sampleRateOption, Strings.STATE_A2DP_WAITING);
        setBlockDisabled(sub, false);
        if (sub.prefs.rememberToggle != null) {
            PrefRef.setChecked(sub.prefs.rememberToggle, prefs.isRemembered(sub.mac));
        }
        applyLeAudioToSwitch(sub);
    }

    private void renderSnapshot(CodecSnapshot snapshot, Subscription sub, boolean fromCache) {
        if (!isSubscriptionActive(sub)
                || snapshot == null
                || !sameDevice(sub.mac, snapshot.mac)) return;
        // LE Audio override (TODO B): when LE Audio is on, the device runs LC3 over the LE
        // transport, so the A2DP codec / quality / sample-rate controls do not apply. Show
        // "蓝牙音质 : LC3" and hide the quality + sample-rate rows on BOTH surfaces.
        if (shouldRenderLeAudioActive(sub)) {
            renderLeAudioActive(sub);
            return;
        }
        sub.renderedLeAudioActive = false;
        clearClassicRestorePending(sub.mac);
        if (Boolean.FALSE.equals(sub.connected)) {
            renderUnknown(sub);
            return;
        }
        if (!isA2dpReadyOrUnknown(sub)) {
            renderA2dpWaiting(sub);
            return;
        }
        String codecName = CodecLabelTable.codecLabel(
                context, snapshot.activeCodecType, snapshot.activeCodecSpecific1);
        String header;
        if (fromCache) {
            String stamp = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
            header = Strings.CODEC_BLOCK_TITLE + " : " + codecName + "  ("
                    + String.format(Strings.FRESHNESS_LABEL_FORMAT, stamp) + ")";
        } else {
            header = Strings.CODEC_BLOCK_TITLE + " : " + codecName;
        }
        if (sub.prefs.codecDisplay != null) {
            PrefRef.setTitle(sub.prefs.codecDisplay, header);
        }
        setBlockDisabled(sub, false);

        renderCodecMode(snapshot, sub);
        renderQuality(snapshot, sub);
        renderSampleRate(snapshot, sub);
        applyLeAudioToSwitch(sub);
        if (sub.prefs.rememberToggle != null) {
            PrefRef.setChecked(sub.prefs.rememberToggle, prefs.isRemembered(sub.mac));
        }
    }

    private boolean shouldRenderLeAudioActive(Subscription sub) {
        return leAudioManager.isEnabled(sub.mac)
                && leAudioManager.isConnected(sub.mac)
                && !isClassicRestorePending(sub.mac);
    }

    private void renderLeAudioConnecting(Subscription sub) {
        if (!isSubscriptionActive(sub)) return;
        sub.renderedLeAudioActive = false;
        if (sub.prefs.codecDisplay != null) {
            PrefRef.setTitle(sub.prefs.codecDisplay,
                    Strings.CODEC_BLOCK_TITLE + " : " + Strings.STATE_LE_AUDIO_CONNECTING);
        }
        PrefRef.setVisible(sub.prefs.codecModeOption, false);
        PrefRef.setVisible(sub.prefs.qualityOption, false);
        PrefRef.setVisible(sub.prefs.sampleRateOption, false);
        setBlockDisabled(sub, true);
        // A failed reconnect must not trap the user in LE Audio. Keep the toggle available so
        // classic audio can be restored without opening system Settings.
        PrefRef.setDisabled(sub.prefs.leAudioSwitch, false);
        applyLeAudioToSwitch(sub);
    }

    private void renderLeAudioActive(Subscription sub) {
        if (!isSubscriptionActive(sub)) return;
        sub.renderedLeAudioActive = true;
        if (sub.prefs.codecDisplay != null) {
            PrefRef.setTitle(sub.prefs.codecDisplay,
                    Strings.CODEC_BLOCK_TITLE + " : " + Strings.CODEC_LABEL_LC3);
        }
        PrefRef.setVisible(sub.prefs.codecModeOption, false);
        PrefRef.setVisible(sub.prefs.qualityOption, false);
        PrefRef.setVisible(sub.prefs.sampleRateOption, false);
        setBlockDisabled(sub, false);
        applyLeAudioToSwitch(sub);
        if (sub.prefs.rememberToggle != null) {
            PrefRef.setChecked(sub.prefs.rememberToggle, prefs.isRemembered(sub.mac));
        }
    }

    private static void setBlockDisabled(Subscription sub, boolean disabled) {
        if (!isSubscriptionActive(sub) || sub.prefs == null) return;
        PrefRef.setDisabled(sub.prefs.category, disabled);
        PrefRef.setDisabled(sub.prefs.codecDisplay, disabled);
        PrefRef.setDisabled(sub.prefs.codecModeOption, disabled);
        PrefRef.setDisabled(sub.prefs.qualityOption, disabled);
        PrefRef.setDisabled(sub.prefs.sampleRateOption, disabled);
        PrefRef.setDisabled(sub.prefs.rememberToggle, disabled);
        PrefRef.setDisabled(sub.prefs.leAudioSwitch, disabled);
    }

    private void renderCodecMode(CodecSnapshot snapshot, Subscription sub) {
        Object mode = sub.prefs.codecModeOption;
        if (mode == null) return;
        if (!isCodecModeSwitchAvailable(snapshot)) {
            PrefRef.setVisible(mode, false);
            return;
        }
        if (codecModeUsesHeader(sub)) {
            PrefRef.setSummary(sub.prefs.codecDisplay, "");
            PrefRef.setVisible(mode, false);
            return;
        }
        if (snapshot.activeCodecType == CodecLabelTable.CODEC_AAC) {
            PrefRef.setSummary(mode, Strings.CODEC_LABEL_AAC);
            PrefRef.setVisible(mode, true);
            return;
        }
        PrefRef.setSummary(mode, codecModeEntry(snapshot, isHighQualityMode(snapshot)));
        PrefRef.setVisible(mode, true);
    }

    private void renderQuality(CodecSnapshot snapshot, Subscription sub) {
        Object q = sub.prefs.qualityOption;
        if (q == null) return;
        if (!CodecLabelTable.isQualityCapable(snapshot.activeCodecType)) {
            PrefRef.setVisible(q, false);
            return;
        }
        long[] options = qualityOptionsForUi(snapshot);
        if (options.length == 0) {
            PrefRef.setVisible(q, false);
            return;
        }
        // Show the current quality as the row's summary, exact match or fallback.
        boolean known = false;
        for (long opt : options) {
            if (qualityValueMatches(snapshot.activeCodecType,
                    opt, snapshot.activeCodecSpecific1)) {
                known = true;
                break;
            }
        }
        if (known || CodecLabelTable.isKnownQuality(
                snapshot.activeCodecType, snapshot.activeCodecSpecific1)) {
            PrefRef.setSummary(q, CodecLabelTable.qualityLabel(
                    context, snapshot.activeCodecType, snapshot.activeCodecSpecific1));
        } else {
            PrefRef.setSummary(q,
                    String.format(Strings.QUALITY_UNKNOWN_VALUE_FORMAT, snapshot.activeCodecSpecific1));
        }
        PrefRef.setVisible(q, true);
    }

    private void renderSampleRate(CodecSnapshot snapshot, Subscription sub) {
        Object r = sub.prefs.sampleRateOption;
        if (r == null) return;
        int activeHz = sampleRateBitToHz(snapshot.activeSampleRate);
        if (activeHz <= 0) activeHz = SAMPLE_RATE_48000_HZ;
        PrefRef.setSummary(r, CodecLabelTable.sampleRateLabel(activeHz));
        PrefRef.setVisible(r, true);
    }

    private String codecModeEntry(CodecSnapshot snapshot, boolean highQuality) {
        String label = highQuality
                ? bestHighQualityCodecLabel(snapshot)
                : standardCodecLabel(snapshot);
        return (highQuality ? Strings.CODEC_MODE_HIGH_QUALITY : Strings.CODEC_MODE_STANDARD)
                + "（" + label + "）";
    }

    private boolean codecModeUsesHeader(Subscription sub) {
        return sub != null
                && sub.prefs != null
                && sub.prefs.codecDisplay != null
                && sub.prefs.codecDisplay != sub.prefs.codecModeOption;
    }

    private void setCodecModeStatus(Subscription sub, CharSequence summary) {
        if (!isSubscriptionActive(sub) || sub.prefs == null) return;
        Object mode = sub.prefs.codecModeOption;
        if (mode != null && PrefRef.isVisible(mode)) {
            PrefRef.setSummary(mode, summary);
        } else if (codecModeUsesHeader(sub)) {
            PrefRef.setSummary(sub.prefs.codecDisplay, summary);
        }
    }

    private static boolean isCodecModeSwitchAvailable(CodecSnapshot snapshot) {
        if (snapshot == null) return false;
        return isHighQualityChoiceAvailable(snapshot)
                || isAacChoiceAvailable(snapshot)
                || isStandardChoiceAvailable(snapshot);
    }

    private static boolean isHighQualityChoiceAvailable(CodecSnapshot snapshot) {
        if (snapshot == null) return false;
        if (snapshot.supportsOptionalCodecs()) return true;
        if (bestSelectableHighQualityCodec(snapshot.selectableCodecTypes) >= 0) return true;
        return snapshot.activeCodecType != CodecLabelTable.CODEC_SBC
                && snapshot.activeCodecType != CodecLabelTable.CODEC_AAC;
    }

    private static boolean isAacChoiceAvailable(CodecSnapshot snapshot) {
        if (snapshot == null) return false;
        return snapshot.activeCodecType != CodecLabelTable.CODEC_LC3;
    }

    private static boolean isStandardChoiceAvailable(CodecSnapshot snapshot) {
        if (snapshot == null) return false;
        if (snapshot.activeCodecType != CodecLabelTable.CODEC_SBC) return true;
        if (isAacChoiceAvailable(snapshot)) return true;
        return snapshot.supportsOptionalCodecs()
                || bestSelectableHighQualityCodec(snapshot.selectableCodecTypes) >= 0;
    }

    private String bestHighQualityCodecLabel(CodecSnapshot snapshot) {
        if (snapshot == null) return Strings.STATE_CODEC_UNKNOWN;
        if (snapshot.optionalCodecsEnabled()
                && snapshot.activeCodecType != CodecLabelTable.CODEC_SBC
                && snapshot.activeCodecType != CodecLabelTable.CODEC_AAC) {
            return CodecLabelTable.codecLabel(
                    context, snapshot.activeCodecType, snapshot.activeCodecSpecific1);
        }
        int best = bestSelectableHighQualityCodec(snapshot.selectableCodecTypes);
        if (best >= 0) {
            return CodecLabelTable.codecLabel(context, best);
        }
        return Strings.STATE_CODEC_UNKNOWN;
    }

    private String standardCodecLabel(CodecSnapshot snapshot) {
        return Strings.CODEC_LABEL_SBC;
    }

    private static boolean isHighQualityMode(CodecSnapshot snapshot) {
        if (snapshot == null) return false;
        return snapshot.activeCodecType != CodecLabelTable.CODEC_SBC
                && snapshot.activeCodecType != CodecLabelTable.CODEC_AAC;
    }

    private static boolean isStandardMode(CodecSnapshot snapshot) {
        return snapshot != null && snapshot.activeCodecType == CodecLabelTable.CODEC_SBC;
    }

    private static int bestSelectableHighQualityCodec(int[] codecTypes) {
        if (codecTypes == null || codecTypes.length == 0) return -1;
        int[] priority = {
                CodecLabelTable.CODEC_LHDC,
                CodecLabelTable.CODEC_LHDC_V3_LEGACY,
                19,
                18,
                CodecLabelTable.CODEC_LDAC,
                CodecLabelTable.CODEC_APTX_ADAPTIVE,
                CodecLabelTable.CODEC_APTX_HD,
                CodecLabelTable.CODEC_APTX
        };
        for (int preferred : priority) {
            for (int type : codecTypes) {
                if (type == preferred || (CodecLabelTable.isLhdc(preferred)
                        && CodecLabelTable.isLhdc(type))) {
                    return type;
                }
            }
        }
        for (int type : codecTypes) {
            if (type != CodecLabelTable.CODEC_SBC
                    && type != CodecLabelTable.CODEC_AAC) return type;
        }
        return -1;
    }

    private static int selectableCodecIndex(CodecSnapshot snapshot, int codecType) {
        if (snapshot == null || snapshot.selectableCodecTypes == null) return -1;
        for (int i = 0; i < snapshot.selectableCodecTypes.length; i++) {
            if (snapshot.selectableCodecTypes[i] == codecType) return i;
        }
        if (CodecLabelTable.isLhdc(codecType)) {
            for (int i = 0; i < snapshot.selectableCodecTypes.length; i++) {
                if (CodecLabelTable.isLhdc(snapshot.selectableCodecTypes[i])) return i;
            }
        }
        return -1;
    }

    private static int resolveSelectableCodecType(CodecSnapshot live, CodecSnapshot remembered) {
        if (live == null || remembered == null) return -1;
        if (remembered.activeCodecType == CodecLabelTable.CODEC_SBC
                || remembered.activeCodecType == CodecLabelTable.CODEC_AAC) {
            return -1;
        }
        int[] types = live.selectableCodecTypes;
        if (types == null) return -1;
        for (int type : types) {
            if (type == remembered.activeCodecType) return type;
        }
        if (CodecLabelTable.isLhdc(remembered.activeCodecType)) {
            for (int type : types) {
                if (CodecLabelTable.isLhdc(type)) return type;
            }
        }
        return -1;
    }

    private static boolean sameCodecFamily(int firstCodecType, int secondCodecType) {
        if (firstCodecType == secondCodecType) return true;
        return CodecLabelTable.isLhdc(firstCodecType)
                && CodecLabelTable.isLhdc(secondCodecType);
    }

    private static int selectableIntValue(int[] values, int index) {
        if (values == null || index < 0 || index >= values.length) return 0;
        return values[index];
    }

    private static long selectableLongValue(long[] values, int index) {
        if (values == null || index < 0 || index >= values.length) return 0L;
        return values[index];
    }

    private static int chooseSampleRate(int mask, int remembered, int codecType) {
        if (remembered != 0 && (mask == 0 || (mask & remembered) != 0)) {
            return remembered;
        }
        if (CodecLabelTable.isLhdc(codecType)) {
            int picked = firstSupported(mask,
                    SAMPLE_RATE_192000_BIT, SAMPLE_RATE_96000_BIT, SAMPLE_RATE_48000_BIT, 0x1);
            if (picked != 0) return picked;
        } else if (codecType == CodecLabelTable.CODEC_LDAC) {
            int picked = firstSupported(mask, SAMPLE_RATE_96000_BIT, SAMPLE_RATE_48000_BIT, 0x1);
            if (picked != 0) return picked;
        }
        return firstBit(mask);
    }

    private static int chooseBitsPerSample(int mask, int remembered) {
        if (remembered != 0 && (mask == 0 || (mask & remembered) != 0)) {
            return remembered;
        }
        int picked = firstSupported(mask, 0x2, 0x4, 0x1, 0x8);
        return picked != 0 ? picked : firstBit(mask);
    }

    private static int chooseChannelMode(int mask, int remembered) {
        if (remembered != 0 && (mask == 0 || (mask & remembered) != 0)) {
            return remembered;
        }
        int picked = firstSupported(mask, 0x2, 0x4, 0x1);
        return picked != 0 ? picked : firstBit(mask);
    }

    private static int firstSupported(int mask, int... values) {
        if (mask == 0) return 0;
        for (int value : values) {
            if ((mask & value) != 0) return value;
        }
        return 0;
    }

    private static int firstBit(int mask) {
        if (mask == 0) return 0;
        return mask & -mask;
    }

    private static long defaultHighQualitySpecific1(int codecType) {
        if (CodecLabelTable.isLhdc(codecType)) {
            return 0x8000L | CodecLabelTable.LHDC_QUALITY_ABR;
        }
        if (codecType == CodecLabelTable.CODEC_LDAC) {
            return CodecLabelTable.LDAC_QUALITY_MID;
        }
        return 0L;
    }

    private static int linkedSampleRateForQuality(CodecSnapshot snapshot, long specific1) {
        if (snapshot == null || !CodecLabelTable.isLhdc(snapshot.activeCodecType)) {
            return snapshot != null ? snapshot.activeSampleRate : SAMPLE_RATE_48000_BIT;
        }
        long quality = specific1 & 0xFFL;
        if (quality == CodecLabelTable.LHDC_QUALITY_CONNECTION) {
            return SAMPLE_RATE_48000_BIT;
        }
        if (isLhdcHighQuality(quality)) {
            if (isHighQualityRate(snapshot.activeSampleRate)) {
                return snapshot.activeSampleRate;
            }
            return preferredHighQualityRate(snapshot);
        }
        return snapshot.activeSampleRate != 0
                ? snapshot.activeSampleRate
                : SAMPLE_RATE_48000_BIT;
    }

    private static long linkedQualityForSampleRate(CodecSnapshot snapshot, int sampleRateBit) {
        if (snapshot == null || !CodecLabelTable.isLhdc(snapshot.activeCodecType)) {
            return snapshot != null ? snapshot.activeCodecSpecific1 : 0L;
        }
        long quality = snapshot.activeCodecSpecific1 & 0xFFL;
        if (sampleRateBit == SAMPLE_RATE_48000_BIT && isLhdcHighQuality(quality)) {
            return replaceLhdcQuality(snapshot.activeCodecSpecific1,
                    CodecLabelTable.LHDC_QUALITY_ABR);
        }
        if (isHighQualityRate(sampleRateBit)
                && quality == CodecLabelTable.LHDC_QUALITY_CONNECTION) {
            return replaceLhdcQuality(snapshot.activeCodecSpecific1,
                    CodecLabelTable.LHDC_QUALITY_ABR);
        }
        return snapshot.activeCodecSpecific1;
    }

    private static int preferredHighQualityRate(CodecSnapshot snapshot) {
        int mask = snapshot.selectableSampleRateMask;
        if (mask == 0 || CodecSnapshot.decodeSampleRateBits(mask).length == 0) {
            mask = sampleRateFallbackMask(snapshot.activeCodecType, snapshot.activeSampleRate);
        }
        if ((mask & SAMPLE_RATE_96000_BIT) != 0) return SAMPLE_RATE_96000_BIT;
        if ((mask & SAMPLE_RATE_192000_BIT) != 0) return SAMPLE_RATE_192000_BIT;
        return SAMPLE_RATE_96000_BIT;
    }

    private static long replaceLhdcQuality(long specific1, long quality) {
        return (specific1 & ~0xFFL) | (quality & 0xFFL);
    }

    private static boolean isLhdcHighQuality(long lowByte) {
        return lowByte == CodecLabelTable.LHDC_QUALITY_FIXED_900
                || lowByte == CodecLabelTable.LHDC_QUALITY_FIXED_1000;
    }

    private static boolean isHighQualityRate(int sampleRateBit) {
        return sampleRateBit == SAMPLE_RATE_96000_BIT
                || sampleRateBit == SAMPLE_RATE_192000_BIT;
    }

    private void rememberHighQualitySnapshot(CodecSnapshot snapshot) {
        if (snapshot == null || snapshot.mac == null) return;
        if (snapshot.activeCodecType == CodecLabelTable.CODEC_SBC
                || snapshot.activeCodecType == CodecLabelTable.CODEC_AAC) {
            return;
        }
        lastHighQualitySnapshots.put(snapshot.mac, snapshot);
    }

    private static int sampleRateFallbackMask(int codecType, int activeRateBit) {
        int mask = activeRateBit != 0 ? activeRateBit : SAMPLE_RATE_48000_BIT;
        final int B44_1 = 1, B48 = 2, B88_2 = 4, B96 = 8, B176_4 = 16, B192 = 32;
        if (codecType == CodecLabelTable.CODEC_LDAC) {
            mask |= B44_1 | B48 | B88_2 | B96;
        } else if (CodecLabelTable.isLhdc(codecType)) {
            mask |= B44_1 | B48 | B88_2 | B96 | B192;
        } else {
            mask |= B44_1 | B48;
        }
        return mask;
    }

    private static boolean qualityValueMatches(int codecType, long option, long active) {
        if (CodecLabelTable.isLhdc(codecType)) {
            long optionByte = option & 0xFFL;
            long activeByte = active & 0xFFL;
            if (isLhdcHighQuality(optionByte) && isLhdcHighQuality(activeByte)) {
                return true;
            }
            return optionByte == activeByte;
        }
        return option == active;
    }

    private static int sampleRateBitToHz(int bit) {
        int[] decoded = CodecSnapshot.decodeSampleRateBits(bit);
        if (decoded.length != 1 || decoded[0] <= 0) return -1;
        return decoded[0];
    }

    private static int sampleRateHzToBit(int hz) {
        int[] knownBits = {0x1, 0x2, 0x4, 0x8, 0x10, 0x20};
        int[] knownHz = {44100, 48000, 88200, 96000, 176400, 192000};
        for (int i = 0; i < knownHz.length; i++) {
            if (knownHz[i] == hz) return knownBits[i];
        }
        return -1;
    }

    private static int dp(Context context, int value) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private static String redactedMac(Subscription sub) {
        return A2dpRouteReadiness.redactMac(sub != null ? sub.mac : null);
    }

    private final class Subscription {
        final String mac;
        final CodecPreferences prefs;
        final Object fragment;
        BroadcastReceiver receiver;
        Boolean connected;
        boolean renderedLeAudioActive;
        java.lang.ref.WeakReference<Activity> hostActivity;
        volatile boolean active = true;
        private long refreshGeneration;

        Subscription(String mac, CodecPreferences prefs, Object fragment) {
            this.mac = mac;
            this.prefs = prefs;
            this.fragment = fragment;
        }

        void registerReceiver() {
            if (!active || receiver != null) return;
            receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    if (!active || intent == null) return;
                    String action = intent.getAction();
                    if (ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                        if (!matchesSubscriptionDevice(intent)) return;
                        int state = intent.getIntExtra(EXTRA_CONNECTION_STATE, -1);
                        if (state != -1 && state != BluetoothProfile.STATE_CONNECTED) {
                            mainHandler.post(() -> {
                                if (!active) return;
                                invalidateRefreshes();
                                if (leAudioManager.isEnabled(mac)
                                        && !isClassicRestorePending(mac)) {
                                    if (leAudioManager.isConnected(mac)) {
                                        connected = Boolean.TRUE;
                                        renderLeAudioActive(Subscription.this);
                                    } else {
                                        connected = Boolean.FALSE;
                                        renderLeAudioConnecting(Subscription.this);
                                    }
                                    return;
                                }
                                connected = Boolean.FALSE;
                                clearSnapshot(mac);
                                renderUnknown(Subscription.this);
                            });
                            return;
                        }
                        if (state == BluetoothProfile.STATE_CONNECTED) {
                            connected = Boolean.TRUE;
                        }
                        refreshSnapshot(Subscription.this);
                        if (state == BluetoothProfile.STATE_CONNECTED) {
                            refreshSnapshotDelayed(Subscription.this, 1200L);
                            refreshSnapshotDelayed(Subscription.this, 3200L);
                        }
                    } else if (ACTION_CODEC_CONFIG_CHANGED.equals(action)) {
                        if (leAudioManager.isEnabled(mac)
                                && !isClassicRestorePending(mac)) {
                            if (leAudioManager.isConnected(mac)) {
                                renderLeAudioActive(Subscription.this);
                            } else {
                                renderLeAudioConnecting(Subscription.this);
                            }
                            return;
                        }
                        refreshSnapshot(Subscription.this);
                    } else if (A2dpRouteReadiness.ACTION_ACTIVE_DEVICE_CHANGED.equals(action)) {
                        boolean ready = routeReadiness.updateFromActiveDeviceIntent(intent, mac);
                        if (ready) {
                            refreshSnapshot(Subscription.this);
                            refreshSnapshotDelayed(Subscription.this, 700L);
                            refreshSnapshotDelayed(Subscription.this, 1600L);
                        } else {
                            mainHandler.post(() -> {
                                if (active && Boolean.TRUE.equals(connected)) {
                                    renderA2dpWaiting(Subscription.this);
                                }
                            });
                        }
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_CODEC_CONFIG_CHANGED);
            filter.addAction(ACTION_CONNECTION_STATE_CHANGED);
            filter.addAction(A2dpRouteReadiness.ACTION_ACTIVE_DEVICE_CHANGED);
            try {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } catch (Throwable t) {
                context.registerReceiver(receiver, filter);
            }
        }

        @SuppressWarnings("deprecation")
        private boolean matchesSubscriptionDevice(Intent intent) {
            try {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device == null) return true;
                String address = device.getAddress();
                return address == null || address.equalsIgnoreCase(mac);
            } catch (Throwable t) {
                return true;
            }
        }

        void unregisterReceiver() {
            if (receiver == null) return;
            try {
                context.unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
            }
            receiver = null;
        }

        synchronized long beginRefresh() {
            if (!active) return -1L;
            return ++refreshGeneration;
        }

        synchronized boolean isCurrentRefresh(long generation) {
            return active && generation == refreshGeneration;
        }

        synchronized void invalidateRefreshes() {
            refreshGeneration++;
        }

        void dispose() {
            active = false;
            invalidateRefreshes();
            unregisterReceiver();
        }
    }
}
