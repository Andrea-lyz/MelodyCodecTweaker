package xyz.melodylsp.codec.host;

import android.app.Activity;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import xyz.melodylsp.codec.MelodyCodecLspEntry;
import xyz.melodylsp.codec.bt.BluetoothCodecReflect;
import xyz.melodylsp.codec.storage.PreferenceStore;
import xyz.melodylsp.codec.util.MLog;

/**
 * Installs all host-side hooks via the modern libxposed API. Each hook lambda is wrapped in
 * try / catch so that a single failure cannot cascade into a host crash (Requirement 9.5 /
 * Property 4 / 6).
 *
 * <p>Surface anchors:</p>
 * <ul>
 *   <li>{@code DetailMainActivity#onResume()} — top-level "Hi-Res 模式" panel. The fragment
 *       hosting the Preference list is R8-renamed; we locate it by scanning the activity's
 *       FragmentManager for a Preference-Fragment-shaped instance whose PreferenceScreen
 *       contains the {@code HiQualityAudioItem} key (the simple class name set by the host
 *       MoreSetting machinery is preserved because it is read via reflection at runtime).</li>
 *   <li>{@code OneSpaceListFragment#onViewCreated} — the {@link com.coui.appcompat.panel.COUIBottomSheetDialog}-hosted
 *       panel; class name is preserved.</li>
 *   <li>{@code HighAudioPreferenceFragment#onViewCreated} — kept for completeness even though
 *       the screen is not normally reachable on this build.</li>
 * </ul>
 *
 * <p>None of the {@code androidx.preference} types are referenced through compile-time
 * symbols. The host App ships androidx.preference but the names are minified, so referencing
 * them at compile time triggers {@link NoClassDefFoundError} at runtime. Everything goes
 * through {@link PrefRef} reflection.</p>
 */
public final class HostHookInstaller {

    private static final String CLASS_DETAIL_MAIN_ACTIVITY =
            "com.oplus.melody.ui.component.detail.DetailMainActivity";
    private static final String CLASS_HIGH_AUDIO =
            "com.oplus.melody.ui.component.detail.highaudio.HighAudioPreferenceFragment";
    private static final String CLASS_ONE_SPACE_FRAGMENT = "com.oplus.melody.onespace.d";
    /**
     * OneSpace detail Activity. Unlike the R8-hashed fragment ({@link #CLASS_ONE_SPACE_FRAGMENT}),
     * this FQN is preserved because it is registered in the host manifest
     * ({@code <activity android:name="com.oplus.melody.onespace.OneSpaceDetailActivity">}). We
     * hook its {@code onResume} as a version-resilient fallback and locate the Preference
     * fragment by scanning the FragmentManager (TODO A1).
     */
    private static final String CLASS_ONE_SPACE_ACTIVITY =
            "com.oplus.melody.onespace.OneSpaceDetailActivity";
    /**
     * Legacy Melody base Fragment. Older builds routed most Preference surfaces through this
     * class; newer builds split real Preference screens into {@code ui.base.b} /
     * {@code com.coui.appcompat.preference.h} / {@code androidx.preference.g}. We hook all of
     * them by structure below so short R8 fragment names can change without killing injection.
     */
    private static final String CLASS_BASE_PREFERENCE_FRAGMENT = "com.oplus.melody.ui.base.c";
    private static final String CLASS_BASE_COUI_PREFERENCE_FRAGMENT = "com.oplus.melody.ui.base.b";
    private static final String CLASS_COUI_PREFERENCE_FRAGMENT = "com.coui.appcompat.preference.h";
    private static final String CLASS_ANDROIDX_PREFERENCE_FRAGMENT = "androidx.preference.g";
    private static final String CLASS_GAME_SOUND_MANAGER = "x6.d";
    private static final String CLASS_GAME_SOUND_REPOSITORY_CLIENT = "x8.C1641b";
    private static final String CLASS_GAME_SOUND_REPOSITORY_SERVER = "x8.C1642c";

    /** {@code PreferenceCategory.setKey(cls.getSimpleName())} stamps this on the Hi-Res item. */
    private static final String KEY_HIRES_ITEM = "HiQualityAudioItem";
    /**
     * Secondary DetailMain anchor (TODO A3). The equalizer row is present on nearly every
     * earphone, including the open-fit / low-end models that have no Hi-Res switch, so it lets
     * us still inject the codec block when {@link #KEY_HIRES_ITEM} is absent. The host stamps
     * the simple class name {@code EqualizerItem} as the key via the same MoreSetting
     * machinery that produces {@code HiQualityAudioItem}.
     */
    private static final String KEY_EQUALIZER_ITEM = "EqualizerItem";
    private static final String KEY_CODEC_HEADER = "melody_codec_lsp_header";
    private static final String KEY_CODEC_CATEGORY = "melody_codec_lsp_category";
    private static final String KEY_CODEC_MODE = "melody_codec_lsp_codec_mode";
    private static final String KEY_CODEC_QUALITY = "melody_codec_lsp_quality";
    private static final String KEY_NOISE_SWITCH = "pref_noise_switch";
    private static final String KEY_NOISE_SWITCH_CATEGORY = "pref_noise_switch_category";
    private static final String KEY_MORE_SETTING = "pref_more_setting";
    private static final String KEY_FOOTER = "footer_preference";
    private static final Pattern MAC_PATTERN =
            Pattern.compile("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}");

    private final MelodyCodecLspEntry module;
    private final ClassLoader classLoader;
    private final DexKitHostResolver dexKit;
    private final Set<Object> attachedScreens =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Activity> knownActivities =
            Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<String, Set<Integer>> pendingGameModeTypes = new HashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private CodecController controller;
    private boolean activityScanRegistered;

    public HostHookInstaller(
            MelodyCodecLspEntry module,
            ClassLoader classLoader,
            String hostApkPath) {
        this.module = module;
        this.classLoader = classLoader;
        String moduleNativeLibraryDir = null;
        String moduleApkPath = null;
        try {
            ApplicationInfo moduleInfo = module.getModuleApplicationInfo();
            if (moduleInfo != null) {
                moduleNativeLibraryDir = moduleInfo.nativeLibraryDir;
                moduleApkPath = moduleInfo.sourceDir;
            }
        } catch (Throwable t) {
            MLog.w("DexKit module application info unavailable", t);
        }
        this.dexKit = new DexKitHostResolver(hostApkPath, moduleNativeLibraryDir, moduleApkPath);
    }

    public void install() {
        try {
            hookApplicationOnCreate();
            hookHighAudio();
            hookBasePreferenceFragment();
            hookOneSpace();
            hookDetailMainActivity();
            hookOneSpaceActivity();
            hookOfficialGameModeState();
        } finally {
            dexKit.close();
        }
    }

