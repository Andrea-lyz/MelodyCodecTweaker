package xyz.melodylsp.codec.host;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
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
 * pops a hand-rolled small floating {@link PopupWindow}. We deliberately avoid {@code ListPreference}: R8
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

    private final Context context;
    private final BluetoothCodecReflect reflect;
    private final CodecBridgeClient bridge;
    private final PreferenceStore prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ConnectionStateReplayer replayer;
    private final Map<Object, Subscription> subscriptions = new HashMap<>();
    private final AtomicReference<CodecSnapshot> lastSnapshot = new AtomicReference<>();
    private final xyz.melodylsp.codec.leaudio.LeAudioManager leAudioManager;

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
        this.leAudioManager = new xyz.melodylsp.codec.leaudio.LeAudioManager(
                this.context, this::onLeAudioStateChanged);
        registerActivityCleanup();
    }

    /**
     * Re-render every subscription bound to {@code mac} when its LE Audio state changes. This
     * is what lets OneSpace — which has no LE Audio switch of its own — still react (codec name
     * → LC3, hide quality / sample-rate rows) when LE Audio is toggled from DetailMain or the
     * system.
     */
    private void onLeAudioStateChanged(String mac) {
        boolean leOn = leAudioManager.isEnabled(mac);
        for (Subscription sub : subscriptions.values()) {
            if (sub.mac == null || !sub.mac.equals(mac)) continue;
            applyLeAudioToSwitch(sub);
            if (leOn) {
                CodecSnapshot snap = lastSnapshot.get();
                if (snap != null && mac.equals(snap.mac) && !Boolean.FALSE.equals(sub.connected)) {
                    renderSnapshot(snap, sub, /* fromCache= */ false);
                } else {
                    // LE Audio took over the transport; renderUnknown shows the LC3 state.
                    renderUnknown(sub);
                }
            } else {
                // LE Audio turned off: A2DP is coming back, so re-read the real codec status
                // instead of trusting the now-stale snapshot.
                refreshSnapshot(sub);
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
                sub.unregisterReceiver();
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
            // Guard against the host re-using a config-change-driven destroy: only clean up when
            // the Activity is really finishing.
            if (activity != null && !activity.isChangingConfigurations()) {
                CodecController.this.onActivityDestroyed(activity);
            }
        }
    }

    /** Bind a {@link CodecPreferences} bag to a fragment lifecycle. */
    public void attach(String mac, CodecPreferences pref, Object fragment) {
        // If this fragment instance was already attached (host rebuilt its screen), retire the
        // previous subscription first so its BroadcastReceiver is not orphaned (TODO A5).
        Subscription previous = subscriptions.remove(fragment);
        if (previous != null) {
            previous.unregisterReceiver();
        }
        Subscription sub = new Subscription(mac, pref, fragment);
        sub.hostActivity = new java.lang.ref.WeakReference<>(activityFromFragment(fragment));
        subscriptions.put(fragment, sub);

        wireClickListeners(sub);
        wireRememberToggle(sub);
        wireLeAudio(sub);
        sub.registerReceiver();
        mainHandler.post(() -> refreshSnapshot(sub));
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
                        leAudioManager.requestToggle(sub.mac, requested);
                        // Return false: do not flip the switch yet. The wirelesssettings dialog
                        // confirms first; the authoritative state arrives via the bridge reply
                        // and applyLeAudioToSwitch flips it then.
                        return false;
                    });
            invokeSetChangeListener(sub.prefs.leAudioSwitch, listener, changeListenerCls);
        }
        applyLeAudioToSwitch(sub);
    }

    /** Reflect the tracked LE Audio state onto the switch widget (visibility + checked + summary). */
    private void applyLeAudioToSwitch(Subscription sub) {
        Object sw = sub.prefs.leAudioSwitch;
        if (sw == null) return;
        boolean supported = leAudioManager.isSupported(sub.mac);
        PrefRef.setVisible(sw, supported);
        if (!supported) return;
        boolean enabled = leAudioManager.isEnabled(sub.mac);
        PrefRef.setChecked(sw, enabled);
        PrefRef.setSummary(sw, enabled ? Strings.LE_AUDIO_SUMMARY_ON : Strings.LE_AUDIO_SUMMARY_OFF);
    }

    /**
     * Wire up click listeners on the quality and sample-rate Preferences. Tap → pop a
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
     * Pop a floating dialog letting the user pick a quality step. Picks a sensible options list
     * even when AOSP {@code getCodecsSelectableCapabilities} returned nothing for this codec
     * (vendor codec quirk on every OPPO LHDC variant).
     */
    private void showQualityPicker(Subscription sub, Object sourcePref) {
        CodecSnapshot snapshot = lastSnapshot.get();
        if (snapshot == null) {
            Toast.makeText(context, Strings.STATE_CODEC_UNKNOWN, Toast.LENGTH_SHORT).show();
            return;
        }
        long[] options = snapshot.selectableCodecSpecific1;
        boolean preserveLhdcHighBits = false;
        if (options == null || options.length == 0) {
            options = CodecLabelTable.qualityFallback(snapshot.activeCodecType);
            preserveLhdcHighBits = CodecLabelTable.isLhdc(snapshot.activeCodecType);
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
            showChoicePopup(sub, sourcePref, dialogContext, entries, checked, which -> {
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

    private void showSampleRatePicker(Subscription sub, Object sourcePref) {
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
            showChoicePopup(sub, sourcePref, dialogContext, entries, checked, which -> {
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
            Context dialogContext,
            CharSequence[] entries,
            int checked,
            ChoiceCallback callback) {
        Activity activity = resolveLiveActivity(sub);
        if (activity == null || activity.getWindow() == null) return;
        Context popupContext = dialogContext != null ? dialogContext : activity;
        View anchor = findPreferenceView(sub, activity, sourcePref);
        View root = anchor != null ? anchor.getRootView() : fragmentRootView(sub.fragment);
        if (root == null) root = activity.getWindow().getDecorView();
        if (root == null) return;

        final PopupWindow[] popupRef = new PopupWindow[1];
        FrameLayout shell = new FrameLayout(popupContext);
        shell.setClipToPadding(false);
        shell.setClipChildren(false);
        int shadowPad = dp(popupContext, 8);
        shell.setPadding(shadowPad, shadowPad, shadowPad, shadowPad);

        LinearLayout list = new LinearLayout(popupContext);
        list.setOrientation(LinearLayout.VERTICAL);
        int horizontal = dp(popupContext, 16);
        int rowHeight = dp(popupContext, 50);
        int blue = Color.rgb(0, 105, 255);
        int textColor = Color.rgb(25, 25, 25);
        int dividerColor = Color.argb(28, 0, 0, 0);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(popupContext, 20));
        bg.setStroke(Math.max(1, dp(popupContext, 1)), Color.argb(20, 0, 0, 0));
        list.setBackground(bg);
        if (Build.VERSION.SDK_INT >= 21) {
            list.setElevation(dp(popupContext, 6));
        }

        for (int i = 0; i < entries.length; i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(popupContext);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(horizontal, 0, horizontal, 0);
            row.setMinimumHeight(rowHeight);

            TextView title = new TextView(popupContext);
            title.setText(entries[i]);
            title.setTextSize(17);
            title.setSingleLine(false);
            title.setGravity(Gravity.CENTER_VERTICAL);
            title.setTextColor(i == checked ? blue : textColor);
            row.addView(title, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

            TextView check = new TextView(popupContext);
            check.setText(i == checked ? "\u2713" : "");
            check.setTextColor(blue);
            check.setTextSize(26);
            check.setGravity(Gravity.CENTER);
            row.addView(check, new LinearLayout.LayoutParams(
                    dp(popupContext, 40), LinearLayout.LayoutParams.MATCH_PARENT));

            row.setOnClickListener(v -> {
                PopupWindow popup = popupRef[0];
                if (popup != null) popup.dismiss();
                callback.onChoice(index);
            });
            list.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, rowHeight));

            if (i + 1 < entries.length) {
                View divider = new View(popupContext);
                divider.setBackgroundColor(dividerColor);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1);
                lp.setMargins(horizontal, 0, horizontal, 0);
                list.addView(divider, lp);
            }
        }
        shell.addView(list, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        DisplayMetrics metrics = popupContext.getResources().getDisplayMetrics();
        int contentWidth = Math.min(metrics.widthPixels - dp(popupContext, 48), dp(popupContext, 176));
        int width = contentWidth + shadowPad * 2;
        PopupWindow popup = new PopupWindow(
                shell, width, LinearLayout.LayoutParams.WRAP_CONTENT, true);
        popupRef[0] = popup;
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setClippingEnabled(true);
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        if (Build.VERSION.SDK_INT >= 21) {
            popup.setElevation(dp(popupContext, 14));
        }
        int x = metrics.widthPixels - width - dp(popupContext, 40);
        int y = Math.round(metrics.heightPixels * 0.38f);
        if (anchor != null) {
            int[] loc = new int[2];
            anchor.getLocationInWindow(loc);
            x = Math.max(dp(popupContext, 16),
                    Math.min(x, metrics.widthPixels - width - dp(popupContext, 16)));
            y = loc[1] - dp(popupContext, 8) - shadowPad;
        }
        int popupHeightEstimate = entries.length * rowHeight
                + Math.max(0, entries.length - 1) + shadowPad * 2;
        int minY = dp(popupContext, 96);
        int maxY = Math.max(minY,
                metrics.heightPixels - popupHeightEstimate - dp(popupContext, 96));
        y = Math.max(minY, Math.min(y, maxY));
        popup.showAtLocation(root, Gravity.TOP | Gravity.START, x, y);
    }

    private static Activity resolveLiveActivity(Subscription sub) {
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
            mainHandler.post(() -> {
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

    private void publish(CodecSnapshot snapshot, Subscription sub) {
        if (snapshot != null) {
            lastSnapshot.set(snapshot);
        }
        if (snapshot == null) {
            lastSnapshot.set(null);
            renderUnknown(sub);
            return;
        }
        renderSnapshot(snapshot, sub, /* fromCache= */ false);
    }

    private void onPushedSnapshot(CodecSnapshot snapshot) {
        if (snapshot == null || snapshot.mac == null) return;
        mainHandler.post(() -> {
            lastSnapshot.set(snapshot);
            for (Subscription sub : subscriptions.values()) {
                if (snapshot.mac.equals(sub.mac) && !Boolean.FALSE.equals(sub.connected)) {
                    renderSnapshot(snapshot, sub, /* fromCache= */ false);
                }
            }
        });
    }

    private void renderUnknown(Subscription sub) {
        // When LE Audio is enabled the A2DP codec status is unavailable (the device is on the
        // LE transport), but that is NOT a disconnect — show the LC3 state instead of
        // "未连接耳机" so the codec block stays meaningful. Only treat it as LE Audio when the
        // device is not explicitly reported disconnected.
        if (leAudioManager.isEnabled(sub.mac) && !Boolean.FALSE.equals(sub.connected)) {
            if (sub.prefs.codecDisplay != null) {
                PrefRef.setTitle(sub.prefs.codecDisplay,
                        Strings.CODEC_BLOCK_TITLE + " : " + Strings.CODEC_LABEL_LC3);
            }
            PrefRef.setVisible(sub.prefs.qualityOption, false);
            PrefRef.setVisible(sub.prefs.sampleRateOption, false);
            setBlockDisabled(sub, false);
            applyLeAudioToSwitch(sub);
            if (sub.prefs.rememberToggle != null) {
                PrefRef.setChecked(sub.prefs.rememberToggle, prefs.isRemembered(sub.mac));
            }
            return;
        }
        if (sub.prefs.codecDisplay != null) {
            PrefRef.setTitle(sub.prefs.codecDisplay,
                    Strings.CODEC_BLOCK_TITLE + " : " + Strings.STATE_NO_DEVICE);
        }
        PrefRef.setSummary(sub.prefs.qualityOption, Strings.STATE_NO_DEVICE);
        PrefRef.setSummary(sub.prefs.sampleRateOption, Strings.STATE_NO_DEVICE);
        PrefRef.setVisible(sub.prefs.qualityOption, true);
        PrefRef.setVisible(sub.prefs.sampleRateOption, true);
        setBlockDisabled(sub, true);
        if (sub.prefs.rememberToggle != null) {
            PrefRef.setChecked(sub.prefs.rememberToggle, prefs.isRemembered(sub.mac));
        }
        applyLeAudioToSwitch(sub);
    }

    private void renderSnapshot(CodecSnapshot snapshot, Subscription sub, boolean fromCache) {
        if (Boolean.FALSE.equals(sub.connected)) {
            renderUnknown(sub);
            return;
        }
        // LE Audio override (TODO B): when LE Audio is on, the device runs LC3 over the LE
        // transport, so the A2DP codec / quality / sample-rate controls do not apply. Show
        // "蓝牙音质 : LC3" and hide the quality + sample-rate rows on BOTH surfaces.
        if (leAudioManager.isEnabled(sub.mac)) {
            if (sub.prefs.codecDisplay != null) {
                PrefRef.setTitle(sub.prefs.codecDisplay,
                        Strings.CODEC_BLOCK_TITLE + " : " + Strings.CODEC_LABEL_LC3);
            }
            PrefRef.setVisible(sub.prefs.qualityOption, false);
            PrefRef.setVisible(sub.prefs.sampleRateOption, false);
            setBlockDisabled(sub, false);
            applyLeAudioToSwitch(sub);
            if (sub.prefs.rememberToggle != null) {
                PrefRef.setChecked(sub.prefs.rememberToggle, prefs.isRemembered(sub.mac));
            }
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

        renderQuality(snapshot, sub);
        renderSampleRate(snapshot, sub);
        applyLeAudioToSwitch(sub);
        if (sub.prefs.rememberToggle != null) {
            PrefRef.setChecked(sub.prefs.rememberToggle, prefs.isRemembered(sub.mac));
        }
    }

    private static void setBlockDisabled(Subscription sub, boolean disabled) {
        if (sub == null || sub.prefs == null) return;
        PrefRef.setDisabled(sub.prefs.category, disabled);
        PrefRef.setDisabled(sub.prefs.codecDisplay, disabled);
        PrefRef.setDisabled(sub.prefs.qualityOption, disabled);
        PrefRef.setDisabled(sub.prefs.sampleRateOption, disabled);
        PrefRef.setDisabled(sub.prefs.rememberToggle, disabled);
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
            if (qualityValueMatches(snapshot.activeCodecType,
                    opt, snapshot.activeCodecSpecific1)) {
                known = true;
                break;
            }
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
        if (activeHz <= 0) activeHz = SAMPLE_RATE_48000_HZ;
        PrefRef.setSummary(r, CodecLabelTable.sampleRateLabel(activeHz));
        PrefRef.setVisible(r, true);
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
                    CodecLabelTable.LHDC_QUALITY_BALANCED);
        }
        if (isHighQualityRate(sampleRateBit)
                && quality == CodecLabelTable.LHDC_QUALITY_CONNECTION) {
            return replaceLhdcQuality(snapshot.activeCodecSpecific1,
                    CodecLabelTable.LHDC_QUALITY_BALANCED);
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
        return lowByte == CodecLabelTable.LHDC_QUALITY_HIGH
                || lowByte == CodecLabelTable.LHDC_QUALITY_HIGH_LEGACY;
    }

    private static boolean isHighQualityRate(int sampleRateBit) {
        return sampleRateBit == SAMPLE_RATE_96000_BIT
                || sampleRateBit == SAMPLE_RATE_192000_BIT;
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
            if (optionByte == activeByte) return true;
            return (optionByte == CodecLabelTable.LHDC_QUALITY_BALANCED
                    && activeByte == CodecLabelTable.LHDC_QUALITY_STANDARD)
                    || (optionByte == CodecLabelTable.LHDC_QUALITY_STANDARD
                    && activeByte == CodecLabelTable.LHDC_QUALITY_BALANCED)
                    || (optionByte == CodecLabelTable.LHDC_QUALITY_HIGH
                    && activeByte == CodecLabelTable.LHDC_QUALITY_HIGH_LEGACY)
                    || (optionByte == CodecLabelTable.LHDC_QUALITY_HIGH_LEGACY
                    && activeByte == CodecLabelTable.LHDC_QUALITY_HIGH);
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

    private final class Subscription {
        final String mac;
        final CodecPreferences prefs;
        final Object fragment;
        BroadcastReceiver receiver;
        Boolean connected;
        java.lang.ref.WeakReference<Activity> hostActivity;

        Subscription(String mac, CodecPreferences prefs, Object fragment) {
            this.mac = mac;
            this.prefs = prefs;
            this.fragment = fragment;
        }

        void registerReceiver() {
            if (receiver != null) return;
            receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    String action = intent.getAction();
                    if (ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                        if (!matchesSubscriptionDevice(intent)) return;
                        int state = intent.getIntExtra(EXTRA_CONNECTION_STATE, -1);
                        if (state != -1 && state != BluetoothProfile.STATE_CONNECTED) {
                            mainHandler.post(() -> {
                                connected = Boolean.FALSE;
                                lastSnapshot.set(null);
                                renderUnknown(Subscription.this);
                            });
                            return;
                        }
                        if (state == BluetoothProfile.STATE_CONNECTED) {
                            connected = Boolean.TRUE;
                        }
                        refreshSnapshot(Subscription.this);
                    } else if (ACTION_CODEC_CONFIG_CHANGED.equals(action)) {
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
    }
}
