package xyz.melodylsp.codec.host;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Set;

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
     * The shared base class for every OPLUS Preference-Fragment in the host APK
     * ({@code DetailMainPreferenceFragment}, {@code MoreSettingFragment},
     * {@code HighAudioPreferenceFragment}, {@code OneSpaceListFragment}, &c.). The package /
     * class name {@code com.oplus.melody.ui.base.c} is preserved by R8 because the host hand-
     * rolls reflection against this exact FQN. Hooking its {@code onViewCreated} gives us a
     * single, stable entrypoint that fires for every such fragment.
     */
    private static final String CLASS_BASE_PREFERENCE_FRAGMENT = "com.oplus.melody.ui.base.c";

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
    private static final String KEY_CODEC_QUALITY = "melody_codec_lsp_quality";
    private static final String KEY_NOISE_SWITCH = "pref_noise_switch";
    private static final String KEY_NOISE_SWITCH_CATEGORY = "pref_noise_switch_category";
    private static final String KEY_MORE_SETTING = "pref_more_setting";
    private static final String KEY_FOOTER = "footer_preference";

    private final MelodyCodecLspEntry module;
    private final ClassLoader classLoader;
    private final Set<Object> attachedScreens =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private CodecController controller;

    public HostHookInstaller(MelodyCodecLspEntry module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
    }

    public void install() {
        hookApplicationOnCreate();
        hookHighAudio();
        hookBasePreferenceFragment();
        hookOneSpace();
        hookOneSpaceActivity();
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
        controller = new CodecController(app, reflect, bridge, prefs);

        MLog.event("controller.ready", "version", hostVersion);
    }

    /**
     * Hook the shared OPLUS preference-fragment base class. Every OPLUS PreferenceFragment in
     * the host APK extends {@code com.oplus.melody.ui.base.c}; hooking its {@code onViewCreated}
     * gives us a single firing point covering DetailMainPreferenceFragment,
     * MoreSettingFragment, HighAudioPreferenceFragment, OneSpaceListFragment, and any other
     * subclass. We then dispatch on the PreferenceScreen's contents to figure out which
     * surface we're on (by looking for HiQualityAudioItem / pref_noise_menu_category / &c.).
     */
    private void hookBasePreferenceFragment() {
        Class<?> baseCls = loadHostClass(CLASS_BASE_PREFERENCE_FRAGMENT);
        if (baseCls == null) {
            MLog.w("Base preference fragment class not found");
            return;
        }
        Method onViewCreated = findOnViewCreated(baseCls);
        if (onViewCreated == null) {
            MLog.w("Base preference fragment onViewCreated(View,Bundle) not found");
            return;
        }
        module.hook(onViewCreated).intercept(chain -> {
            Object result = chain.proceed();
            Object fragment = chain.getThisObject();
            // PreferenceScreen items often arrive asynchronously after onViewCreated; retry a
            // few times spaced 800 ms apart to catch the moment the anchors land.
            scheduleSurfaceDispatch(fragment, /* attempt= */ 0);
            return result;
        });
    }

    private void scheduleSurfaceDispatch(Object fragment, int attempt) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (controller == null) return;
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
                MLog.w("surface anchor not found after retries; class=" + fragment.getClass().getName());
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
                || PrefRef.findPreference(screen, KEY_CODEC_QUALITY) != null;
    }

    /**
     * Decide which surface the fragment represents by inspecting the keys that exist on its
     * PreferenceScreen, then route to the matching insertion routine.
     */
    private boolean dispatchSurface(Object fragment) {
        Object screen = PrefRef.getPreferenceScreen(fragment);
        if (screen == null) return false;

        if (hasCodecMarker(screen)) {
            // Already injected on this screen.
            attachedScreens.add(screen);
            return true;
        }

        if (PrefRef.findPreference(screen, KEY_HIRES_ITEM) != null) {
            return injectAfterHires(fragment, screen);
        }

        if (PrefRef.findPreference(screen, MelodyResIds.KEY_NOISE_MENU_CATEGORY) != null
                || PrefRef.findPreference(screen, MelodyResIds.KEY_MORE_SETTING_CATEGORY) != null
                || PrefRef.findPreference(screen, KEY_NOISE_SWITCH) != null
                || PrefRef.findPreference(screen, KEY_MORE_SETTING) != null) {
            return injectIntoOneSpace(fragment, screen);
        }

        // DetailMain fallback anchors (TODO A3) for earphones without a Hi-Res switch:
        // anchor after the Equalizer row, else after the first visible PreferenceCategory.
        if (PrefRef.findPreference(screen, KEY_EQUALIZER_ITEM) != null) {
            return injectAfterAnchorKey(fragment, screen, KEY_EQUALIZER_ITEM);
        }
        Object firstCategory = findFirstVisibleCategory(screen);
        if (firstCategory != null) {
            return injectAfterPreference(fragment, screen, firstCategory);
        }

        return false;
    }

    /**
     * Find the first visible {@code PreferenceCategory}-shaped child on the screen (TODO A3
     * last-resort anchor). Detected by runtime class-name containing {@code PreferenceCategory}
     * so it works against the host's R8-minified COUI category subclasses.
     */
    private static Object findFirstVisibleCategory(Object screen) {
        int count = PrefRef.getPreferenceCount(screen);
        for (int i = 0; i < count; i++) {
            Object pref = PrefRef.getPreference(screen, i);
            if (pref == null) continue;
            if (!PrefRef.isVisible(pref)) continue;
            String name = pref.getClass().getName();
            if (name.contains("PreferenceCategory")) {
                return pref;
            }
        }
        return null;
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
        shiftPreferenceOrders(parent, targetOrder, +4);

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
        shiftPreferenceOrders(parent, targetOrder, +4);

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
                /* wrapInCategory= */ true, /* includeRemember= */ false);
        if (prefs == null) return false;
        setCategoryTopMarginZero(prefs.category);
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
        Class<?> fragCls = loadHostClass(CLASS_ONE_SPACE_FRAGMENT);
        if (fragCls == null) return;
        Method onViewCreated = findOnViewCreated(fragCls);
        if (onViewCreated == null) return;
        module.hook(onViewCreated).intercept(chain -> {
            Object result = chain.proceed();
            Object fragment = chain.getThisObject();
            scheduleSurfaceDispatch(fragment, /* attempt= */ 0);
            return result;
        });
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

    /** True when a PreferenceScreen carries any OneSpace-specific marker key. */
    private static boolean isOneSpaceScreen(Object screen) {
        return PrefRef.findPreference(screen, MelodyResIds.KEY_NOISE_MENU_CATEGORY) != null
                || PrefRef.findPreference(screen, MelodyResIds.KEY_MORE_SETTING_CATEGORY) != null
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
        Object list = invokeNoArg(fragmentManager, "getFragments");
        if (!(list instanceof java.util.List)) return;
        for (Object fragment : (java.util.List<Object>) list) {
            if (fragment == null) continue;
            out.add(fragment);
            Object childFm = invokeNoArg(fragment, "getChildFragmentManager");
            if (childFm != null && childFm != fragmentManager) {
                collectFromManager(childFm, out, depth + 1);
            }
        }
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
     * Resolves the MAC address that the OneSpace / HighAudio fragment is bound to.
     *
     * <p>The host stores the MAC in the parent Activity's Intent under
     * {@code device_mac_info} — a plain string literal that survives R8 minification. Field
     * names are R8-hashed and change between builds, so we no longer try to read them.</p>
     */
    private static String resolveMacFromActivityIntent(Object fragment) {
        try {
            Method getActivity = fragment.getClass().getMethod("getActivity");
            Object activity = getActivity.invoke(fragment);
            if (activity == null) return null;
            Method getIntent = activity.getClass().getMethod("getIntent");
            Object intent = getIntent.invoke(activity);
            if (intent == null) return null;
            Method getStringExtra = intent.getClass().getMethod("getStringExtra", String.class);
            Object value = getStringExtra.invoke(intent, "device_mac_info");
            if (value == null) return null;
            String mac = value.toString();
            return mac.isEmpty() ? null : mac;
        } catch (Throwable t) {
            MLog.w("resolveMacFromActivityIntent failed", t);
            return null;
        }
    }

    private Class<?> loadHostClass(String name) {
        try {
            return Class.forName(name, false, classLoader);
        } catch (Throwable t) {
            return null;
        }
    }
}