    private void hookApplicationOnCreate() {
        try {
            Method onCreate = Application.class.getMethod("onCreate");
            module.hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    bootstrapController((Application) chain.getThisObject());
                } catch (Throwable t) {
                    MLog.e("bootstrapController failed", t);
                }
                return result;
            });
        } catch (Throwable t) {
            MLog.e("hookApplicationOnCreate failed", t);
        }
    }

    private synchronized void bootstrapController(Application app) {
        if (controller != null) return;
        MLog.setDiagnosticContext(app, "melody");
        MLog.event("scope.host.context.ready");

        String hostVersion = "?";
        try {
            PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            hostVersion = info.versionName != null ? info.versionName : "?";
        } catch (Throwable ignored) {
        }
        MLog.setHostVersion(hostVersion);

        PreferenceStore prefs = new PreferenceStore(app);
        BluetoothCodecReflect reflect = new BluetoothCodecReflect(app);
        SettingsGlobalFallback fallback = new SettingsGlobalFallback(app);
        CodecBridgeClient bridge = new CodecBridgeClient(app, reflect, fallback);
        controller = new CodecController(app, reflect, bridge, prefs, this::requestSurfaceRescan);
        flushPendingOfficialGameModeStates();
        registerActivityScanCallbacks(app);

        MLog.event("controller.ready", "version", hostVersion);
    }

    /**
     * Generic update-resilience fallback. Direct hooks against host fragments are always at
     * risk because Melody's Preference fragments and their base class can be R8-renamed between
     * app releases. ActivityLifecycleCallbacks is a framework API, so it survives host updates:
     * whenever a supported Melody Activity resumes, scan its support FragmentManager and
     * dispatch only PreferenceScreens carrying known codec-surface markers.
     */
    private synchronized void registerActivityScanCallbacks(Application app) {
        if (activityScanRegistered) return;
        try {
            app.registerActivityLifecycleCallbacks(new ActivityScanCallbacks());
            activityScanRegistered = true;
            MLog.event("activity.scan.registered");
        } catch (Throwable t) {
            MLog.w("activity.scan.register failed", t);
        }
    }

    private final class ActivityScanCallbacks
            implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            rememberActivity(activity);
        }

        @Override
        public void onActivityStarted(Activity activity) {
            rememberActivity(activity);
        }

        @Override
        public void onActivityResumed(Activity activity) {
            if (!rememberActivity(activity)) return;
            scheduleKnownFragmentScan(activity, /* attempt= */ 0, "activity.scan");
        }

        @Override
        public void onActivityPaused(Activity activity) {
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
            if (activity != null) {
                knownActivities.remove(activity);
            }
        }
    }

    private boolean rememberActivity(Activity activity) {
        if (activity == null) return false;
        String name = activity.getClass().getName();
        if (!isSupportedHostActivityName(name)) return false;
        knownActivities.add(activity);
        return true;
    }

    private void requestSurfaceRescan(String reason) {
        mainHandler.post(() -> {
            int scheduled = 0;
            for (Activity activity : new ArrayList<>(knownActivities)) {
                if (activity == null || activity.isFinishing()) continue;
                String name = activity.getClass().getName();
                if (!isSupportedHostActivityName(name)) continue;
                scheduleKnownFragmentScan(activity, /* attempt= */ 0, "surface.rescan");
                scheduled++;
            }
            MLog.event("surface.rescan.requested", "reason", reason, "activities", scheduled);
        });
    }

    private static boolean isSupportedHostActivityName(String name) {
        return CLASS_DETAIL_MAIN_ACTIVITY.equals(name)
                || CLASS_ONE_SPACE_ACTIVITY.equals(name);
    }

    private static boolean isDetailMainHost(Object fragment) {
        if (CLASS_DETAIL_MAIN_ACTIVITY.equals(activityNameFromFragment(fragment))) return true;
        String name = fragment != null ? fragment.getClass().getName() : null;
        return CLASS_HIGH_AUDIO.equals(name);
    }

    private static boolean isOneSpaceHost(Object fragment) {
        if (CLASS_ONE_SPACE_ACTIVITY.equals(activityNameFromFragment(fragment))) return true;
        String name = fragment != null ? fragment.getClass().getName() : null;
        return name != null && name.startsWith("com.oplus.melody.onespace.");
    }

    private static boolean isSupportedFragmentHost(Object fragment) {
        if (fragment == null) return false;
        String activityName = activityNameFromFragment(fragment);
        if (isSupportedHostActivityName(activityName)) return true;
        String fragmentName = fragment.getClass().getName();
        return CLASS_HIGH_AUDIO.equals(fragmentName)
                || fragmentName.startsWith("com.oplus.melody.onespace.");
    }

    private static String activityNameFromFragment(Object fragment) {
        Object activity = invokeNoArg(fragment, "getActivity");
        if (activity == null) activity = invokeNoArg(fragment, "requireActivity");
        return activity != null ? activity.getClass().getName() : null;
    }

    /**
     * Hook every known PreferenceFragment-shaped base class, from Melody's wrappers down to the
     * bundled AndroidX implementation. Melody 16.7.1 moved DetailMain to an outer plain
     * Fragment plus an inner {@code DetailMainPreferenceFragment}; the inner class name is
     * R8-shortened, but its PreferenceFragment base chain is still discoverable.
     */
    private void hookBasePreferenceFragment() {
        String[] candidates = {
                CLASS_BASE_PREFERENCE_FRAGMENT,
                CLASS_BASE_COUI_PREFERENCE_FRAGMENT,
                CLASS_COUI_PREFERENCE_FRAGMENT,
                CLASS_ANDROIDX_PREFERENCE_FRAGMENT
        };
        java.util.Set<Method> hooked = new java.util.HashSet<>();
        int count = 0;
        for (String name : candidates) {
            Class<?> cls = loadHostClass(name);
            if (cls == null) continue;
            Method onViewCreated = findOnViewCreated(cls);
            if (onViewCreated == null || !hooked.add(onViewCreated)) continue;
            module.hook(onViewCreated).intercept(chain -> {
                Object result = chain.proceed();
                Object fragment = chain.getThisObject();
                // PreferenceScreen items often arrive asynchronously after onViewCreated; retry
                // for long enough to catch delayed WhitelistConfig / fragment transactions.
                scheduleSurfaceDispatch(fragment, /* attempt= */ 0);
                return result;
            });
            count++;
            MLog.event("preference.fragment.hooked",
                    "class", name, "methodOwner", onViewCreated.getDeclaringClass().getName());
        }
        if (count == 0) {
            MLog.w("No preference fragment onViewCreated hook installed");
        }
    }

    private void scheduleSurfaceDispatch(Object fragment, int attempt) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (controller == null) return;
            if (!isSupportedFragmentHost(fragment)) return;
            try {
                Object screen = PrefRef.getPreferenceScreen(fragment);
                if (screen != null && attachedScreens.contains(screen)) {
                    if (hasCodecMarker(screen)) {
                        scheduleNextSurfaceCheck(fragment, attempt);
                        return;
                    }
                    // The host can rebuild the same PreferenceScreen instance after our first
                    // injection. If the marker disappeared, allow the retry loop to inject again.
                    attachedScreens.remove(screen);
                }
                if (dispatchSurface(fragment)) {
                    scheduleNextSurfaceCheck(fragment, attempt);
                    return;
                }
            } catch (Throwable t) {
                MLog.e("Surface dispatch failed", t);
                return;
            }
            // PreferenceScreen items can be added asynchronously over many seconds (the
            // WhitelistConfig pipeline involves a network round-trip on first launch). Keep
            // retrying for ~15 s with a longer back-off than before.
            if (attempt < 14) {
                scheduleSurfaceDispatch(fragment, attempt + 1);
            } else {
                boolean knownCodecSurface = false;
                boolean hasScreen = false;
                try {
                    Object screen = PrefRef.getPreferenceScreen(fragment);
                    hasScreen = screen != null;
                    knownCodecSurface = hasScreen && isKnownCodecSurface(screen);
                } catch (Throwable ignored) {
                }
                if (!knownCodecSurface) {
                    MLog.event("surface.scan.skip_non_codec",
                            "class", fragment.getClass().getName(),
                            "screen", hasScreen,
                            "attempts", attempt + 1);
                } else {
                    MLog.w("surface anchor not found after retries; class="
                            + fragment.getClass().getName());
                }
            }
        }, attempt == 0 ? 200L : 1000L);
    }

    private void scheduleNextSurfaceCheck(Object fragment, int attempt) {
        if (attempt < 14) {
            scheduleSurfaceDispatch(fragment, attempt + 1);
        }
    }

    private static boolean hasCodecMarker(Object screen) {
        return PrefRef.findPreference(screen, KEY_CODEC_HEADER) != null
                || PrefRef.findPreference(screen, KEY_CODEC_CATEGORY) != null
                || PrefRef.findPreference(screen, KEY_CODEC_MODE) != null
                || PrefRef.findPreference(screen, KEY_CODEC_QUALITY) != null;
    }

    /**
     * Decide which surface the fragment represents by inspecting the keys that exist on its
     * PreferenceScreen, then route to the matching insertion routine.
     */
    private boolean dispatchSurface(Object fragment) {
        Object screen = PrefRef.getPreferenceScreen(fragment);
        if (screen == null) return false;
        boolean detailMainHost = isDetailMainHost(fragment);
        boolean oneSpaceHost = isOneSpaceHost(fragment);

        if (hasCodecMarker(screen)) {
            // Already injected on this screen.
            attachedScreens.add(screen);
            return true;
        }

        if (detailMainHost && PrefRef.findPreference(screen, KEY_HIRES_ITEM) != null) {
            return injectAfterHires(fragment, screen);
        }

        if (oneSpaceHost && isOneSpaceScreen(screen)) {
            return injectIntoOneSpace(fragment, screen);
        }

        // DetailMain fallback anchor for earphones without a Hi-Res switch.
        if (detailMainHost && PrefRef.findPreference(screen, KEY_EQUALIZER_ITEM) != null) {
            return injectAfterAnchorKey(fragment, screen, KEY_EQUALIZER_ITEM);
        }

        return false;
    }

    /** Inject the DetailMain codec block right after the preference identified by {@code key}. */
    private boolean injectAfterAnchorKey(Object fragment, Object screen, String key) {
        Object anchor = PrefRef.findPreference(screen, key);
        if (anchor == null) return false;
        return injectAfterPreference(fragment, screen, anchor);
    }

    /**
     * Shared DetailMain insertion routine: place the codec block immediately after {@code anchor}
     * within the anchor's parent group, shifting subsequent orders to make room. Used by both
     * the Hi-Res path and the A3 fallback anchors so they behave identically.
     */
    private boolean injectAfterPreference(Object fragment, Object screen, Object anchor) {
        if (anchor == null) return false;
        Context themedContext = resolveThemedContext(fragment);
        if (themedContext == null) return false;

        Object parent = PrefRef.getParent(anchor);
        if (parent == null) parent = screen;
        int anchorOrder = PrefRef.getOrder(anchor);
        int targetOrder = anchorOrder + 1;
        shiftPreferenceOrders(parent, targetOrder, +6);

        String mac = resolveMacFromActivityIntent(fragment);
        if (mac == null) {
            MLog.w("DetailMain fallback insertion: mac unresolved; skip");
            return false;
        }
        CodecPreferences prefs = CodecBlockBuilder.buildAndInsert(
                themedContext, parent, targetOrder,
                /* wrapInCategory= */ false, /* includeRemember= */ true,
                /* includeLeAudio= */ true);
        if (prefs == null) return false;
        controller.attach(mac, prefs, fragment);
        attachedScreens.add(screen);
        MLog.event("detailmain_fallback.injected", "mac_len", mac.length(),
                "order", targetOrder, "anchor", PrefRef.getKey(anchor),
                "fragment", fragment.getClass().getName());
        return true;
    }

    private boolean injectAfterHires(Object fragment, Object screen) {
        Object anchor = PrefRef.findPreference(screen, KEY_HIRES_ITEM);
        if (anchor == null) return false;
        Context themedContext = resolveThemedContext(fragment);
        if (themedContext == null) return false;

        Object parent = PrefRef.getParent(anchor);
        if (parent == null) parent = screen;
        int anchorOrder = PrefRef.getOrder(anchor);
        int targetOrder = anchorOrder + 1;
        shiftPreferenceOrders(parent, targetOrder, +6);

        String mac = resolveMacFromActivityIntent(fragment);
        if (mac == null) {
            MLog.w("Hi-Res-anchored insertion: mac unresolved; skip");
            return false;
        }
        // DetailMain uses the same top-level row group as OneSpace so the neighbouring host
        // cards visually connect instead of forming double-rounded notches.
        CodecPreferences prefs = CodecBlockBuilder.buildAndInsert(
                themedContext, parent, targetOrder,
                /* wrapInCategory= */ false, /* includeRemember= */ true,
                /* includeLeAudio= */ true);
        if (prefs == null) return false;
        controller.attach(mac, prefs, fragment);
        attachedScreens.add(screen);
        MLog.event("hires_anchored.injected", "mac_len", mac.length(),
                "order", targetOrder, "fragment", fragment.getClass().getName());
        return true;
    }

    private boolean injectIntoOneSpace(Object fragment, Object screen) {
        Context themedContext = resolveThemedContext(fragment);
        if (themedContext == null) return false;

        Object noiseSwitchCategory = PrefRef.findPreference(screen, KEY_NOISE_SWITCH_CATEGORY);
        Object noiseMenuCategory = PrefRef.findPreference(screen, MelodyResIds.KEY_NOISE_MENU_CATEGORY);
        Object noiseSwitch = PrefRef.findPreference(screen, KEY_NOISE_SWITCH);
        Object moreSettingCategory = PrefRef.findPreference(
                screen, MelodyResIds.KEY_MORE_SETTING_CATEGORY);
        Object moreSettingRow = PrefRef.findPreference(screen, KEY_MORE_SETTING);
        if (moreSettingCategory == null && moreSettingRow != null) {
            Object parent = PrefRef.getParent(moreSettingRow);
            moreSettingCategory = parent != null && parent != screen ? parent : moreSettingRow;
        }
        Object footer = PrefRef.findPreference(screen, KEY_FOOTER);

        Object anchor = null;
        boolean insertAfterAnchor = false;
        if (noiseMenuCategory != null && PrefRef.isVisible(noiseMenuCategory)) {
            anchor = noiseMenuCategory;
            insertAfterAnchor = true;
        } else if (noiseSwitchCategory != null && PrefRef.isVisible(noiseSwitchCategory)) {
            anchor = noiseSwitchCategory;
            insertAfterAnchor = true;
        } else if (noiseSwitch != null && PrefRef.isVisible(noiseSwitch)) {
            anchor = noiseSwitch;
            insertAfterAnchor = true;
        } else if (moreSettingCategory != null) {
            anchor = moreSettingCategory;
        } else {
            anchor = footer;
        }
        int targetOrder;
        if (anchor != null) {
            int anchorOrder = PrefRef.getOrder(anchor);
            targetOrder = Math.max(0, anchorOrder + (insertAfterAnchor ? 1 : 0));
        } else {
            targetOrder = PrefRef.getPreferenceCount(screen);
        }
        String mac = resolveMacFromActivityIntent(fragment);
        if (mac == null) {
            MLog.w("OneSpace mac unresolved; skip");
            return false;
        }
        shiftPreferenceOrders(screen, targetOrder, +1);
        // OneSpace is for instant switching only. Keep it in a visible Category so COUI owns
        // the card background; the hidden noise-menu Category renders as bare rows here.
        CodecPreferences prefs = CodecBlockBuilder.buildAndInsert(
                themedContext, screen, targetOrder,
                /* wrapInCategory= */ true, /* includeRemember= */ false,
                /* includeLeAudio= */ true);
        if (prefs == null) return false;
        if (!insertAfterAnchor) {
            setCategoryTopMarginZero(prefs.category);
        }
        // Add bottom padding to the RecyclerView so the last host row ("耳机设置") is not
        // clipped by overscroll-bounce after our injection extends the content height.
        addRecyclerBottomPadding(fragment, dpToPx(themedContext, 32));
        controller.attach(mac, prefs, fragment);
        attachedScreens.add(screen);
        MLog.event("onespace.injected", "mac_len", mac.length(), "order", targetOrder,
                "anchor", anchor != null ? PrefRef.getKey(anchor) : "none");
        return true;
    }

    /**
     * Increase the {@code RecyclerView}'s bottom padding so the last row settles cleanly after
     * an overscroll bounce. Without this, the host's "耳机设置" row at the bottom of OneSpace
     * gets visually clipped because our injection extends the content height past the natural
     * scroll-stop point.
     *
     * <p>{@code androidx.preference.PreferenceFragmentCompat} keeps the RecyclerView in field
     * {@code mList} (renamed by R8). We locate it by walking the fragment's {@code View} tree
     * looking for the only {@code RecyclerView}-shaped child. We type the result as
     * {@link android.view.View} on purpose — the module APK does not depend on
     * {@code androidx.recyclerview} (the host ships its own copy that R8 has minified), so
     * compile-time references to the class fail to build. {@code setPadding} /
     * {@code setClipToPadding} are inherited from {@link android.view.ViewGroup}.</p>
     */
    private static void addRecyclerBottomPadding(Object fragment, int extraPx) {
        try {
            android.view.View root = null;
            try {
                Method m = fragment.getClass().getMethod("getView");
                Object v = m.invoke(fragment);
                if (v instanceof android.view.View) root = (android.view.View) v;
            } catch (Throwable ignored) {
            }
            if (root == null) return;
            android.view.View rv = findRecyclerView(root);
            if (!(rv instanceof android.view.ViewGroup)) return;
            android.view.ViewGroup group = (android.view.ViewGroup) rv;
            int currentBottom = group.getPaddingBottom();
            // Idempotency guard: if already padded enough, skip.
            if (currentBottom >= extraPx) return;
            group.setClipToPadding(false);
            group.setPadding(group.getPaddingLeft(), group.getPaddingTop(),
                    group.getPaddingRight(), currentBottom + extraPx);
        } catch (Throwable t) {
            MLog.w("addRecyclerBottomPadding failed", t);
        }
    }

    /**
     * Recursively walk the view tree returning the first descendant whose runtime class is
     * {@code androidx.recyclerview.widget.RecyclerView} (or anything that extends it). We
     * detect it by class-name string match instead of {@code instanceof} because the module
     * APK does not have RecyclerView on its classpath.
     */
    private static android.view.View findRecyclerView(android.view.View root) {
        if (root == null) return null;
        if (isRecyclerViewClass(root.getClass())) return root;
        if (!(root instanceof android.view.ViewGroup)) return null;
        android.view.ViewGroup group = (android.view.ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            android.view.View found = findRecyclerView(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private static boolean isRecyclerViewClass(Class<?> cls) {
        Class<?> cur = cls;
        while (cur != null && cur != Object.class) {
            if ("androidx.recyclerview.widget.RecyclerView".equals(cur.getName())) return true;
            cur = cur.getSuperclass();
        }
        return false;
    }

    private static int dpToPx(Context context, int dp) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    private static void setCategoryTopMarginZero(Object category) {
        if (category == null) return;
        try {
            Method m = category.getClass().getMethod("m", int.class);
            m.invoke(category, 2);
        } catch (Throwable ignored) {
        }
    }

    /** Shifts the {@code order} of every Preference at index >= {@code threshold} by {@code delta}. */
    private static void shiftPreferenceOrders(Object container, int threshold, int delta) {
        int count = PrefRef.getPreferenceCount(container);
        for (int i = 0; i < count; i++) {
            Object pref = PrefRef.getPreference(container, i);
            if (pref == null) continue;
            int order = PrefRef.getOrder(pref);
            if (order >= threshold) {
                PrefRef.setOrder(pref, order + delta);
            }
        }
    }

    private void hookHighAudio() {
        Class<?> fragCls = loadHostClass(CLASS_HIGH_AUDIO);
        if (fragCls == null) {
            // HighAudio Fragment is rarely present on this build; not an error.
            return;
        }
        Method onViewCreated = findOnViewCreated(fragCls);
        if (onViewCreated == null) return;
        module.hook(onViewCreated).intercept(chain -> {
            Object result = chain.proceed();
            Object fragment = chain.getThisObject();
            // HighAudioPreferenceFragment may or may not inherit com.oplus.melody.ui.base.c;
            // dispatch via the same surface code so it works either way.
            scheduleSurfaceDispatch(fragment, /* attempt= */ 0);
            return result;
        });
    }

    private void hookOneSpace() {
        // OneSpaceListFragment (com.oplus.melody.onespace.d) extends
        // com.coui.appcompat.preference.h directly — NOT com.oplus.melody.ui.base.c — so the
        // base-class hook does not cover it. Hook OneSpace explicitly.
        java.util.List<String> names = new java.util.ArrayList<>();
        names.add(CLASS_ONE_SPACE_FRAGMENT);
        names.addAll(dexKit.findClassesUsingStrings(
                "onespace.list.fragment",
                "com.oplus.melody.onespace",
                "pref_more_setting",
                "pref_more_setting_category",
                "pref_noise_switch",
                "pref_noise_switch_category"));
        java.util.Set<Method> hooked = new java.util.HashSet<>();
        int count = 0;
        for (String name : names) {
            Class<?> fragCls = loadHostClass(name);
            if (fragCls == null) continue;
            Method onViewCreated = findOnViewCreated(fragCls);
            if (onViewCreated == null || !hooked.add(onViewCreated)) continue;
            module.hook(onViewCreated).intercept(chain -> {
                Object result = chain.proceed();
                Object fragment = chain.getThisObject();
                scheduleSurfaceDispatch(fragment, /* attempt= */ 0);
                return result;
            });
            count++;
            MLog.event("onespace.fragment.hooked",
                    "class", name,
                    "methodOwner", onViewCreated.getDeclaringClass().getName());
        }
        if (count == 0) {
            MLog.w("OneSpace direct fragment hook unavailable; activity scan fallback remains");
        }
    }

    /**
     * DetailMain's Activity FQN is manifest-stable while its actual PreferenceFragment and
     * shared base class can move after a Melody update. Hook the stable Activity too and scan
     * for the same PreferenceScreen markers used by the direct fragment hooks.
     */
    private void hookDetailMainActivity() {
        Class<?> actCls = loadHostClass(CLASS_DETAIL_MAIN_ACTIVITY);
        if (actCls == null) {
            MLog.w("DetailMainActivity class not found; stable fallback unavailable");
            return;
        }
        Method onResume = findHostDeclaredMethod(actCls, "onResume");
        if (onResume == null) {
            onResume = findHostDeclaredMethod(actCls, "onStart");
        }
        if (onResume == null) {
            MLog.w("DetailMainActivity onResume/onStart not found");
            return;
        }
        module.hook(onResume).intercept(chain -> {
            Object result = chain.proceed();
            try {
                Object activity = chain.getThisObject();
                if (activity != null && actCls.isInstance(activity)) {
                    scheduleKnownFragmentScan(activity, /* attempt= */ 0, "detailmain.activity");
                }
            } catch (Throwable t) {
                MLog.e("DetailMain activity scan failed", t);
            }
            return result;
        });
        MLog.event("detailmain.activity.hooked");
    }

    /**
     * Version-resilient OneSpace fallback (TODO A1). The direct fragment hook
     * ({@link #hookOneSpace}) targets the R8-hashed class {@code com.oplus.melody.onespace.d},
     * which is very likely to be renamed on the next host update (to {@code c} / {@code e} /
     * &c.), silently killing OneSpace injection. {@code OneSpaceDetailActivity} keeps a stable
     * FQN because it is declared in the host manifest, so we additionally hook its
     * {@code onResume} and locate the Preference fragment by scanning the FragmentManager for
     * an instance whose PreferenceScreen carries OneSpace-specific keys.
     *
     * <p>This runs alongside the direct fragment hook; {@link #attachedScreens} de-duplicates so
     * whichever path fires first wins and the other no-ops.</p>
     */
    private void hookOneSpaceActivity() {
        Class<?> actCls = loadHostClass(CLASS_ONE_SPACE_ACTIVITY);
        if (actCls == null) {
            MLog.w("OneSpaceDetailActivity class not found; A1 fallback unavailable");
            return;
        }
        Method onResume = findHostDeclaredMethod(actCls, "onResume");
        if (onResume == null) {
            // Fall back to onStart, which OneSpaceDetailActivity overrides directly.
            onResume = findHostDeclaredMethod(actCls, "onStart");
        }
        if (onResume == null) {
            MLog.w("OneSpaceDetailActivity onResume/onStart not found");
            return;
        }
        module.hook(onResume).intercept(chain -> {
            Object result = chain.proceed();
            try {
                Object activity = chain.getThisObject();
                // onResume may resolve to a shared superclass (androidx FragmentActivity), so
                // the hook can fire for unrelated activities; only act on OneSpace instances.
                if (activity != null && actCls.isInstance(activity)) {
                    scheduleOneSpaceFragmentScan(activity, /* attempt= */ 0);
                }
            } catch (Throwable t) {
                MLog.e("OneSpace activity onResume scan failed", t);
            }
            return result;
        });
        MLog.event("onespace.activity.hooked");
    }

    private void hookOfficialGameModeState() {
        int count = 0;
        count += hookOfficialGameModeStateMethod(
                CLASS_GAME_SOUND_MANAGER, "host.game_sound_manager");
        count += hookOfficialGameModeStateMethod(
                CLASS_GAME_SOUND_REPOSITORY_CLIENT, "host.game_sound_repository_client");
        count += hookOfficialGameModeStateMethod(
                CLASS_GAME_SOUND_REPOSITORY_SERVER, "host.game_sound_repository_server");
        MLog.event("game.mode.hooks", "count", count);
    }

    private int hookOfficialGameModeStateMethod(String className, String source) {
        Class<?> cls = loadHostClass(className);
        if (cls == null) return 0;
        int count = 0;
        for (Method m : cls.getDeclaredMethods()) {
            if (!isOfficialGameModeStateMethod(m)) continue;
            try {
                m.setAccessible(true);
            } catch (Throwable ignored) {
            }
            module.hook(m).intercept(chain -> {
                Object[] args = chain.getArgs().toArray();
                String mac = args.length > 0 ? normalizeMacCandidate(args[0]) : null;
                int type = args.length > 1 && args[1] instanceof Number
                        ? ((Number) args[1]).intValue()
                        : -1;
                boolean active = args.length > 2 && Boolean.TRUE.equals(args[2]);
                if (active) {
                    notifyOfficialGameModeState(mac, type, true, source);
                }
                Object result = chain.proceed();
                if (!active) {
                    notifyOfficialGameModeState(mac, type, false, source);
                }
                return result;
            });
            count++;
            MLog.event("game.mode.method.hooked",
                    "class", className,
                    "method", m.getName(),
                    "source", source);
        }
        return count;
    }

    private static boolean isOfficialGameModeStateMethod(Method method) {
        if (method == null || !"d".equals(method.getName())) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 3
                && params[0] == String.class
                && (params[1] == int.class || params[1] == Integer.class)
                && (params[2] == boolean.class || params[2] == Boolean.class);
    }

    private void notifyOfficialGameModeState(
            String mac,
            int type,
            boolean active,
            String source) {
        String key = normalizeMac(mac);
        if (key == null) return;
        CodecController current = controller;
        if (current == null) {
            rememberPendingOfficialGameModeState(key, type, active);
            MLog.event("game.mode.pending",
                    "mac", A2dpRouteReadiness.redactMac(key),
                    "type", type,
                    "active", active,
                    "source", source);
            return;
        }
        current.onOfficialGameModeState(key, type, active, source);
    }

    private synchronized void rememberPendingOfficialGameModeState(
            String key,
            int type,
            boolean active) {
        Set<Integer> activeTypes = pendingGameModeTypes.get(key);
        if (active) {
            if (activeTypes == null) {
                activeTypes = new HashSet<>();
                pendingGameModeTypes.put(key, activeTypes);
            }
            activeTypes.add(type);
        } else if (activeTypes != null) {
            activeTypes.remove(type);
            if (activeTypes.isEmpty()) {
                pendingGameModeTypes.remove(key);
            }
        }
    }

    private void flushPendingOfficialGameModeStates() {
        Map<String, Set<Integer>> copy;
        synchronized (this) {
            if (pendingGameModeTypes.isEmpty()) return;
            copy = new HashMap<>();
            for (Map.Entry<String, Set<Integer>> entry : pendingGameModeTypes.entrySet()) {
                copy.put(entry.getKey(), new HashSet<>(entry.getValue()));
            }
            pendingGameModeTypes.clear();
        }
        CodecController current = controller;
        if (current == null) return;
        int count = 0;
        for (Map.Entry<String, Set<Integer>> entry : copy.entrySet()) {
            for (Integer type : entry.getValue()) {
                current.onOfficialGameModeState(
                        entry.getKey(),
                        type != null ? type : -1,
                        true,
                        "host.pending_game_mode");
                count++;
            }
        }
        MLog.event("game.mode.pending.flushed", "count", count);
    }

    /**
     * Walk {@code activity.getSupportFragmentManager().getFragments()} (and nested child
     * managers) looking for a Preference fragment whose screen contains OneSpace markers, then
     * route it through the normal surface dispatch. Retries on a back-off because the bottom-
     * sheet fragment + its PreferenceScreen are attached asynchronously after onResume.
     */
    private void scheduleOneSpaceFragmentScan(Object activity, int attempt) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (controller == null) return;
            try {
                java.util.List<Object> fragments = collectFragments(activity);
                for (Object fragment : fragments) {
                    Object screen = PrefRef.getPreferenceScreen(fragment);
                    if (screen == null) continue;
                    if (!isOneSpaceScreen(screen)) continue;
                    if (attachedScreens.contains(screen) && hasCodecMarker(screen)) {
                        return; // Already injected by either path.
                    }
                    scheduleSurfaceDispatch(fragment, /* attempt= */ 0);
                    MLog.event("onespace.activity.fragment_found",
                            "fragment", fragment.getClass().getName());
                    return;
                }
            } catch (Throwable t) {
                MLog.e("OneSpace fragment scan failed", t);
                return;
            }
            if (attempt < 12) {
                scheduleOneSpaceFragmentScan(activity, attempt + 1);
            }
        }, attempt == 0 ? 300L : 800L);
    }

    /**
     * Generic Activity -> FragmentManager scanner. It intentionally only routes screens with
     * strong markers (Hi-Res / Equalizer / OneSpace / our own marker) so ordinary Melody
     * preference pages cannot pick up the codec block.
     */
    private void scheduleKnownFragmentScan(Object activity, int attempt, String source) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (controller == null) return;
            boolean found = false;
            try {
                java.util.List<Object> fragments = collectFragments(activity);
                for (Object fragment : fragments) {
                    Object screen = PrefRef.getPreferenceScreen(fragment);
                    if (screen == null) continue;
                    if (!isKnownCodecSurface(screen)) continue;
                    found = true;
                    if (attachedScreens.contains(screen) && hasCodecMarker(screen)) {
                        continue;
                    }
                    scheduleSurfaceDispatch(fragment, /* attempt= */ 0);
                    MLog.event(source + ".fragment_found",
                            "fragment", fragment.getClass().getName());
                }
            } catch (Throwable t) {
                MLog.e(source + " failed", t);
                return;
            }
            if (!found && attempt < 12) {
                scheduleKnownFragmentScan(activity, attempt + 1, source);
            }
        }, attempt == 0 ? 300L : 800L);
    }

    /** True when a PreferenceScreen is one of the surfaces we own or should recover. */
    private static boolean isKnownCodecSurface(Object screen) {
        return hasCodecMarker(screen)
                || PrefRef.findPreference(screen, KEY_HIRES_ITEM) != null
                || PrefRef.findPreference(screen, KEY_EQUALIZER_ITEM) != null
                || isOneSpaceScreen(screen);
    }

    /** True when a PreferenceScreen carries any OneSpace-specific marker key. */
    private static boolean isOneSpaceScreen(Object screen) {
        return PrefRef.findPreference(screen, MelodyResIds.KEY_NOISE_MENU_CATEGORY) != null
                || PrefRef.findPreference(screen, MelodyResIds.KEY_MORE_SETTING_CATEGORY) != null
                || PrefRef.findPreference(screen, KEY_NOISE_SWITCH_CATEGORY) != null
                || PrefRef.findPreference(screen, KEY_NOISE_SWITCH) != null
                || PrefRef.findPreference(screen, KEY_MORE_SETTING) != null;
    }

    /**
     * Collect Fragments reachable from an Activity's support FragmentManager, descending one
     * level into each fragment's child FragmentManager (OneSpace hosts its Preference list in a
     * COUIBottomSheetDialogFragment whose content is a child fragment). All reflective so we do
     * not compile against {@code androidx.fragment}, which the host ships R8-minified.
     */
    private static java.util.List<Object> collectFragments(Object activity) {
        java.util.List<Object> out = new java.util.ArrayList<>();
        Object fm = invokeNoArg(activity, "getSupportFragmentManager");
        if (fm == null) return out;
        collectFromManager(fm, out, /* depth= */ 0);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void collectFromManager(Object fragmentManager, java.util.List<Object> out, int depth) {
        if (fragmentManager == null || depth > 2) return;
        java.util.List<Object> fragments = extractFragmentsFromManager(fragmentManager);
        if (fragments.isEmpty()) return;
        for (Object fragment : fragments) {
            if (fragment == null) continue;
            if (!out.contains(fragment)) out.add(fragment);
            Object childFm = invokeNoArg(fragment, "getChildFragmentManager");
            if (childFm != null && childFm != fragmentManager) {
                collectFromManager(childFm, out, depth + 1);
            }
        }
    }

    /**
     * AndroidX FragmentManager's public {@code getFragments()} name is not stable inside
     * vendor apps: in Melody 16.7.1 it is stripped/renamed and the actual list lives in a
     * FragmentStore field. Recover by scanning fields for Lists whose elements inherit
     * {@code androidx.fragment.app.Fragment}.
     */
    private static java.util.List<Object> extractFragmentsFromManager(Object fragmentManager) {
        java.util.List<Object> out = new java.util.ArrayList<>();
        Object direct = invokeNoArg(fragmentManager, "getFragments");
        appendFragmentList(direct, out);
        appendFragmentListsFromFields(fragmentManager, out, 2, new IdentityHashMap<>());
        return out;
    }

    private static void appendFragmentListsFromFields(
            Object root,
            java.util.List<Object> out,
            int depth,
            IdentityHashMap<Object, Boolean> seen) {
        if (root == null || depth < 0 || seen.containsKey(root)) return;
        seen.put(root, Boolean.TRUE);
        Class<?> cls = root.getClass();
        while (cls != null && cls != Object.class) {
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                Class<?> ft = f.getType();
                if (ft.isPrimitive() || ft.isArray()) continue;
                if (ft == String.class || ft.getName().startsWith("java.lang.")) continue;
                try {
                    f.setAccessible(true);
                    Object value = f.get(root);
                    if (value == null) continue;
                    if (appendFragmentList(value, out)) continue;
                    if (depth > 0 && shouldRecurseFragmentField(value)) {
                        appendFragmentListsFromFields(value, out, depth - 1, seen);
                    }
                } catch (Throwable ignored) {
                }
            }
            cls = cls.getSuperclass();
        }
    }

    private static boolean appendFragmentList(Object value, java.util.List<Object> out) {
        if (!(value instanceof java.util.List)) return false;
        boolean any = false;
        for (Object item : (java.util.List<?>) value) {
            if (!isFragmentLike(item)) continue;
            any = true;
            if (!out.contains(item)) out.add(item);
        }
        return any;
    }

    private static boolean shouldRecurseFragmentField(Object value) {
        String name = value.getClass().getName();
        return name.startsWith("androidx.fragment.")
                || name.startsWith("androidx.lifecycle.")
                || name.startsWith("com.oplus.melody.");
    }

    private static boolean isFragmentLike(Object obj) {
        if (obj == null) return false;
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            if ("androidx.fragment.app.Fragment".equals(cls.getName())) return true;
            cls = cls.getSuperclass();
        }
        return false;
    }

    private static Object invokeNoArg(Object target, String name) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(name);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Walks the class hierarchy and returns the first method named {@code onViewCreated} that
     * accepts {@code (View, Bundle)}. Doing so avoids depending on the exact declaring class —
     * the host fragment subclass may or may not override the method.
     */
    private Method findOnViewCreated(Class<?> startCls) {
        Class<?> viewCls;
        try {
            viewCls = Class.forName("android.view.View", false, classLoader);
        } catch (ClassNotFoundException e) {
            return null;
        }
        Class<?> cls = startCls;
        while (cls != null && cls != Object.class) {
            try {
                Method m = cls.getDeclaredMethod("onViewCreated", viewCls, Bundle.class);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                cls = cls.getSuperclass();
            }
        }
        return null;
    }

    /** Walk the class hierarchy looking for a no-arg method by name. */
    private static Method findMethodIgnoringDeclared(Class<?> startCls, String name) {
        Class<?> cls = startCls;
        while (cls != null && cls != Object.class) {
            try {
                Method m = cls.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                cls = cls.getSuperclass();
            }
        }
        return null;
    }

    /**
     * Like {@link #findMethodIgnoringDeclared} but only matches a method declared on a host-
     * owned class (FQN starting with {@code com.oplus.}). This avoids resolving the framework
     * {@code Activity.onResume} / androidx {@code FragmentActivity.onResume}, which would make
     * the hook fire for every activity in the process instead of just OneSpace.
     */
    private static Method findHostDeclaredMethod(Class<?> startCls, String name) {
        Class<?> cls = startCls;
        while (cls != null && cls != Object.class) {
            String clsName = cls.getName();
            if (clsName.startsWith("com.oplus.")) {
                try {
                    Method m = cls.getDeclaredMethod(name);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException ignored) {
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    /** Calls {@code Fragment.requireContext()} via reflection. */
    private static Context resolveContext(Object fragment) {
        try {
            Method m = fragment.getClass().getMethod("requireContext");
            Object ctx = m.invoke(fragment);
            return ctx instanceof Context ? (Context) ctx : null;
        } catch (Throwable ignored) {
        }
        try {
            Method m = fragment.getClass().getMethod("getContext");
            Object ctx = m.invoke(fragment);
            return ctx instanceof Context ? (Context) ctx : null;
        } catch (Throwable t) {
            MLog.w("resolveContext failed", t);
            return null;
        }
    }

    /**
     * Resolve the {@link Context} that has the host {@code preferenceTheme} overlay applied.
     *
     * <p>{@code androidx.preference.PreferenceFragmentCompat} caches a themed context inside
     * its {@code PreferenceManager} so that programmatic {@code new COUIPreferenceCategory(ctx)}
     * picks up {@code R.style.Preference_Category_*} (= correct title color, padding, dark-mode
     * tint). {@code Fragment.requireContext()} returns the un-themed activity context, so any
     * Preference instantiated against it paints centre-aligned grey text on a transparent
     * background. We prefer the themed context.</p>
     */
    private static Context resolveThemedContext(Object fragment) {
        try {
            Method getPm = findMethodIgnoringDeclared(fragment.getClass(), "getPreferenceManager");
            if (getPm != null) {
                Object pm = getPm.invoke(fragment);
                if (pm != null) {
                    Method getCtx = findMethodIgnoringDeclared(pm.getClass(), "getContext");
                    if (getCtx != null) {
                        Object ctx = getCtx.invoke(pm);
                        if (ctx instanceof Context) return (Context) ctx;
                    }
                }
            }
        } catch (Throwable t) {
            MLog.w("resolveThemedContext via PreferenceManager failed", t);
        }
        return resolveContext(fragment);
    }

    /**
     * Resolves the MAC address that the current Preference surface is bound to.
     *
     * <p>Normal Melody entry points still pass {@code device_mac_info}, but Settings ->
     * WirelessSettings -> DetailMainActivity can start Melody without that extra. In that path
     * the hook is installed correctly, yet injection used to be skipped because no device id was
     * available. Keep the stable Intent key as the fast path, then fall back to scanning host
     * objects and finally the active A2DP device.</p>
     */
    private static String resolveMacFromActivityIntent(Object fragment) {
        Object activity = null;
        try {
            Method getActivity = fragment.getClass().getMethod("getActivity");
            activity = getActivity.invoke(fragment);
        } catch (Throwable t) {
            MLog.w("resolveMac activity failed", t);
        }

        Intent intent = null;
        try {
            if (activity != null) {
                Method getIntent = activity.getClass().getMethod("getIntent");
                Object value = getIntent.invoke(activity);
                if (value instanceof Intent) intent = (Intent) value;
            }
        } catch (Throwable t) {
            MLog.w("resolveMac intent failed", t);
        }

        String mac = macFromIntent(intent);
        if (mac != null) {
            MLog.event("mac.resolved", "source", "intent");
            return mac;
        }

        mac = findMacInObject(fragment, 2, new IdentityHashMap<>());
        if (mac != null) {
            MLog.event("mac.resolved", "source", "fragment_fields");
            return mac;
        }

        mac = findMacInObject(activity, 2, new IdentityHashMap<>());
        if (mac != null) {
            MLog.event("mac.resolved", "source", "activity_fields");
            return mac;
        }

        Context context = activity instanceof Context ? (Context) activity : resolveContext(fragment);
        mac = resolveMacFromA2dp(context);
        if (mac != null) {
            MLog.event("mac.resolved", "source", "active_a2dp");
            return mac;
        }

        MLog.event("mac.unresolved",
                "fragment", fragment == null ? "null" : fragment.getClass().getName(),
                "activity", activity == null ? "null" : activity.getClass().getName());
        return null;
    }

    @SuppressWarnings("deprecation")
    private static String macFromIntent(Intent intent) {
        if (intent == null) return null;
        String mac = normalizeMacCandidate(intent.getStringExtra("device_mac_info"));
        if (mac != null) return mac;
        mac = normalizeMacCandidate(intent.getDataString());
        if (mac != null) return mac;
        try {
            Object device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            mac = normalizeMacCandidate(device);
            if (mac != null) return mac;
        } catch (Throwable ignored) {
        }
        return findMacInObject(intent.getExtras(), 2, new IdentityHashMap<>());
    }

    private static String findMacInObject(
            Object value,
            int depth,
            IdentityHashMap<Object, Boolean> seen) {
        String mac = normalizeMacCandidate(value);
        if (mac != null) return mac;
        if (value == null || depth < 0 || seen.containsKey(value)) return null;
        if (isMacScanTerminal(value)) return null;
        seen.put(value, Boolean.TRUE);

        if (value instanceof Intent) {
            return macFromIntent((Intent) value);
        }
        if (value instanceof Bundle) {
            return findMacInBundle((Bundle) value, depth, seen);
        }
        if (value instanceof Map) {
            return findMacInMap((Map<?, ?>) value, depth, seen);
        }
        if (value instanceof Iterable) {
            return findMacInIterable((Iterable<?>) value, depth, seen);
        }

        Class<?> cls = value.getClass();
        while (cls != null && cls != Object.class) {
            if (shouldStopMacFieldScan(cls)) break;
            for (Field field : cls.getDeclaredFields()) {
                if (field.isSynthetic()
                        || field.getType().isPrimitive()
                        || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    mac = findMacInObject(field.get(value), depth - 1, seen);
                    if (mac != null) return mac;
                } catch (Throwable ignored) {
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    private static String findMacInBundle(
            Bundle bundle,
            int depth,
            IdentityHashMap<Object, Boolean> seen) {
        try {
            for (String key : bundle.keySet()) {
                String mac = normalizeMacCandidate(key);
                if (mac != null) return mac;
                try {
                    mac = findMacInObject(bundle.get(key), depth - 1, seen);
                    if (mac != null) return mac;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String findMacInMap(
            Map<?, ?> map,
            int depth,
            IdentityHashMap<Object, Boolean> seen) {
        int count = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (count++ > 64) break;
            String mac = findMacInObject(entry.getKey(), depth - 1, seen);
            if (mac != null) return mac;
            mac = findMacInObject(entry.getValue(), depth - 1, seen);
            if (mac != null) return mac;
        }
        return null;
    }

    private static String findMacInIterable(
            Iterable<?> values,
            int depth,
            IdentityHashMap<Object, Boolean> seen) {
        int count = 0;
        for (Object item : values) {
            if (count++ > 64) break;
            String mac = findMacInObject(item, depth - 1, seen);
            if (mac != null) return mac;
        }
        return null;
    }

    private static boolean isMacScanTerminal(Object value) {
        if ((value instanceof Context && !(value instanceof Activity))
                || value instanceof Handler
                || value instanceof ClassLoader
                || value instanceof Class
                || value.getClass().isArray()) {
            return true;
        }
        String name = value.getClass().getName();
        return name.startsWith("android.view.")
                || name.startsWith("android.widget.")
                || name.startsWith("android.graphics.")
                || name.startsWith("android.text.")
                || name.startsWith("android.content.res.")
                || name.startsWith("java.lang.ref.");
    }

    private static boolean shouldStopMacFieldScan(Class<?> cls) {
        String name = cls.getName();
        return name.startsWith("android.")
                || name.startsWith("androidx.")
                || name.startsWith("java.")
                || name.startsWith("kotlin.")
                || name.startsWith("dalvik.")
                || name.startsWith("libcore.")
                || name.startsWith("sun.");
    }

    private static String normalizeMacCandidate(Object value) {
        if (value == null) return null;
        if (value instanceof BluetoothDevice) {
            return normalizeMac(((BluetoothDevice) value).getAddress());
        }
        if (value instanceof CharSequence) {
            Matcher matcher = MAC_PATTERN.matcher(value.toString());
            return matcher.find() ? normalizeMac(matcher.group()) : null;
        }
        return null;
    }

    private static String normalizeMac(String mac) {
        if (mac == null) return null;
        String trimmed = mac.trim();
        if (!MAC_PATTERN.matcher(trimmed).matches()) return null;
        return trimmed.toUpperCase(Locale.ROOT);
    }

    @SuppressWarnings("deprecation")
    private static String resolveMacFromA2dp(Context context) {
        BluetoothAdapter adapter = null;
        try {
            if (context != null) {
                Object service = context.getSystemService(Context.BLUETOOTH_SERVICE);
                if (service instanceof BluetoothManager) {
                    adapter = ((BluetoothManager) service).getAdapter();
                }
            }
        } catch (Throwable ignored) {
        }
        if (adapter == null) {
            try {
                adapter = BluetoothAdapter.getDefaultAdapter();
            } catch (Throwable ignored) {
            }
        }
        if (adapter == null) return null;

        try {
            Method getActiveDevices = adapter.getClass().getMethod("getActiveDevices", int.class);
            Object devices = getActiveDevices.invoke(adapter, BluetoothProfile.A2DP);
            String mac = macFromDeviceCollection(devices);
            if (mac != null) return mac;
        } catch (Throwable ignored) {
        }

        try {
            if (context != null) {
                Object service = context.getSystemService(Context.BLUETOOTH_SERVICE);
                if (service instanceof BluetoothManager) {
                    List<BluetoothDevice> devices =
                            ((BluetoothManager) service).getConnectedDevices(BluetoothProfile.A2DP);
                    return macFromDeviceCollection(devices);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String macFromDeviceCollection(Object devices) {
        if (!(devices instanceof Iterable)) return null;
        for (Object item : (Iterable<?>) devices) {
            String mac = normalizeMacCandidate(item);
            if (mac != null) return mac;
        }
        return null;
    }

    private Class<?> loadHostClass(String name) {
        try {
            return Class.forName(name, false, classLoader);
        } catch (Throwable t) {
            return null;
        }
    }
}
