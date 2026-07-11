package xyz.melodylsp.codec.util;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import io.github.libxposed.api.XposedInterface;
import xyz.melodylsp.codec.BuildConfig;
import xyz.melodylsp.codec.diag.DiagnosticEvents;

/**
 * Module-wide logging facade. Logs go to standard {@link Log} as well as the libxposed
 * framework log when an {@link XposedInterface} has been attached, so users can either tail
 * {@code logcat -s MelodyCodecLsp} or read the LSPosed module log viewer.
 */
public final class MLog {

    public static final String TAG = "MelodyCodecLsp";

    private static volatile XposedInterface xposed;
    private static volatile String hostVersion = "?";
    private static volatile Context diagnosticContext;
    private static volatile String diagnosticScope = "unknown";
    private static final Object PENDING_LOCK = new Object();
    private static final List<PendingDiagnostic> pendingDiagnostics = new ArrayList<>();
    private static final int MAX_PENDING_DIAGNOSTICS = 128;
    private static final Pattern BLUETOOTH_ADDRESS = Pattern.compile(
            "(?i)([0-9a-f]{2}):(?:[0-9a-f]{2}:){4}([0-9a-f]{2})");

    private MLog() {
    }

    public static void attach(XposedInterface iface) {
        xposed = iface;
    }

    public static void setDiagnosticContext(Context context, String scope) {
        Context appContext = null;
        if (context != null) {
            appContext = context.getApplicationContext();
            diagnosticContext = appContext;
        }
        if (scope != null && !scope.isEmpty()) {
            diagnosticScope = scope;
        }
        flushPendingDiagnostics(appContext, diagnosticScope);
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

    /** Returns a single-token throwable summary suitable for structured event values. */
    public static String compactThrowable(Throwable t) {
        if (t == null) return "unknown";
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        String value = root.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ':' + message);
        value = value.replaceAll("\\s+", "_");
        return value.length() <= 96 ? value : value.substring(0, 96);
    }

    private static void emit(int priority, String message, Throwable t) {
        long time = System.currentTimeMillis();
        String prefixed = prefix() + redactBluetoothAddresses(message);
        String safeStack = t != null
                ? redactBluetoothAddresses(Log.getStackTraceString(t))
                : null;
        if (t == null) {
            Log.println(priority, TAG, prefixed);
        } else {
            Log.println(priority, TAG, prefixed + '\n' + safeStack);
        }
        XposedInterface api = xposed;
        if (api != null) {
            try {
                if (t == null) api.log(priority, TAG, prefixed);
                else api.log(priority, TAG, prefixed + '\n' + safeStack);
            } catch (Throwable swallow) {
                // Logging never crashes the app.
            }
        }
        String diagnosticMessage = t == null ? prefixed : prefixed + '\n' + safeStack;
        Context context = diagnosticContext;
        if (context != null) {
            DiagnosticEvents.send(context, diagnosticScope, priority, diagnosticMessage, time);
        } else {
            enqueuePendingDiagnostic(priority, diagnosticMessage, time);
        }
    }

    private static String prefix() {
        return "[mod=" + BuildConfig.VERSION_NAME + " host=" + hostVersion + "] ";
    }

    static String redactBluetoothAddresses(String value) {
        if (value == null || value.isEmpty()) return value;
        return BLUETOOTH_ADDRESS.matcher(value).replaceAll("$1:**:**:**:**:$2");
    }

    private static void enqueuePendingDiagnostic(int priority, String message, long time) {
        if (priority < Log.INFO) return;
        synchronized (PENDING_LOCK) {
            pendingDiagnostics.add(new PendingDiagnostic(priority, message, time));
            while (pendingDiagnostics.size() > MAX_PENDING_DIAGNOSTICS) {
                pendingDiagnostics.remove(0);
            }
        }
    }

    private static void flushPendingDiagnostics(Context context, String scope) {
        if (context == null) return;
        List<PendingDiagnostic> copy;
        synchronized (PENDING_LOCK) {
            if (pendingDiagnostics.isEmpty()) return;
            copy = new ArrayList<>(pendingDiagnostics);
            pendingDiagnostics.clear();
        }
        for (PendingDiagnostic pending : copy) {
            DiagnosticEvents.send(context, scope, pending.priority, pending.message, pending.time);
        }
    }

    private static final class PendingDiagnostic {
        final int priority;
        final String message;
        final long time;

        PendingDiagnostic(int priority, String message, long time) {
            this.priority = priority;
            this.message = message;
            this.time = time;
        }
    }
}
