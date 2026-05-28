package xyz.melodylsp.codec.host;

import android.app.AlertDialog;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import xyz.melodylsp.codec.bridge.CodecRequest;
import xyz.melodylsp.codec.bridge.CodecSnapshot;
import xyz.melodylsp.codec.bt.BluetoothCodecReflect;
import xyz.melodylsp.codec.label.CodecLabelTable;
import xyz.melodylsp.codec.storage.PreferenceStore;
import xyz.melodylsp.codec.util.MLog;

/**
 * Single host-side state owner. Each Surface attaches its {@link CodecPreferences} bag, the
 * controller refreshes UI state from {@code BluetoothA2dp.getCodecStatus} and routes write
 * intents through {@link CodecBridgeClient}.
 *
 * <p>Quality and sample-rate rows are plain {@code Preference} entries whose click handler
 * pops a hand-rolled {@link AlertDialog}. We deliberately avoid {@code ListPreference}: R8
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

    private final Context context;
    private final BluetoothCodecReflect reflect;
    private final CodecBridgeClient bridge;
    private final PreferenceStore prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ConnectionStateReplayer replayer;
    private final Map<Object, Subscription> subscriptions = new HashMap<>();
    private final AtomicReference<CodecSnapshot> lastSnapshot = new AtomicReference<>();

    public CodecController(
            Context context,
            BluetoothCodecReflect reflect,
            CodecBridgeClient bridge,
            PreferenceStore prefs) {
        this.context = context.getApplicationContext();
        this.reflect = reflect;
        this.bridge = bridge;
        this.prefs = prefs;
        this.replayer = new ConnectionStateReplayer(this.context, bridge, prefs);
        this.replayer.start();
        this.bridge.addSnapshotListener(this::onPushedSnapshot);
    }

    /** Bind a {@link CodecPreferences} bag to a fragment lifecycle. */
    public void attach(String mac, CodecPreferences pref, Object fragment) {
        Subscription sub = new Subscription(mac, pref);
        subscriptions.put(fragment, sub);

        wireClickListeners(sub);
        wireRememberToggle(sub);
        sub.registerReceiver();
        mainHandler.post(() -> refreshSnapshot(sub));
    }

    /**
     * Wire up click listeners on the quality and sample-rate Preferences. Tap → pop a
     * hand-rolled AlertDialog with the current options. We resolve
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
        Object qualityListener = Proxy.newProxyInstance(cl, new Class[]{clickListenerCls},
                (proxy, method, args) -> {
                    try {
                        showQualityPicker(sub);
                    } catch (Throwable t) {
                        MLog.e("showQualityPicker failed", t);
                        Toast.makeText(context, Strings.TOAST_APPLY_FAILED, Toast.LENGTH_SHORT).show();
                    }
                    return true;
                });
        Object sampleListener = Proxy.newProxyInstance(cl, new Class[]{clickListenerCls},
                (proxy, method, args) -> {
                    try {
                        showSampleRatePicker(sub);
                    } catch (Throwable t) {
                        MLog.e("showSampleRatePicker failed", t);
                        Toast.makeText(context, Strings.TOAST_APPLY_FAILED, Toast.LENGTH_SHORT).show();
                    }
                    return true;
                });
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
     * Pop an AlertDialog letting the user pick a quality step. Picks a sensible options list
     * even when AOSP {@code getCodecsSelectableCapabilities} returned nothing for this codec
     * (vendor codec quirk on every OPPO LHDC variant).
     */
    private void showQualityPicker(Subscription sub) {
        CodecSnapshot snapshot = lastSnapshot.get();
        if (snapshot == null) {
            Toast.makeText(context, Strings.STATE_CODEC_UNKNOWN, Toast.LENGTH_SHORT).show();
            return;
        }
        long[] options = snapshot.selectableCodecSpecific1;
        if (options == null || options.length == 0) {
            options = CodecLabelTable.qualityFallback(snapshot.activeCodecType);
        }
        if (options == null || options.length == 0) {
            Toast.makeText(context,
                    "当前编解码器不支持播放质量调整", Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence[] entries = new CharSequence[options.length];
        int checked = -1;
        for (int i = 0; i < options.length; i++) {
            entries[i] = CodecLabelTable.qualityLabel(context, snapshot.activeCodecType, options[i]);
            if (options[i] == snapshot.activeCodecSpecific1) checked = i;
        }
        long[] finalOptions = options;
        Context dialogContext = resolveLiveDialogContext(sub);
        if (dialogContext == null) {
            MLog.w("showQualityPicker skipped: no live activity context");
            return;
        }
        try {
            new AlertDialog.Builder(dialogContext)
                    .setTitle(Strings.QUALITY_OPTION_TITLE)
                    .setSingleChoiceItems(entries, checked, (DialogInterface dlg, int which) -> {
                        dlg.dismiss();
                        long picked = finalOptions[which];
                        CodecRequest req = CodecRequest.fromActive(snapshot)
                                .withSpecific1(picked).build();
                        applyWrite(sub, req);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } catch (Throwable t) {
            MLog.e("showQualityPicker dialog.show failed", t);
            Toast.makeText(context, Strings.TOAST_APPLY_FAILED, Toast.LENGTH_SHORT).show();
        }
    }

    private void showSampleRatePicker(Subscription sub) {
        CodecSnapshot snapshot = lastSnapshot.get();
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
            new AlertDialog.Builder(dialogContext)
                    .setTitle(Strings.SAMPLE_RATE_OPTION_TITLE)
                    .setSingleChoiceItems(entries, checked, (DialogInterface dlg, int which) -> {
                        dlg.dismiss();
                        int hz = finalRates[which];
                        int bit = sampleRateHzToBit(hz);
                        if (bit < 0) return;
                        CodecRequest req = CodecRequest.fromActive(snapshot)
                                .withSampleRate(bit).build();
                        applyWrite(sub, req);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } catch (Throwable t) {
            MLog.e("showSampleRatePicker dialog.show failed", t);
            Toast.makeText(context, Strings.TOAST_APPLY_FAILED, Toast.LENGTH_SHORT).show();
        }
    }

    private static Context resolveLiveDialogContext(Subscription sub) {
        Context ui = sub != null && sub.prefs != null ? sub.prefs.uiContext : null;
        Activity activity = findActivity(ui);
        if (activity == null) return null;
        if (activity.isFinishing()) return null;
        if (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed()) return null;
        return ui;
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
        if (!(value instanceof Boolean)) return false;
        boolean enabled = (Boolean) value;
        prefs.setRemembered(sub.mac, enabled);
        if (enabled) {
            CodecSnapshot s = lastSnapshot.get();
            if (s != null && s.mac != null && s.mac.equals(sub.mac)) {
                prefs.writeSnapshot(sub.mac, s.activeCodecSpecific1, s.activeSampleRate);
            }
        }
        return true;
    }

    private void applyWrite(Subscription sub, CodecRequest request) {
        bridge.setCodec(request).whenComplete((result, ex) -> mainHandler.post(() -> {
            if (ex != null) {
                MLog.e("setCodec future failed", ex);
                Toast.makeText(context, Strings.TOAST_APPLY_FAILED, Toast.LENGTH_SHORT).show();
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
                    if (prefs.isRemembered(sub.mac)) {
                        prefs.writeSnapshot(sub.mac, request.codecSpecific1, request.sampleRate);
                    }
                    refreshSnapshot(sub);
                    break;
                case TIMEOUT_ROLLED_BACK:
                    Toast.makeText(context, Strings.TOAST_APPLY_FAILED, Toast.LENGTH_SHORT).show();
                    if (result.rollbackSnapshot != null) {
                        publish(result.rollbackSnapshot, sub);
                    } else {
                        refreshSnapshot(sub);
                    }
                    break;
                case FAILED:
                default:
                    Toast.makeText(context, Strings.TOAST_APPLY_FAILED, Toast.LENGTH_SHORT).show();
                    refreshSnapshot(sub);
                    break;
            }
        }));
    }

    private void refreshSnapshot(Subscription sub) {
        Thread worker = new Thread(() -> {
            CodecSnapshot snapshot;
            try {
                snapshot = reflect.readStatus(sub.mac);
            } catch (BluetoothCodecReflect.BluetoothCodecReflectException e) {
                MLog.w("refreshSnapshot reflect failed", e);
                snapshot = bridge.getStatus(sub.mac);
            } catch (Throwable t) {
                MLog.e("refreshSnapshot threw", t);
                snapshot = null;
            }
            CodecSnapshot finalSnapshot = snapshot;
            mainHandler.post(() -> publish(finalSnapshot, sub));
        }, "MelodyCodecLsp-refresh");
        worker.setDaemon(true);
        worker.start();
    }

    private void publish(CodecSnapshot snapshot, Subscription sub) {
        if (snapshot != null) {
            lastSnapshot.set(snapshot);
        }
        if (snapshot == null) {
            CodecSnapshot stale = lastSnapshot.get();
            if (stale != null) {
                renderSnapshot(stale, sub, /* fromCache= */ true);
            } else {
                renderUnknown(sub);
            }
            return;
        }
        renderSnapshot(snapshot, sub, /* fromCache= */ false);
    }

    private void onPushedSnapshot(CodecSnapshot snapshot) {
        if (snapshot == null || snapshot.mac == null) return;
        mainHandler.post(() -> {
            lastSnapshot.set(snapshot);
            for (Subscription sub : subscriptions.values()) {
                if (snapshot.mac.equals(sub.mac)) {
                    renderSnapshot(snapshot, sub, /* fromCache= */ false);
                }
            }
        });
    }

    private void renderUnknown(Subscription sub) {
        if (sub.prefs.codecDisplay != null) {
            PrefRef.setTitle(sub.prefs.codecDisplay, Strings.CODEC_BLOCK_TITLE);
        }
        PrefRef.setVisible(sub.prefs.qualityOption, false);
        PrefRef.setVisible(sub.prefs.sampleRateOption, false);
        if (sub.prefs.rememberToggle != null) {
            PrefRef.setChecked(sub.prefs.rememberToggle, prefs.isRemembered(sub.mac));
        }
    }

    private void renderSnapshot(CodecSnapshot snapshot, Subscription sub, boolean fromCache) {
        String codecName = CodecLabelTable.codecLabel(context, snapshot.activeCodecType);
        String header;
        if (fromCache) {
            String stamp = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
            header = Strings.CODEC_BLOCK_TITLE + " · " + codecName + "  ("
                    + String.format(Strings.FRESHNESS_LABEL_FORMAT, stamp) + ")";
        } else {
            header = Strings.CODEC_BLOCK_TITLE + " · " + codecName;
        }
        if (sub.prefs.codecDisplay != null) {
            PrefRef.setTitle(sub.prefs.codecDisplay, header);
        }

        renderQuality(snapshot, sub);
        renderSampleRate(snapshot, sub);
        if (sub.prefs.rememberToggle != null) {
            PrefRef.setChecked(sub.prefs.rememberToggle, prefs.isRemembered(sub.mac));
        }
    }

    private void renderQuality(CodecSnapshot snapshot, Subscription sub) {
        Object q = sub.prefs.qualityOption;
        if (q == null) return;
        if (!CodecLabelTable.isQualityCapable(snapshot.activeCodecType)) {
            PrefRef.setVisible(q, false);
            return;
        }
        long[] options = snapshot.selectableCodecSpecific1;
        if (options == null || options.length == 0) {
            options = CodecLabelTable.qualityFallback(snapshot.activeCodecType);
        }
        if (options.length == 0) {
            PrefRef.setVisible(q, false);
            return;
        }
        // Show the current quality as the row's summary, exact match or fallback.
        boolean known = false;
        for (long opt : options) {
            if (opt == snapshot.activeCodecSpecific1) { known = true; break; }
        }
        if (known) {
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
        if (activeHz > 0) {
            PrefRef.setSummary(r, CodecLabelTable.sampleRateLabel(activeHz));
        } else {
            PrefRef.setSummary(r,
                    "采样率 (0x" + Integer.toHexString(snapshot.activeSampleRate) + ")");
        }
        PrefRef.setVisible(r, true);
    }

    private static int sampleRateFallbackMask(int codecType, int activeRateBit) {
        int mask = activeRateBit;
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

    private final class Subscription {
        final String mac;
        final CodecPreferences prefs;
        BroadcastReceiver receiver;

        Subscription(String mac, CodecPreferences prefs) {
            this.mac = mac;
            this.prefs = prefs;
        }

        void registerReceiver() {
            if (receiver != null) return;
            receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    String action = intent.getAction();
                    if (ACTION_CODEC_CONFIG_CHANGED.equals(action)
                            || ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                        refreshSnapshot(Subscription.this);
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_CODEC_CONFIG_CHANGED);
            filter.addAction(ACTION_CONNECTION_STATE_CHANGED);
            try {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } catch (Throwable t) {
                context.registerReceiver(receiver, filter);
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
    }
}
