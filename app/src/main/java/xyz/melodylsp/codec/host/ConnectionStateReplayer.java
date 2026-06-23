package xyz.melodylsp.codec.host;

import android.app.Application;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.Map;

import xyz.melodylsp.codec.bridge.CodecRequest;
import xyz.melodylsp.codec.bridge.CodecSnapshot;
import xyz.melodylsp.codec.label.CodecLabelTable;
import xyz.melodylsp.codec.storage.PreferenceStore;
import xyz.melodylsp.codec.util.MLog;

/**
 * Watches A2DP connection events and replays the stored codec snapshot when
 * {@code Remember_Toggle=true}. Replays are skipped silently when the persisted value is no longer
 * in the freshly negotiated capabilities (Requirement 7.9).
 */
public final class ConnectionStateReplayer {

    private static final String ACTION_CONNECTION_STATE_CHANGED =
            "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED";
    private static final String EXTRA_DEVICE = "android.bluetooth.device.extra.DEVICE";
    private static final String EXTRA_STATE = "android.bluetooth.profile.extra.STATE";
    private static final int STATE_CONNECTED = 2;

    private static final long REPLAY_DELAY_MS = 1_500L;
    private static final long REPLAY_VERIFY_DELAY_MS = 2_000L;
    private static final int MAX_REPLAY_WRITE_ATTEMPTS = 2;

    private final Context context;
    private final CodecBridgeClient bridge;
    private final PreferenceStore prefs;
    private final A2dpRouteReadiness routeReadiness;
    private final String processName;
    private final boolean replayEnabled;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, PreferenceStore.RememberedValue> pendingReplays = new HashMap<>();
    private final Map<String, Long> replayGenerations = new HashMap<>();

    private BroadcastReceiver receiver;

    public ConnectionStateReplayer(
            Context context,
            CodecBridgeClient bridge,
            PreferenceStore prefs,
            A2dpRouteReadiness routeReadiness) {
        this.context = context.getApplicationContext();
        this.bridge = bridge;
        this.prefs = prefs;
        this.routeReadiness = routeReadiness;
        this.processName = resolveProcessName(this.context);
        this.replayEnabled = isReplayOwnerProcess(this.context, processName);
    }

