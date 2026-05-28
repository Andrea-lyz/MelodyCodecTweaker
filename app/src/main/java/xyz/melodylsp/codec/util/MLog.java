package xyz.melodylsp.codec.util;

import android.util.Log;

import io.github.libxposed.api.XposedInterface;
import xyz.melodylsp.codec.BuildConfig;

/**
 * Module-wide logging facade. Logs go to standard {@link Log} as well as the libxposed
 * framework log when an {@link XposedInterface} has been attached, so users can either tail
 * {@code logcat -s MelodyCodecLsp} or read the LSPosed module log viewer.
 */
public final class MLog {

    public static final String TAG = "MelodyCodecLsp";

    private static volatile XposedInterface xposed;
    private static volatile String hostVersion = "?";

    private MLog() {
    }

    public static void attach(XposedInterface iface) {
        xposed = iface;
    }

    /** Called by {@link xyz.melodylsp.codec.host.HostHookInstaller} once host package info is known. */
    public static void setHostVersion(String hostVersionName) {
        if (hostVersionName != null && !hostVersionName.isEmpty()) {
            hostVersion = hostVersionName;
        }
    }

    public static void d(String message) {
        emit(Log.DEBUG, message, null);
    }

    public static void d(String message, Throwable t) {
        emit(Log.DEBUG, message, t);
    }

    public static void i(String message) {
        emit(Log.INFO, message, null);
    }

    public static void w(String message) {
        emit(Log.WARN, message, null);
    }

    public static void w(String message, Throwable t) {
        emit(Log.WARN, message, t);
    }

    public static void e(String message) {
        emit(Log.ERROR, message, null);
    }

    public static void e(String message, Throwable t) {
        emit(Log.ERROR, message, t);
    }

    /** Structured event log; {@code kvPairs} is appended as {@code k=v} pairs separated by spaces. */
    public static void event(String name, Object... kvPairs) {
        StringBuilder sb = new StringBuilder("evt=").append(name);
        for (int i = 0; i + 1 < kvPairs.length; i += 2) {
            sb.append(' ').append(kvPairs[i]).append('=').append(kvPairs[i + 1]);
        }
        emit(Log.INFO, sb.toString(), null);
    }

    private static void emit(int priority, String message, Throwable t) {
        String prefixed = prefix() + message;
        if (t == null) {
            Log.println(priority, TAG, prefixed);
        } else {
            Log.println(priority, TAG, prefixed + '\n' + Log.getStackTraceString(t));
        }
        XposedInterface api = xposed;
        if (api != null) {
            try {
                if (t == null) api.log(priority, TAG, prefixed);
                else api.log(priority, TAG, prefixed, t);
            } catch (Throwable swallow) {
                // Logging never crashes the app.
            }
        }
    }

    private static String prefix() {
        return "[mod=" + BuildConfig.VERSION_NAME + " host=" + hostVersion + "] ";
    }
}
