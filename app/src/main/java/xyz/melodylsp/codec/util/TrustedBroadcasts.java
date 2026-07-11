package xyz.melodylsp.codec.util;

import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

/**
 * Android 14+ sender-identity support for the module's cross-process fallback broadcasts.
 *
 * <p>A compile-time token is useful for protocol versioning, but every installed application can
 * recover it from the APK. Privileged receivers must instead require the sender identity supplied
 * by the framework. Android 12 and 13 cannot provide that identity for ordinary broadcasts, so
 * callers must never use {@link #isTrustedSender} as a permissive legacy check.</p>
 */
public final class TrustedBroadcasts {

    public static final String PERMISSION_OPLUS_COMPONENT_SAFE =
            "oplus.permission.OPLUS_COMPONENT_SAFE";
    public static final String PERMISSION_BLUETOOTH_PRIVILEGED =
            "android.permission.BLUETOOTH_PRIVILEGED";
    private static final int UNKNOWN_UID = -1;

    private TrustedBroadcasts() {
    }

    /** True when share-identity broadcasts and receiver-side sender attribution are available. */
    public static boolean supportsSenderIdentity() {
        return Build.VERSION.SDK_INT >= 34;
    }

    /**
     * Send a targeted broadcast and opt into framework sender-identity sharing on Android 14+.
     *
     * <p>On Android 14+ this intentionally does not retry without the option: such a retry would
     * reach the receiver but be unverifiable, and is particularly dangerous for write requests.
     * Android 12/13 retain ordinary delivery so non-privileged query compatibility can remain.</p>
     */
    public static boolean send(Context context, Intent intent) {
        if (context == null || intent == null) return false;
        try {
            if (supportsSenderIdentity()) {
                Api34Impl.sendWithIdentity(context, intent);
            } else {
                context.sendBroadcast(intent);
            }
            return true;
        } catch (Throwable t) {
            // Do not route this through MLog: DiagnosticEvents itself uses this send path.
            Log.w("MelodyCodecLsp", "identity-sharing broadcast send failed", t);
            return false;
        }
    }

    /**
     * Register an exported dynamic receiver while requiring every sender to hold a trusted
     * signature permission. This is the authentication boundary on Android 12/13 and remains a
     * useful first gate before exact Android 14+ identity checks.
     */
    public static boolean registerExportedReceiver(
            Context context,
            BroadcastReceiver receiver,
            IntentFilter filter,
            String senderPermission,
            Handler scheduler) {
        if (context == null
                || receiver == null
                || filter == null
                || senderPermission == null
                || senderPermission.isEmpty()) {
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                Api33Impl.registerExported(
                        context, receiver, filter, senderPermission, scheduler);
            } else {
                context.registerReceiver(receiver, filter, senderPermission, scheduler);
            }
            return true;
        } catch (Throwable t) {
            MLog.w("permission-guarded receiver registration failed", t);
            return false;
        }
    }

    /** Register a same-app dynamic receiver, with a signature-permission fallback on API 31/32. */
    public static boolean registerNotExportedReceiver(
            Context context,
            BroadcastReceiver receiver,
            IntentFilter filter,
            String legacySenderPermission,
            Handler scheduler) {
        if (context == null || receiver == null || filter == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                Api33Impl.registerNotExported(context, receiver, filter, scheduler);
            } else {
                context.registerReceiver(
                        receiver, filter, legacySenderPermission, scheduler);
            }
            return true;
        } catch (Throwable t) {
            MLog.w("non-exported receiver registration failed", t);
            return false;
        }
    }

    /** Capture sender identity while {@link BroadcastReceiver#onReceive} is still active. */
    public static SenderIdentity captureSender(BroadcastReceiver receiver) {
        if (!supportsSenderIdentity() || receiver == null) {
            return SenderIdentity.unavailable();
        }
        try {
            return new SenderIdentity(
                    Api34Impl.getSentFromUid(receiver),
                    Api34Impl.getSentFromPackage(receiver),
                    true);
        } catch (Throwable t) {
            return SenderIdentity.unavailable();
        }
    }

    /**
     * Verify both the framework-reported package and the package set belonging to its actual UID.
     */
    public static boolean isTrustedSender(
            Context context, SenderIdentity sender, String... allowedPackages) {
        if (!supportsSenderIdentity()
                || context == null
                || sender == null
                || !sender.available
                || sender.uid < 0
                || sender.packageName == null
                || sender.packageName.isEmpty()) {
            return false;
        }
        String[] packagesForUid;
        try {
            PackageManager pm = context.getPackageManager();
            packagesForUid = pm != null ? pm.getPackagesForUid(sender.uid) : null;
        } catch (Throwable t) {
            return false;
        }
        return isAllowedIdentity(
                sender.uid, sender.packageName, packagesForUid, allowedPackages);
    }

    // Package-private pure helper for host-side unit tests.
    static boolean isAllowedIdentity(
            int uid,
            String sentFromPackage,
            String[] packagesForUid,
            String... allowedPackages) {
        if (uid < 0
                || sentFromPackage == null
                || sentFromPackage.isEmpty()
                || packagesForUid == null
                || packagesForUid.length == 0
                || allowedPackages == null
                || allowedPackages.length == 0) {
            return false;
        }
        boolean packageAllowed = false;
        for (String allowed : allowedPackages) {
            if (sentFromPackage.equals(allowed)) {
                packageAllowed = true;
                break;
            }
        }
        if (!packageAllowed) return false;
        for (String packageForUid : packagesForUid) {
            if (sentFromPackage.equals(packageForUid)) return true;
        }
        return false;
    }

    public static final class SenderIdentity {
        public final int uid;
        public final String packageName;
        public final boolean available;

        private SenderIdentity(int uid, String packageName, boolean available) {
            this.uid = uid;
            this.packageName = packageName;
            this.available = available;
        }

        private static SenderIdentity unavailable() {
            return new SenderIdentity(UNKNOWN_UID, null, false);
        }
    }

    @android.annotation.TargetApi(34)
    private static final class Api34Impl {
        private Api34Impl() {
        }

        static void sendWithIdentity(Context context, Intent intent) {
            BroadcastOptions options = BroadcastOptions.makeBasic();
            options.setShareIdentityEnabled(true);
            Bundle bundle = options.toBundle();
            context.sendBroadcast(intent, null, bundle);
        }

        static int getSentFromUid(BroadcastReceiver receiver) {
            return receiver.getSentFromUid();
        }

        static String getSentFromPackage(BroadcastReceiver receiver) {
            return receiver.getSentFromPackage();
        }
    }

    @android.annotation.TargetApi(33)
    private static final class Api33Impl {
        private Api33Impl() {
        }

        static void registerExported(
                Context context,
                BroadcastReceiver receiver,
                IntentFilter filter,
                String senderPermission,
                Handler scheduler) {
            context.registerReceiver(
                    receiver,
                    filter,
                    senderPermission,
                    scheduler,
                    Context.RECEIVER_EXPORTED);
        }

        static void registerNotExported(
                Context context,
                BroadcastReceiver receiver,
                IntentFilter filter,
                Handler scheduler) {
            context.registerReceiver(
                    receiver,
                    filter,
                    null,
                    scheduler,
                    Context.RECEIVER_NOT_EXPORTED);
        }
    }
}
