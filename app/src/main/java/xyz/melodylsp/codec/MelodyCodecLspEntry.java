package xyz.melodylsp.codec;

import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import xyz.melodylsp.codec.host.HostHookInstaller;
import xyz.melodylsp.codec.leaudio.WirelessSettingsHookInstaller;
import xyz.melodylsp.codec.settings.SettingsHookInstaller;
import xyz.melodylsp.codec.system.SystemHookInstaller;
import xyz.melodylsp.codec.util.MLog;

/**
 * libxposed API 101 entry. The framework instantiates this class once per process generation;
 * we dispatch to host- or system-side installers based on the package name carried by
 * {@link XposedModuleInterface.PackageLoadedParam}.
 *
 * <p>The legacy {@code IXposedHookLoadPackage} interface and the {@code assets/xposed_init}
 * file are no longer used. Discovery happens through {@code META-INF/xposed/java_init.list}
 * and the per-API behaviour is declared in {@code META-INF/xposed/module.prop}.</p>
 */
public final class MelodyCodecLspEntry extends XposedModule {

    private static final String HOST_PKG = "com.oplus.melody";
    private static final String BT_PKG = "com.android.bluetooth";
    private static final String WIRELESS_SETTINGS_PKG = "com.oplus.wirelesssettings";
    private static final String SETTINGS_PKG = "com.android.settings";

    /** Single shared instance referenced by hooker callbacks via {@link #current()}. */
    private static volatile MelodyCodecLspEntry INSTANCE;

    public MelodyCodecLspEntry() {
        INSTANCE = this;
    }

    /** Returns the currently-attached module reference, or {@code null} before attach. */
    public static MelodyCodecLspEntry current() {
        return INSTANCE;
    }

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        MLog.attach(this);
        MLog.event("module.loaded", "process", param.getProcessName(),
                "framework", getFrameworkName(),
                "framework_version", getFrameworkVersionCode(),
                "api", getApiVersion());
    }

    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        MLog.attach(this);
        String pkg = param.getPackageName();
        if (HOST_PKG.equals(pkg)) {
            installHostScope(param);
        } else if (BT_PKG.equals(pkg)) {
            installSystemScope(param);
        } else if (WIRELESS_SETTINGS_PKG.equals(pkg)) {
            installWirelessSettingsScope(param);
        } else if (SETTINGS_PKG.equals(pkg)) {
            installSettingsScope(param);
        }
    }

    private void installHostScope(PackageLoadedParam param) {
        if (!moduleEnabled()) {
            log(Log.INFO, MLog.TAG, "module disabled by master switch; skipping host hooks");
            return;
        }
        MLog.event("scope.host.start", "process", param.getApplicationInfo().processName);
        new HostHookInstaller(this, param.getDefaultClassLoader()).install();
    }

    private void installSystemScope(PackageLoadedParam param) {
        if (!moduleEnabled()) {
            log(Log.INFO, MLog.TAG, "module disabled by master switch; skipping system hooks");
            return;
        }
        MLog.event("scope.system.start", "process", param.getApplicationInfo().processName);
        new SystemHookInstaller(this, param.getDefaultClassLoader(),
                param.getApplicationInfo().sourceDir).install();
    }

    private void installWirelessSettingsScope(PackageLoadedParam param) {
        if (!moduleEnabled()) {
            log(Log.INFO, MLog.TAG,
                    "module disabled by master switch; skipping wirelesssettings hooks");
            return;
        }
        String processName = param.getApplicationInfo().processName;
        MLog.event("scope.wirelesssettings.start", "process", processName);
        new WirelessSettingsHookInstaller(this, param.getDefaultClassLoader(), processName,
                param.getApplicationInfo().sourceDir)
                .install();
    }

    private void installSettingsScope(PackageLoadedParam param) {
        if (!moduleEnabled()) {
            log(Log.INFO, MLog.TAG, "module disabled by master switch; skipping settings hooks");
            return;
        }
        String processName = param.getApplicationInfo().processName;
        if (processName != null && processName.contains(":")) {
            MLog.event("scope.settings.skip_subprocess", "process", processName);
            return;
        }
        MLog.event("scope.settings.start", "process", processName);
        new SettingsHookInstaller(this).install();
    }

    /**
     * Reads the master enable flag through the libxposed remote preferences pipe, falling back
     * to {@code true} on any error so a flaky framework state never leaves the user without the
     * codec block (Requirement 12.2).
     */
    private boolean moduleEnabled() {
        try {
            SharedPreferences sp = getRemotePreferences("module_prefs");
            return sp.getBoolean("enabled", true);
        } catch (Throwable t) {
            log(Log.WARN, MLog.TAG, "moduleEnabled fallback to true: " + t.getMessage());
            return true;
        }
    }

    /** Convenience wrapper so callers don't need to import {@link XposedInterface} constants. */
    public void hookMethod(@NonNull java.lang.reflect.Executable origin,
                           @NonNull io.github.libxposed.api.XposedInterface.Hooker hooker) {
        hook(origin).intercept(hooker);
    }
}
