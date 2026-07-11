package xyz.melodylsp.codec.diag;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import java.util.HashMap;
import java.util.Map;

import xyz.melodylsp.codec.bridge.CodecIpc;
import xyz.melodylsp.codec.leaudio.LeAudioIpc;
import xyz.melodylsp.codec.util.TrustedBroadcasts;

public final class DiagnosticEventReceiver extends BroadcastReceiver {

    private static final String SETTINGS_PKG = "com.android.settings";
    private static final long RATE_WINDOW_MS = 60_000L;
    private static final int MAX_EVENTS_PER_WINDOW = 180;
    private static final Map<Integer, RateWindow> RATE_WINDOWS = new HashMap<>();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !DiagnosticEvents.ACTION.equals(intent.getAction())) return;
        TrustedBroadcasts.SenderIdentity sender = TrustedBroadcasts.captureSender(this);
        String scope;
        int rateLimitUid;
        if (TrustedBroadcasts.supportsSenderIdentity()) {
            if (!TrustedBroadcasts.isTrustedSender(
                    context,
                    sender,
                    CodecIpc.MELODY_PKG,
                    CodecIpc.BLUETOOTH_PKG,
                    LeAudioIpc.WIRELESS_SETTINGS_PKG,
                    SETTINGS_PKG)) {
                return;
            }
            scope = scopeForPackage(sender.packageName);
            rateLimitUid = sender.uid;
        } else {
            // The manifest-level OPLUS_COMPONENT_SAFE signature permission is the legacy sender
            // gate. Exact package attribution is not exposed before Android 14, so do not trust
            // the supplied scope label and place all legacy traffic in one conservative bucket.
            scope = "legacy_trusted";
            rateLimitUid = -1;
        }
        if (!allowEvent(rateLimitUid, SystemClock.elapsedRealtime())) return;
        DiagnosticEvents.record(context, intent, scope);
    }

    private static String scopeForPackage(String packageName) {
        if (CodecIpc.MELODY_PKG.equals(packageName)) return "melody";
        if (CodecIpc.BLUETOOTH_PKG.equals(packageName)) return "bluetooth";
        if (LeAudioIpc.WIRELESS_SETTINGS_PKG.equals(packageName)) return "wirelesssettings";
        if (SETTINGS_PKG.equals(packageName)) return "settings";
        return "unknown";
    }

    static synchronized boolean allowEvent(int uid, long elapsedMs) {
        RateWindow window = RATE_WINDOWS.get(uid);
        if (window == null
                || elapsedMs < window.startedAtMs
                || elapsedMs - window.startedAtMs >= RATE_WINDOW_MS) {
            RATE_WINDOWS.put(uid, new RateWindow(elapsedMs, 1));
            return true;
        }
        if (window.count >= MAX_EVENTS_PER_WINDOW) return false;
        window.count++;
        return true;
    }

    static synchronized void resetRateLimitsForTest() {
        RATE_WINDOWS.clear();
    }

    private static final class RateWindow {
        final long startedAtMs;
        int count;

        RateWindow(long startedAtMs, int count) {
            this.startedAtMs = startedAtMs;
            this.count = count;
        }
    }
}