    public void start() {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                if (ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                    BluetoothDevice device = intent.getParcelableExtra(EXTRA_DEVICE);
                    if (device == null) return;
                    String mac = device.getAddress();
                    if (mac == null || mac.isEmpty()) mac = device.toString();
                    int state = intent.getIntExtra(EXTRA_STATE, -1);
                    if (state == STATE_CONNECTED) {
                        handleConnected(mac);
                    } else if (state != -1) {
                        handleDisconnected(mac);
                    }
                } else if (A2dpRouteReadiness.ACTION_ACTIVE_DEVICE_CHANGED.equals(action)) {
                    handleActiveDeviceChanged(intent);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(A2dpRouteReadiness.ACTION_ACTIVE_DEVICE_CHANGED);
        try {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            context.registerReceiver(receiver, filter);
        }
        MLog.event("replay.receiver.started",
                "process", processName,
                "replay", replayEnabled);
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
        String key = A2dpRouteReadiness.normalizeMac(mac);
        if (key == null) return;
        routeReadiness.markConnected(key);
        if (!replayEnabled) {
            MLog.event("replay.skip.process",
                    "mac", A2dpRouteReadiness.redactMac(key),
                    "process", processName);
            return;
        }
        if (!prefs.isRemembered(key)) {
            MLog.d("connected mac=" + A2dpRouteReadiness.redactMac(key)
                    + " remember=false; no replay");
            return;
        }
        PreferenceStore.RememberedValue value = prefs.readSnapshot(key);
        if (value == null) {
            MLog.d("connected mac=" + A2dpRouteReadiness.redactMac(key)
                    + " remember=true but snapshot missing");
            return;
        }
        if (!routeReadiness.isReadyOrUnknown(key)) {
            synchronized (this) {
                pendingReplays.put(key, value);
                bumpGenerationLocked(key);
            }
            MLog.event("replay.pending_ready",
                    "mac", A2dpRouteReadiness.redactMac(key));
            return;
        }
        scheduleReplay(key, value, REPLAY_DELAY_MS, "connected_ready");
    }

    private void handleDisconnected(String mac) {
        String key = A2dpRouteReadiness.normalizeMac(mac);
        if (key == null) return;
        synchronized (this) {
            pendingReplays.remove(key);
            bumpGenerationLocked(key);
        }
        routeReadiness.markDisconnected(key);
    }

    @SuppressWarnings("deprecation")
    private void handleActiveDeviceChanged(Intent intent) {
        BluetoothDevice device;
        try {
            device = intent.getParcelableExtra(EXTRA_DEVICE);
        } catch (Throwable t) {
            return;
        }
        if (device == null) return;
        String key = A2dpRouteReadiness.normalizeMac(device.getAddress());
        if (key == null) return;
        routeReadiness.markReady(key, "replay.active_broadcast");
        if (!replayEnabled) return;
        PreferenceStore.RememberedValue pending;
        synchronized (this) {
            pending = pendingReplays.remove(key);
        }
        if (pending != null) {
            scheduleReplay(key, pending, REPLAY_DELAY_MS, "active_ready");
        }
    }

    private void scheduleReplay(
            String mac,
            PreferenceStore.RememberedValue stored,
            long delayMs,
            String reason) {
        if (!replayEnabled) return;
        long generation;
        synchronized (this) {
            generation = bumpGenerationLocked(mac);
        }
        MLog.event("replay.schedule",
                "mac", A2dpRouteReadiness.redactMac(mac),
                "delayMs", delayMs,
                "reason", reason);
        mainHandler.postDelayed(() -> {
            if (!isCurrentReplay(mac, generation)) {
                MLog.event("replay.stale.ignore",
                        "mac", A2dpRouteReadiness.redactMac(mac),
                        "generation", generation);
                return;
            }
            if (!routeReadiness.isReadyOrUnknown(mac)) {
                synchronized (ConnectionStateReplayer.this) {
                    pendingReplays.put(mac, stored);
                }
                MLog.event("replay.skip.not_ready",
                        "mac", A2dpRouteReadiness.redactMac(mac),
                        "generation", generation);
                return;
            }
            runReplayWorker(mac, stored, generation);
        }, delayMs);
    }

    private synchronized long bumpGenerationLocked(String mac) {
        Long current = replayGenerations.get(mac);
        long next = current != null ? current + 1L : 1L;
        replayGenerations.put(mac, next);
        return next;
    }

    private synchronized boolean isCurrentReplay(String mac, long generation) {
        Long current = replayGenerations.get(mac);
        return current != null && current == generation;
    }

    private void runReplayWorker(
            String mac,
            PreferenceStore.RememberedValue stored,
            long generation) {
        runReplayWorker(mac, stored, generation, 0);
    }

    private void runReplayWorker(
            String mac,
            PreferenceStore.RememberedValue stored,
            long generation,
            int attempt) {
        Thread worker = new Thread(() -> {
            if (!isCurrentReplay(mac, generation)) return;
            replay(mac, stored, generation, attempt);
        }, "MelodyCodecLsp-replay");
        worker.setDaemon(true);
        worker.start();
    }

    private void replay(
            String mac,
            PreferenceStore.RememberedValue stored,
            long generation,
            int attempt) {
        if (!replayEnabled) return;
        if (!isReplayStillCurrent(mac, stored, generation)) return;
        if (!routeReadiness.isReadyOrUnknown(mac)) {
            synchronized (this) {
                pendingReplays.put(mac, stored);
            }
            MLog.event("replay.abort.not_ready",
                    "mac", A2dpRouteReadiness.redactMac(mac));
            return;
        }
        CodecSnapshot live = bridge.getStatus(mac);
        if (live == null) {
            MLog.w("replay skipped, getStatus returned null mac="
                    + A2dpRouteReadiness.redactMac(mac));
            scheduleReplayRetry(mac, stored, generation, attempt);
            return;
        }

        if (matchesStoredValue(live, stored)) {
            MLog.event("replay.already_applied",
                    "mac", A2dpRouteReadiness.redactMac(mac),
                    "attempt", attempt,
                    "live", live);
            return;
        }

        if (isStandardCodec(stored.codecType)) {
            CodecRequest req = CodecRequest.fromActive(live)
                    .codecType(stored.codecType)
                    .codecSpecific1(0L)
                    .codecSpecific2(0L)
                    .codecSpecific3(0L)
                    .codecSpecific4(0L)
                    .sampleRate(0)
                    .bitsPerSample(0)
                    .channelMode(0)
                    .build();
            dispatchReplay(mac, stored, req, generation, attempt);
            return;
        }

        boolean specific1Selectable = isSpecific1Selectable(live, stored.codecSpecific1);
        boolean sampleRateSelectable = isSampleRateSelectable(live, stored.sampleRate);

        if (!specific1Selectable && !sampleRateSelectable) {
            MLog.event("replay.skip.both",
                    "mac", mac,
                    "stored_codec", stored.codecType,
                    "stored_specific1", stored.codecSpecific1,
                    "stored_rate", stored.sampleRate);
            scheduleReplayRetry(mac, stored, generation, attempt);
            return;
        }

        CodecRequest.Builder builder = CodecRequest.fromActive(live);
        if (specific1Selectable) builder.withSpecific1(stored.codecSpecific1);
        if (sampleRateSelectable) builder.withSampleRate(stored.sampleRate);

        CodecRequest req = builder.build();
        dispatchReplay(mac, stored, req, generation, attempt);
    }

    private void dispatchReplay(
            String mac,
            PreferenceStore.RememberedValue stored,
            CodecRequest req,
            long generation,
            int attempt) {
        MLog.event("replay.dispatch",
                "attempt", attempt,
                "request", req);
        bridge.setCodec(req, () -> isReplayStillCurrent(mac, stored, generation))
                .whenComplete((result, throwable) -> {
            if (throwable != null) {
                MLog.e("replay future failed", throwable);
            } else {
                MLog.event("replay.outcome",
                        "attempt", attempt,
                        "path", result != null ? result.path : "unknown",
                        "outcome", result != null ? result.outcome : "null");
            }
            if (throwable != null
                    || result == null
                    || result.outcome != WriteResult.Outcome.CONFIRMED) {
                scheduleReplayRetry(mac, stored, generation, attempt);
            }
        });
    }

    private void scheduleReplayRetry(
            String mac,
            PreferenceStore.RememberedValue stored,
            long generation,
            int attempt) {
        mainHandler.postDelayed(() -> {
            if (!isReplayStillCurrent(mac, stored, generation)) return;
            if (!routeReadiness.isReadyOrUnknown(mac)) {
                MLog.event("replay.verify.skip_not_ready",
                        "mac", A2dpRouteReadiness.redactMac(mac),
                        "attempt", attempt);
                return;
            }
            Thread worker = new Thread(() -> {
                if (!isReplayStillCurrent(mac, stored, generation)) return;
                CodecSnapshot live = bridge.getStatus(mac);
                if (live != null && matchesStoredValue(live, stored)) {
                    MLog.event("replay.stable",
                            "mac", A2dpRouteReadiness.redactMac(mac),
                            "attempt", attempt,
                            "live", live);
                    return;
                }
                int nextAttempt = attempt + 1;
                if (nextAttempt >= MAX_REPLAY_WRITE_ATTEMPTS) {
                    MLog.event("replay.unstable",
                            "mac", A2dpRouteReadiness.redactMac(mac),
                            "attempts", MAX_REPLAY_WRITE_ATTEMPTS,
                            "live", String.valueOf(live));
                    return;
                }
                MLog.event("replay.retry",
                        "mac", A2dpRouteReadiness.redactMac(mac),
                        "attempt", nextAttempt,
                        "live", String.valueOf(live));
                replay(mac, stored, generation, nextAttempt);
            }, "MelodyCodecLsp-replayVerify");
            worker.setDaemon(true);
            worker.start();
        }, REPLAY_VERIFY_DELAY_MS);
    }

    private boolean isReplayStillCurrent(
            String mac,
            PreferenceStore.RememberedValue stored,
            long generation) {
        if (!isCurrentReplay(mac, generation)) return false;
        PreferenceStore.RememberedValue current = prefs.readSnapshot(mac);
        if (!sameRememberedValue(stored, current)) {
            MLog.event("replay.stale.preference",
                    "mac", A2dpRouteReadiness.redactMac(mac),
                    "generation", generation);
            return false;
        }
        return true;
    }

    private static boolean isStandardCodec(int codecType) {
        return codecType == CodecLabelTable.CODEC_SBC
                || codecType == CodecLabelTable.CODEC_AAC;
    }

    private static boolean sameRememberedValue(
            PreferenceStore.RememberedValue first,
            PreferenceStore.RememberedValue second) {
        if (first == second) return true;
        if (first == null || second == null) return false;
        return first.codecType == second.codecType
                && first.codecSpecific1 == second.codecSpecific1
                && first.sampleRate == second.sampleRate;
    }

    private static boolean matchesStoredValue(
            CodecSnapshot live,
            PreferenceStore.RememberedValue stored) {
        if (live == null || stored == null) return false;
        if (stored.codecType >= 0 && !sameCodecFamily(live.activeCodecType, stored.codecType)) {
            return false;
        }
        if (isStandardCodec(stored.codecType)) return true;
        if (stored.sampleRate > 0 && live.activeSampleRate != stored.sampleRate) {
            return false;
        }
        if (stored.codecSpecific1 < 0L) return true;
        if (CodecLabelTable.isLhdc(live.activeCodecType)
                || CodecLabelTable.isLhdc(stored.codecType)) {
            long active = live.activeCodecSpecific1 & 0xFFL;
            long remembered = stored.codecSpecific1 & 0xFFL;
            return active == remembered || isLhdcFixedCeilingPair(active, remembered);
        }
        return live.activeCodecSpecific1 == stored.codecSpecific1;
    }

    private static boolean sameCodecFamily(int activeCodecType, int storedCodecType) {
        if (activeCodecType == storedCodecType) return true;
        return CodecLabelTable.isLhdc(activeCodecType)
                && CodecLabelTable.isLhdc(storedCodecType);
    }

    private static boolean isLhdcFixedCeilingPair(long first, long second) {
        return (first == CodecLabelTable.LHDC_QUALITY_FIXED_900
                && second == CodecLabelTable.LHDC_QUALITY_FIXED_1000)
                || (first == CodecLabelTable.LHDC_QUALITY_FIXED_1000
                && second == CodecLabelTable.LHDC_QUALITY_FIXED_900);
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

    private static String resolveProcessName(Context context) {
        try {
            String name = Application.getProcessName();
            if (name != null && !name.isEmpty()) return name;
        } catch (Throwable ignored) {
        }
        try {
            Class<?> cls = Class.forName("android.app.ActivityThread");
            Object value = cls.getMethod("currentProcessName").invoke(null);
            if (value instanceof String && !((String) value).isEmpty()) return (String) value;
        } catch (Throwable ignored) {
        }
        try {
            return context != null ? context.getPackageName() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isReplayOwnerProcess(Context context, String processName) {
        if (processName == null || processName.isEmpty()) return true;
        String packageName;
        try {
            packageName = context != null ? context.getPackageName() : null;
        } catch (Throwable ignored) {
            packageName = null;
        }
        if (packageName == null || packageName.isEmpty()) return true;
        if ((packageName + ":fg").equals(processName)) return true;
        if (packageName.equals(processName)) return false;
        if (processName.startsWith(packageName + ":")) return processName.endsWith(":fg");
        return true;
    }
}
