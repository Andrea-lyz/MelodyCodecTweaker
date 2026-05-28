package xyz.melodylsp.codec.host;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Bundle;

import java.lang.reflect.Method;

import xyz.melodylsp.codec.MelodyCodecLspEntry;
import xyz.melodylsp.codec.bt.BluetoothCodecReflect;
import xyz.melodylsp.codec.storage.PreferenceStore;
import xyz.melodylsp.codec.util.MLog;

/**
 * Installs all host-side hooks via the modern libxposed API. Each hook lambda is wrapped in
 * try / catch so that a single failure cannot cascade into a host crash (Requirement 9.5 /
 * Property 4 / 6).
 *
 * <p>None of the {@code androidx.preference} types are referenced through compile-time
 * symbols. The host App ships androidx.preference but the names are minified, so referencing
 * them at compile time triggers {@link NoClassDefFoundError} at runtime. Everything goes
 * through {@link PrefRef} reflection.</p>
 */
public final class HostHookInstaller {

    private static final String CLASS_HIGH_AUDIO =
            "com.oplus.melody.ui.component.detail.highaudio.HighAudioPreferenceFragment";
    private static final String CLASS_DETAIL_MAIN_FRAGMENT = "G8.a";
    private static final String CLASS_ONE_SPACE_FRAGMENT = "com.oplus.melody.onespace.d";

    private static final String KEY_HIRES_ITEM = "HiQualityAudioItem";

    private final MelodyCodecLspEntry module;
    private final ClassLoader classLoader;
    private CodecController controller;

    public HostHookInstaller(MelodyCodecLspEntry module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
    }

    public void install() {
        hookApplicationOnCreate();
        hookHighAudio();
        hookDetailMain();
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

    private void hookDetailMain() {
        Class<?> fragCls = loadHostClass(CLASS_DETAIL_MAIN_FRAGMENT);
        if (fragCls == null) {
            MLog.w("MoreSettingFragment class not found");
            return;
        }
        Method onViewCreated = findOnViewCreated(fragCls);
        if (onViewCreated == null) {
            MLog.w("MoreSettingFragment.onViewCreated(View,Bundle) not found");
            return;
        }
        module.hook(onViewCreated).intercept(chain -> {
            Object result = chain.proceed();
            Object fragment = chain.getThisObject();
            // The detail main panel populates its PreferenceScreen asynchronously through
            // MoreSettingViewModel.f17885h (a LiveData of List<Class<?>>). Items appear bit by
            // bit as the WhitelistConfig stream emits, so the Hi-Res item we want to anchor
            // below may not be present at onViewCreated. Schedule a few retries spaced 800 ms
            // apart to catch the moment the Preference for HiQualityAudioItem lands.
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    () -> tryInsertIntoDetailMain(fragment, /* attempt= */ 0), 600L);
            return result;
        });
    }

