package xyz.melodylsp.codec.host;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import xyz.melodylsp.codec.bridge.CodecRequest;
import xyz.melodylsp.codec.bridge.CodecSnapshot;
import xyz.melodylsp.codec.label.CodecLabelTable;
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
    private static final String ACTION_ACTIVE_DEVICE_CHANGED =
            "android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED";
    private static final String ACTION_PLAYING_STATE_CHANGED =
            "android.bluetooth.a2dp.profile.action.PLAYING_STATE_CHANGED";
    private static final String EXTRA_DEVICE = "android.bluetooth.device.extra.DEVICE";
    private static final String EXTRA_STATE = "android.bluetooth.profile.extra.STATE";
    private static final int STATE_CONNECTED = 2;
    private static final int STATE_PLAYING = 10;

    private static final long REPLAY_DELAY_MS = 1_500L;

    private final Context context;
    private final CodecBridgeClient bridge;
    private final PreferenceStore prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, PreferenceStore.RememberedValue> pendingReplays = new HashMap<>();
    private final Map<String, Long> replayGenerations = new HashMap<>();
    private final Set<String> activeDevices = new HashSet<>();
    private final Set<String> playingDevices = new HashSet<>();

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
                String action = intent.getAction();
                if (ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                    handleConnectionChanged(intent);
                } else if (ACTION_ACTIVE_DEVICE_CHANGED.equals(action)) {
                    handleActiveDeviceChanged(intent);
                } else if (ACTION_PLAYING_STATE_CHANGED.equals(action)) {
                    handlePlayingStateChanged(intent);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(ACTION_ACTIVE_DEVICE_CHANGED);
        filter.addAction(ACTION_PLAYING_STATE_CHANGED);
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
        pendingReplays.clear();
        activeDevices.clear();
        playingDevices.clear();
        replayGenerations.clear();
    }

    private void handleConnectionChanged(Intent intent) {
        int state = intent.getIntExtra(EXTRA_STATE, -1);
        String mac = macFromIntent(intent);
        if (mac == null) return;
        if (state != STATE_CONNECTED) {
            pendingReplays.remove(mac);
            activeDevices.remove(mac);
            playingDevices.remove(mac);
            bumpReplayGeneration(mac);
            MLog.event("replay.cancel", "mac", redact(mac), "reason", "disconnected");
            return;
        }
        handleConnected(mac);
    }

    private void handleActiveDeviceChanged(Intent intent) {
        String mac = macFromIntent(intent);
        if (mac == null) {
            Set<String> previous = new HashSet<>(activeDevices);
            previous.addAll(playingDevices);
            activeDevices.clear();
            playingDevices.clear();
            if (!previous.isEmpty()) {
                for (String item : previous) bumpReplayGeneration(item);
                MLog.event("replay.active.clear");
            }
            return;
        }
        Set<String> previous = new HashSet<>(activeDevices);
        previous.addAll(playingDevices);
        activeDevices.clear();
        playingDevices.clear();
        activeDevices.add(mac);
        for (String item : previous) {
            if (!mac.equals(item)) bumpReplayGeneration(item);
        }
        PreferenceStore.RememberedValue pending = pendingReplays.get(mac);
        if (pending != null) {
            scheduleReplayIfReady(mac, pending, "active_device");
        }
    }

    private void handlePlayingStateChanged(Intent intent) {
        String mac = macFromIntent(intent);
        if (mac == null) return;
        int state = intent.getIntExtra(EXTRA_STATE, -1);
        if (state == STATE_PLAYING) {
            playingDevices.add(mac);
            PreferenceStore.RememberedValue pending = pendingReplays.get(mac);
            if (pending != null) {
                scheduleReplayIfReady(mac, pending, "playing");
            }
        } else {
            playingDevices.remove(mac);
        }
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
        pendingReplays.put(mac, value);
        if (isReplayReady(mac)) {
            scheduleReplayIfReady(mac, value, "connected_ready");
        } else {
            MLog.event("replay.pending", "mac", redact(mac), "reason", "await_active_or_playing");
        }
    }

    private void scheduleReplayIfReady(
            String mac, PreferenceStore.RememberedValue stored, String reason) {
        long generation = bumpReplayGeneration(mac);
        MLog.event("replay.ready", "mac", redact(mac), "reason", reason,
                "delayMs", REPLAY_DELAY_MS);
        mainHandler.postDelayed(() -> {
            if (!isCurrentGeneration(mac, generation)) return;
            if (!isReplayReady(mac)) {
                MLog.event("replay.skip_not_ready", "mac", redact(mac), "reason", reason);
                return;
            }
            pendingReplays.remove(mac);
            Thread worker = new Thread(() -> replay(mac, stored), "MelodyCodecLsp-replay");
            worker.setDaemon(true);
            worker.start();
        }, REPLAY_DELAY_MS);
    }

    private boolean isReplayReady(String mac) {
        return activeDevices.contains(mac) || playingDevices.contains(mac);
    }

    private long bumpReplayGeneration(String mac) {
        if (mac == null) return 0L;
        long next = replayGenerations.containsKey(mac) ? replayGenerations.get(mac) + 1L : 1L;
        replayGenerations.put(mac, next);
        return next;
    }

    private boolean isCurrentGeneration(String mac, long generation) {
        Long current = replayGenerations.get(mac);
        return current != null && current == generation;
    }

    private void replay(String mac, PreferenceStore.RememberedValue stored) {
        CodecSnapshot live = bridge.getStatus(mac);
        if (live == null) {
            MLog.w("replay skipped, getStatus returned null mac=" + mac);
            return;
        }

        boolean specific1Selectable = isSpecific1Selectable(live, stored.codecSpecific1);
        boolean sampleRateSelectable = isSampleRateSelectable(live, stored.sampleRate);

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
        for (long v : arr) {
            if (v == value) return true;
            if ((v & 0xFFL) == (value & 0xFFL)) return true;
        }
        return false;
    }

    private static boolean isSpecific1Selectable(CodecSnapshot live, long value) {
        if (live == null) return false;
        if (arrayContains(live.selectableCodecSpecific1, value)) return true;
        return CodecLabelTable.isLhdc(live.activeCodecType)
                || live.activeCodecType == CodecLabelTable.CODEC_LDAC;
    }

    private static boolean isSampleRateSelectable(CodecSnapshot live, int value) {
        if (live == null) return false;
        if (value == 0) return true;
        int mask = live.selectableSampleRateMask;
        if ((mask & value) != 0) return true;
        return CodecLabelTable.isLhdc(live.activeCodecType)
                && (value == 0x2 || value == 0x8 || value == 0x20);
    }

    private static String macFromIntent(Intent intent) {
        if (intent == null) return null;
        BluetoothDevice device = intent.getParcelableExtra(EXTRA_DEVICE);
        if (device == null) return null;
        String mac = device.getAddress();
        if (mac == null || mac.isEmpty()) mac = device.toString();
        return mac;
    }

    private static String redact(String mac) {
        if (mac == null || mac.length() < 5) return "??";
        return mac.substring(0, 2) + "**" + mac.substring(mac.length() - 2);
    }
}
