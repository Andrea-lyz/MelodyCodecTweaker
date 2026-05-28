package xyz.melodylsp.codec.host;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;

import xyz.melodylsp.codec.bridge.CodecRequest;
import xyz.melodylsp.codec.bridge.CodecSnapshot;
import xyz.melodylsp.codec.storage.PreferenceStore;
import xyz.melodylsp.codec.util.MLog;

/**
 * Watches A2DP connection events and replays the stored {@code <MAC>_specific1} /
 * {@code <MAC>_samplerate} when {@code Remember_Toggle=true}. Replays are skipped silently when
 * the persisted value is no longer in the freshly negotiated capabilities (Requirement 7.9).
 */
public final class ConnectionStateReplayer {

    private static final String ACTION_CONNECTION_STATE_CHANGED =
            "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED";
    private static final String EXTRA_DEVICE = "android.bluetooth.device.extra.DEVICE";
    private static final String EXTRA_STATE = "android.bluetooth.profile.extra.STATE";
    private static final int STATE_CONNECTED = 2;

    private static final long REPLAY_DELAY_MS = 1_500L;

    private final Context context;
    private final CodecBridgeClient bridge;
    private final PreferenceStore prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private BroadcastReceiver receiver;

    public ConnectionStateReplayer(
            Context context,
            CodecBridgeClient bridge,
            PreferenceStore prefs) {
        this.context = context.getApplicationContext();
        this.bridge = bridge;
        this.prefs = prefs;
    }

    public void start() {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (!ACTION_CONNECTION_STATE_CHANGED.equals(intent.getAction())) return;
                int state = intent.getIntExtra(EXTRA_STATE, -1);
                if (state != STATE_CONNECTED) return;
                Object device = intent.getParcelableExtra(EXTRA_DEVICE);
                if (device == null) return;
                String mac = device.toString();
                // BluetoothDevice.toString() returns the MAC verbatim.
                handleConnected(mac);
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_CONNECTION_STATE_CHANGED);
        try {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            context.registerReceiver(receiver, filter);
        }
        MLog.d("ConnectionStateReplayer started");
    }

    public void stop() {
        if (receiver == null) return;
        try {
            context.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
        }
        receiver = null;
    }

    private void handleConnected(String mac) {
        if (!prefs.isRemembered(mac)) {
            MLog.d("connected mac=" + mac + " remember=false; no replay");
            return;
        }
        PreferenceStore.RememberedValue value = prefs.readSnapshot(mac);
        if (value == null) {
            MLog.d("connected mac=" + mac + " remember=true but snapshot missing");
            return;
        }
        // Wait briefly so the platform finishes the initial negotiation, then replay.
        mainHandler.postDelayed(() -> replay(mac, value), REPLAY_DELAY_MS);
    }

    private void replay(String mac, PreferenceStore.RememberedValue stored) {
        CodecSnapshot live = bridge.getStatus(mac);
        if (live == null) {
            MLog.w("replay skipped, getStatus returned null mac=" + mac);
            return;
        }

        boolean specific1Selectable = arrayContains(live.selectableCodecSpecific1, stored.codecSpecific1);
        boolean sampleRateSelectable = (live.selectableSampleRateMask & stored.sampleRate) != 0;

        if (!specific1Selectable && !sampleRateSelectable) {
            MLog.event("replay.skip.both",
                    "mac", mac,
                    "stored_specific1", stored.codecSpecific1,
                    "stored_rate", stored.sampleRate);
            return;
        }

        CodecRequest.Builder builder = CodecRequest.fromActive(live);
        if (specific1Selectable) builder.withSpecific1(stored.codecSpecific1);
        if (sampleRateSelectable) builder.withSampleRate(stored.sampleRate);

        CodecRequest req = builder.build();
        MLog.event("replay.dispatch", "request", req);
        bridge.setCodec(req).whenComplete((result, throwable) -> {
            if (throwable != null) {
                MLog.e("replay future failed", throwable);
                return;
            }
            MLog.event("replay.outcome",
                    "path", result.path,
                    "outcome", result.outcome);
        });
    }

    private static boolean arrayContains(long[] arr, long value) {
        if (arr == null) return false;
        for (long v : arr) if (v == value) return true;
        return false;
    }
}