    private void tryInsertIntoDetailMain(Object fragment, int attempt) {
        try {
            if (insertIntoDetailMain(fragment)) {
                return;
            }
        } catch (Throwable t) {
            MLog.e("DetailMain insertion failed", t);
            return;
        }
        if (attempt < 5) {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    () -> tryInsertIntoDetailMain(fragment, attempt + 1), 800L);
        } else {
            MLog.w("DetailMain insertion gave up after 5 retries (Hi-Res item never appeared)");
        }
    }

    /**
     * Insert the codec block immediately after the {@code HiQualityAudioItem} Preference inside
     * MoreSettingFragment. The item appears asynchronously, so this method is retried; it
     * returns {@code true} when the anchor is found and injection succeeds.
     */
    private boolean insertIntoDetailMain(Object fragment) {
        if (controller == null) return false;
        Context context = resolveContext(fragment);
        if (context == null) return false;
        Object screen = PrefRef.getPreferenceScreen(fragment);
        if (screen == null) return false;
        // The PreferenceCategory wrapping the Hi-Res item uses the simple class name as its
        // key (see G8.a$C0036a.invoke -> cOUIPreferenceCategory.setKey(cls.getSimpleName())).
        Object hiresCategory = PrefRef.findPreference(screen, KEY_HIRES_ITEM);
        if (hiresCategory == null) return false;
        // Avoid re-inserting if we already attached.
        if (PrefRef.findPreference(screen, "melody_codec_lsp_category") != null) {
            return true;
        }
        int order = PrefRef.getOrder(hiresCategory) + 1;
        String mac = resolveMacFromActivityIntent(fragment);
        if (mac == null) {
            MLog.w("DetailMain mac unresolved; skip");
            return false;
        }
        CodecPreferences prefs = CodecBlockBuilder.buildAndInsert(context, screen, order);
        if (prefs == null) return false;
        controller.attach(mac, prefs, fragment);
        MLog.event("detailmain.injected", "mac_len", mac.length(), "order", order);
        return true;
    }

    private void hookHighAudio() {
        Class<?> fragCls = loadHostClass(CLASS_HIGH_AUDIO);
        if (fragCls == null) {
            MLog.w("HighAudioPreferenceFragment class not found");
            return;
        }
        // Hook onViewCreated(View, Bundle) which runs after onCreate -> onCreatePreferences ->
        // onCreateView, so the PreferenceScreen is guaranteed to be attached. We deliberately
        // avoid hooking onCreate / onCreatePreferences because both their method names can be
        // R8-renamed inside the host APK.
        Method onViewCreated = findOnViewCreated(fragCls);
        if (onViewCreated == null) {
            MLog.w("HighAudioPreferenceFragment.onViewCreated(View,Bundle) not found");
            return;
        }
        module.hook(onViewCreated).intercept(chain -> {
            Object result = chain.proceed();
            try {
                insertIntoHighAudio(chain.getThisObject());
            } catch (Throwable t) {
                MLog.e("HighAudio insertion failed", t);
            }
            return result;
        });
    }

    private void hookOneSpace() {
        Class<?> fragCls = loadHostClass(CLASS_ONE_SPACE_FRAGMENT);
        if (fragCls == null) {
            MLog.w("OneSpaceListFragment class not found");
            return;
        }
        Method onViewCreated = findOnViewCreated(fragCls);
        if (onViewCreated == null) {
            MLog.w("OneSpaceListFragment.onViewCreated(View,Bundle) not found");
            return;
        }
        module.hook(onViewCreated).intercept(chain -> {
            Object result = chain.proceed();
            try {
                insertIntoOneSpace(chain.getThisObject());
            } catch (Throwable t) {
                MLog.e("OneSpace insertion failed", t);
            }
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

    private void insertIntoHighAudio(Object fragment) {
        if (controller == null) {
            MLog.w("HighAudio insertion skipped: controller not ready");
            return;
        }
        Context context = resolveContext(fragment);
        if (context == null) {
            MLog.w("HighAudio insertion skipped: no context");
            return;
        }
        Object screen = PrefRef.getPreferenceScreen(fragment);
        if (screen == null) {
            MLog.w("HighAudio screen is null");
            return;
        }
        Object hires = PrefRef.findPreference(screen, MelodyResIds.KEY_HIRES_SWITCH_CATEGORY);
        int order = hires != null ? PrefRef.getOrder(hires) + 1 : PrefRef.getPreferenceCount(screen);
        String mac = resolveHighAudioMac(fragment);
        if (mac == null) {
            MLog.w("HighAudio mac unresolved; skip");
            return;
        }
        CodecPreferences prefs = CodecBlockBuilder.buildAndInsert(context, screen, order);
        if (prefs == null) return;
        controller.attach(mac, prefs, fragment);
        MLog.event("highaudio.injected", "mac_len", mac.length(), "order", order);
    }

    private void insertIntoOneSpace(Object fragment) {
        if (controller == null) {
            MLog.w("OneSpace insertion skipped: controller not ready");
            return;
        }
        Context context = resolveContext(fragment);
        if (context == null) {
            MLog.w("OneSpace insertion skipped: no context");
            return;
        }
        Object screen = PrefRef.getPreferenceScreen(fragment);
        if (screen == null) {
            MLog.w("OneSpace screen is null");
            return;
        }
        Object noiseMenu = PrefRef.findPreference(screen, MelodyResIds.KEY_NOISE_MENU_CATEGORY);
        Object moreSetting = PrefRef.findPreference(screen, MelodyResIds.KEY_MORE_SETTING_CATEGORY);

        int targetOrder;
        if (noiseMenu != null && moreSetting != null) {
            int low = Math.min(PrefRef.getOrder(noiseMenu), PrefRef.getOrder(moreSetting));
            int high = Math.max(PrefRef.getOrder(noiseMenu), PrefRef.getOrder(moreSetting));
            targetOrder = (low + high) / 2;
            if (targetOrder == low) targetOrder = low + 1;
        } else if (moreSetting != null) {
            targetOrder = Math.max(0, PrefRef.getOrder(moreSetting) - 1);
        } else {
            targetOrder = PrefRef.getPreferenceCount(screen);
        }
        if (moreSetting != null && targetOrder >= PrefRef.getOrder(moreSetting)) {
            PrefRef.setOrder(moreSetting, targetOrder + 1);
        }

        String mac = resolveOneSpaceMac(fragment);
        if (mac == null) {
            MLog.w("OneSpace mac unresolved; skip");
            return;
        }
        CodecPreferences prefs = CodecBlockBuilder.buildAndInsert(context, screen, targetOrder);
        if (prefs == null) return;
        controller.attach(mac, prefs, fragment);
        MLog.event("onespace.injected", "mac_len", mac.length(), "order", targetOrder);
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
