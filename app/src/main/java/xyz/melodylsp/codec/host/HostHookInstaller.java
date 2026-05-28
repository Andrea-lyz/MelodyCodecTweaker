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
    private static final String KEY_CODEC_HEADER = "melody_codec_lsp_header";
    private static final String KEY_CODEC_CATEGORY = "melody_codec_lsp_category";
    private static final String KEY_CODEC_QUALITY = "melody_codec_lsp_quality";
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
                || PrefRef.findPreference(screen, KEY_MORE_SETTING) != null) {
            return injectIntoOneSpace(fragment, screen);
        }

        return false;
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
        shiftPreferenceOrders(parent, targetOrder, +1);

        String mac = resolveMacFromActivityIntent(fragment);
        if (mac == null) {
            MLog.w("Hi-Res-anchored insertion: mac unresolved; skip");
            return false;
        }
        // DetailMain wants the wrapped category card with remember toggle.
        CodecPreferences prefs = CodecBlockBuilder.buildAndInsert(
                themedContext, parent, targetOrder,
                /* wrapInCategory= */ true, /* includeRemember= */ true);
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

        Object noiseMenu = PrefRef.findPreference(screen, MelodyResIds.KEY_NOISE_MENU_CATEGORY);
        Object moreSettingCategory = PrefRef.findPreference(
                screen, MelodyResIds.KEY_MORE_SETTING_CATEGORY);
        Object moreSettingRow = PrefRef.findPreference(screen, KEY_MORE_SETTING);
        if (moreSettingCategory == null && moreSettingRow != null) {
            Object parent = PrefRef.getParent(moreSettingRow);
            moreSettingCategory = parent != null && parent != screen ? parent : moreSettingRow;
        }
        Object footer = PrefRef.findPreference(screen, KEY_FOOTER);

        Object anchor = moreSettingCategory != null ? moreSettingCategory : footer;
        int targetOrder;
        if (anchor != null) {
            targetOrder = Math.max(0, PrefRef.getOrder(anchor));
        } else if (noiseMenu != null) {
            targetOrder = PrefRef.getOrder(noiseMenu) + 1;
        } else {
            targetOrder = PrefRef.getPreferenceCount(screen);
        }
        String mac = resolveMacFromActivityIntent(fragment);
        if (mac == null) {
            MLog.w("OneSpace mac unresolved; skip");
            return false;
        }
        shiftPreferenceOrders(screen, targetOrder, +3);
        // OneSpace is for instant switching only: use top-level rows and skip the remember
        // toggle. Persistence is owned by DetailMain's panel.
        CodecPreferences prefs = CodecBlockBuilder.buildAndInsert(
                themedContext, screen, targetOrder,
                /* wrapInCategory= */ false, /* includeRemember= */ false);
        if (prefs == null) return false;
        controller.attach(mac, prefs, fragment);
        attachedScreens.add(screen);
        MLog.event("onespace.injected", "mac_len", mac.length(), "order", targetOrder);
        return true;
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

    private static String resolveHighAudioMac(Object fragment) {
        // First try the parent fragment's intent (HighAudioPreferenceFragment is hosted by
        // HighAudioDetailFragment). Both share the same activity intent so the result is the
        // same as querying the fragment directly, but the parent path matches the host's own
        // MAC plumbing.
        try {
            Method getParent = fragment.getClass().getMethod("getParentFragment");
            Object parent = getParent.invoke(fragment);
            if (parent != null) {
                String mac = resolveMacFromActivityIntent(parent);
                if (mac != null) return mac;
            }
        } catch (Throwable ignored) {
        }
        return resolveMacFromActivityIntent(fragment);
    }

    private static String resolveOneSpaceMac(Object fragment) {
        return resolveMacFromActivityIntent(fragment);
    }

    private Class<?> loadHostClass(String name) {
        try {
            return Class.forName(name, false, classLoader);
        } catch (Throwable t) {
            return null;
        }
    }
}
