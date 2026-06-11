package xyz.melodylsp.codec.settings;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.List;

import xyz.melodylsp.codec.MelodyCodecLspEntry;
import xyz.melodylsp.codec.util.MLog;

/**
 * Small compatibility hook for OPlus developer options.
 *
 * <p>ColorOS 16 developer options do not provide summary entries for the vendor LHDC quality
 * values, so Settings repeatedly logs harmless errors while the actual Bluetooth stack accepts
 * the codec state. This hook only suppresses those known log lines in {@code com.android.settings}
 * to keep diagnostics readable.</p>
 */
public final class SettingsHookInstaller {

    private static final String TAG_BASE_BLUETOOTH_DLG_PREF = "BaseBluetoothDlgPref";
    private static final String TAG_BT_EXT_CODEC_CTR = "BtExtCodecCtr";

    private final MelodyCodecLspEntry module;

    public SettingsHookInstaller(MelodyCodecLspEntry module) {
        this.module = module;
    }

    public void install() {
        hookLogError(String.class, String.class);
        hookLogError(String.class, String.class, Throwable.class);
        MLog.event("settings.log_filter.installed");
    }

    private void hookLogError(Class<?>... parameterTypes) {
        try {
            Method method = Log.class.getMethod("e", parameterTypes);
            module.hook(method).intercept(chain -> {
                List<?> args = chain.getArgs();
                String tag = stringArg(args, 0);
                String message = stringArg(args, 1);
                if (shouldSuppress(tag, message)) {
                    return 0;
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            MLog.w("Settings Log.e hook failed", t);
        }
    }

    private static String stringArg(List<?> args, int index) {
        if (args == null || index >= args.size()) return null;
        Object value = args.get(index);
        return value instanceof String ? (String) value : null;
    }

    private static boolean shouldSuppress(String tag, String message) {
        if (tag == null || message == null) return false;
        if (TAG_BASE_BLUETOOTH_DLG_PREF.equals(tag)) {
            return message.startsWith("Unable to get summary of ")
                    && message.contains(". Size is ")
                    && isKnownLhdcVendorValue(message);
        }
        return TAG_BT_EXT_CODEC_CTR.equals(tag)
                && message.contains("setupListPreference: List preference is null");
    }

    private static boolean isKnownLhdcVendorValue(String message) {
        return message.contains("31774")
                || message.contains("31776")
                || message.contains("31777")
                || message.contains("32774")
                || message.contains("32776")
                || message.contains("32777");
    }
}
