package xyz.melodylsp.codec.host;

import android.app.Application;
import android.content.pm.PackageInfo;
import android.os.Bundle;

import androidx.lifecycle.LifecycleOwner;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import io.github.libxposed.api.XposedInterface;
import xyz.melodylsp.codec.MelodyCodecLspEntry;
import xyz.melodylsp.codec.bt.BluetoothCodecReflect;
import xyz.melodylsp.codec.storage.PreferenceStore;
import xyz.melodylsp.codec.util.MLog;

/**
 * Installs all host-side hooks via the modern libxposed {@link XposedInterface}. Each hook
 * lambda is wrapped in try / catch so that a single failure cannot cascade into a host crash
 * (Requirement 9.5 / Property 4 / 6).
 */
public final class HostHookInstaller {

    private static final String CLASS_HIGH_AUDIO =
            "com.oplus.melody.ui.component.detail.highaudio.HighAudioPreferenceFragment";
    private static final String CLASS_ONE_SPACE_FRAGMENT = "com.oplus.melody.onespace.d";
    private static final String CLASS_HIGH_AUDIO_DETAIL_FRAGMENT = "y8.g";
    private static final String FIELD_HIGH_AUDIO_MAC = "f27613b";
    private static final String FIELD_ONE_SPACE_MAC = "f17198C";

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

    private void hookHighAudio() {
        Class<?> fragCls = loadHostClass(CLASS_HIGH_AUDIO);
        if (fragCls == null) {
            MLog.w("HighAudioPreferenceFragment class not found");
            return;
        }
        Method onCreate = findDeclaredMethod(fragCls, "onCreate", Bundle.class);
        if (onCreate == null) {
            MLog.w("HighAudioPreferenceFragment.onCreate(Bundle) not found");
            return;
        }
        module.hook(onCreate).intercept(chain -> {
            Object result = chain.proceed();
            try {
                insertIntoHighAudio((PreferenceFragmentCompat) chain.getThisObject());
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
        // OneSpaceListFragment.r() corresponds to onCreatePreferences. Hook every declared
        // method named "r" with no parameters; Kotlin synthetics also use the same name shape
        // but they take parameters, so this filter avoids collisions.
        for (Method m : fragCls.getDeclaredMethods()) {
            if (!"r".equals(m.getName())) continue;
            if (m.getParameterCount() != 0) continue;
            if (Modifier.isStatic(m.getModifiers())) continue;
            module.hook(m).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    insertIntoOneSpace((PreferenceFragmentCompat) chain.getThisObject());
                } catch (Throwable t) {
                    MLog.e("OneSpace insertion failed", t);
                }
                return result;
            });
        }
    }

    private void insertIntoHighAudio(PreferenceFragmentCompat fragment) {
        if (controller == null) {
            MLog.w("HighAudio insertion skipped: controller not ready");
            return;
        }
        PreferenceScreen screen = fragment.getPreferenceScreen();
        if (screen == null) {
            MLog.w("HighAudio screen is null");
            return;
        }
        Preference hires = screen.findPreference(MelodyResIds.KEY_HIRES_SWITCH_CATEGORY);
        int order = hires != null ? hires.getOrder() + 1 : screen.getPreferenceCount();
        String mac = resolveHighAudioMac(fragment);
        if (mac == null) {
            MLog.w("HighAudio mac unresolved; skip");
            return;
        }
        CodecPreferences prefs =
                CodecBlockBuilder.buildAndInsert(fragment.requireContext(), screen, order);
        controller.attach(mac, prefs, (LifecycleOwner) fragment);
        MLog.event("highaudio.injected", "mac_len", mac.length(), "order", order);
    }

    private void insertIntoOneSpace(PreferenceFragmentCompat fragment) {
        if (controller == null) {
            MLog.w("OneSpace insertion skipped: controller not ready");
            return;
        }
        PreferenceScreen screen = fragment.getPreferenceScreen();
        if (screen == null) {
            MLog.w("OneSpace screen is null");
            return;
        }
        Preference noiseMenu = screen.findPreference(MelodyResIds.KEY_NOISE_MENU_CATEGORY);
        Preference moreSetting = screen.findPreference(MelodyResIds.KEY_MORE_SETTING_CATEGORY);

        int targetOrder;
        if (noiseMenu != null && moreSetting != null) {
            int low = Math.min(noiseMenu.getOrder(), moreSetting.getOrder());
            int high = Math.max(noiseMenu.getOrder(), moreSetting.getOrder());
            targetOrder = (low + high) / 2;
            if (targetOrder == low) targetOrder = low + 1;
        } else if (moreSetting != null) {
            targetOrder = Math.max(0, moreSetting.getOrder() - 1);
        } else {
            targetOrder = screen.getPreferenceCount();
        }
        if (moreSetting instanceof PreferenceCategory && targetOrder >= moreSetting.getOrder()) {
            moreSetting.setOrder(targetOrder + 1);
        }

        String mac = resolveOneSpaceMac(fragment);
        if (mac == null) {
            MLog.w("OneSpace mac unresolved; skip");
            return;
        }
        CodecPreferences prefs =
                CodecBlockBuilder.buildAndInsert(fragment.requireContext(), screen, targetOrder);
        controller.attach(mac, prefs, (LifecycleOwner) fragment);
        MLog.event("onespace.injected", "mac_len", mac.length(), "order", targetOrder);
    }

    private static String resolveHighAudioMac(Object fragment) {
        try {
            // The host stores the MAC on the parent fragment (HighAudioDetailFragment.f27613b).
            Method getParent = fragment.getClass().getMethod("getParentFragment");
            Object parent = getParent.invoke(fragment);
            if (parent == null) return null;
            return readField(parent, FIELD_HIGH_AUDIO_MAC);
        } catch (Throwable t) {
            MLog.w("resolveHighAudioMac failed", t);
            return null;
        }
    }

    private static String resolveOneSpaceMac(Object fragment) {
        try {
            return readField(fragment, FIELD_ONE_SPACE_MAC);
        } catch (Throwable t) {
            MLog.w("resolveOneSpaceMac failed", t);
            return null;
        }
    }

    private static String readField(Object target, String fieldName) throws ReflectiveOperationException {
        Class<?> cls = target.getClass();
        while (cls != null && cls != Object.class) {
            try {
                Field f = cls.getDeclaredField(fieldName);
                f.setAccessible(true);
                Object value = f.get(target);
                return value != null ? value.toString() : null;
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private Class<?> loadHostClass(String name) {
        try {
            return Class.forName(name, false, classLoader);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findDeclaredMethod(Class<?> cls, String name, Class<?>... paramTypes) {
        try {
            Method m = cls.getDeclaredMethod(name, paramTypes);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
