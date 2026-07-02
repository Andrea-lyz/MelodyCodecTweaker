package xyz.melodylsp.codec.host;

import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import xyz.melodylsp.codec.bridge.CodecIpc;
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
    private static final long GAME_MODE_EXIT_REPLAY_DELAY_MS = 2_000L;
    private static final long GAME_MODE_LIVE_SBC_FALLBACK_MS = 180_000L;
    private static final long[] BOOTSTRAP_SCAN_DELAYS_MS = {1_500L, 6_000L, 15_000L};
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
    private final Map<String, Set<Integer>> gameModeActiveTypes = new HashMap<>();
    private final Map<String, Long> gameModeFallbackUntilMs = new HashMap<>();

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
                } else if (CodecIpc.ACTION_GAME_MODE_STATE.equals(action)) {
                    handleGameModeStateBroadcast(intent);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(A2dpRouteReadiness.ACTION_ACTIVE_DEVICE_CHANGED);
        filter.addAction(CodecIpc.ACTION_GAME_MODE_STATE);
        try {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            context.registerReceiver(receiver, filter);
        }
        MLog.event("replay.receiver.started",
                "process", processName,
                "replay", replayEnabled);
        scheduleBootstrapScans();
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
        maybeReplayConnected(key, REPLAY_DELAY_MS, "connected_ready");
    }

    private void handleBootstrapConnected(String mac, boolean active, int pass) {
        String key = A2dpRouteReadiness.normalizeMac(mac);
        if (key == null) return;
        if (active) {
            routeReadiness.markReady(key, "startup_scan_active");
        }
        MLog.event("replay.bootstrap.connected",
                "mac", A2dpRouteReadiness.redactMac(key),
                "active", active,
                "pass", pass);
        maybeReplayConnected(key, REPLAY_DELAY_MS, active
                ? "startup_scan_active"
                : "startup_scan_connected");
    }

    private void maybeReplayConnected(String key, long delayMs, String reason) {
        if (!replayEnabled) {
            MLog.event("replay.skip.process",
                    "mac", A2dpRouteReadiness.redactMac(key),
                    "process", processName);
            return;
        }
        if (!prefs.isRemembered(key)) {
            MLog.event("replay.skip.no_remember",
                    "mac", A2dpRouteReadiness.redactMac(key),
                    "reason", reason);
            return;
        }
        if (isGameModeSuppressed(key)) {
            suppressReplayForGameMode(key, reason);
            return;
        }
        PreferenceStore.RememberedValue value = prefs.readSnapshot(key);
        if (value == null) {
            MLog.event("replay.skip.snapshot_missing",
                    "mac", A2dpRouteReadiness.redactMac(key),
                    "reason", reason);
            return;
        }
        if (!routeReadiness.isReadyOrUnknown(key)) {
            synchronized (this) {
                pendingReplays.put(key, value);
                bumpGenerationLocked(key);
            }
            MLog.event("replay.pending_ready",
                    "mac", A2dpRouteReadiness.redactMac(key),
                    "reason", reason);
            return;
        }
        scheduleReplay(key, value, delayMs, reason);
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

    void onOfficialGameModeState(
            String mac,
            int type,
            boolean active,
            String source) {
        onOfficialGameModeState(mac, type, active, source, 0L);
    }

    private void handleGameModeStateBroadcast(Intent intent) {
        if (intent == null
                || !CodecIpc.ACTION_GAME_MODE_STATE.equals(intent.getAction())
                || !CodecIpc.TOKEN.equals(intent.getStringExtra(CodecIpc.EXTRA_TOKEN))) {
            return;
        }
        onOfficialGameModeState(
                intent.getStringExtra(CodecIpc.EXTRA_MAC),
                intent.getIntExtra(CodecIpc.EXTRA_GAME_MODE_TYPE, -1),
                intent.getBooleanExtra(CodecIpc.EXTRA_GAME_MODE_ACTIVE, false),
                intent.getStringExtra(CodecIpc.EXTRA_GAME_MODE_SOURCE),
                intent.getLongExtra(CodecIpc.EXTRA_GAME_MODE_TTL_MS, 0L));
    }

    private void onOfficialGameModeState(
            String mac,
            int type,
            boolean active,
            String source,
            long fallbackTtlMs) {
        String key = A2dpRouteReadiness.normalizeMac(mac);
        if (key == null) return;
        String safeSource = source != null && !source.isEmpty() ? source : "unknown";
        long now = System.currentTimeMillis();
        boolean fallbackOnly = fallbackTtlMs > 0L;
        boolean replayAfterExit = false;
        boolean suppressed;
        int activeTypeCount;
        synchronized (this) {
            boolean wasSuppressed = isGameModeSuppressedLocked(key, now);
            if (active) {
                if (!fallbackOnly) {
                    Set<Integer> activeTypes = gameModeActiveTypes.get(key);
                    if (activeTypes == null) {
                        activeTypes = new HashSet<>();
                        gameModeActiveTypes.put(key, activeTypes);
                    }
                    activeTypes.add(type);
                }
                if (fallbackTtlMs > 0L) {
                    long until = now + fallbackTtlMs;
                    gameModeFallbackUntilMs.put(key, until);
                    scheduleGameModeFallbackExpiry(key, until, fallbackTtlMs);
                }
                pendingReplays.remove(key);
                bumpGenerationLocked(key);
            } else {
                boolean removedActiveType = false;
                Set<Integer> activeTypes = gameModeActiveTypes.get(key);
                if (activeTypes != null) {
                    removedActiveType = activeTypes.remove(type);
                    if (activeTypes.isEmpty()) {
                        gameModeActiveTypes.remove(key);
                    }
                }
                // A matching host-side false state is the authoritative "game really exited"
                // signal. Ignore unmatched false/init pulses so they cannot clear the Bluetooth
                // SBC fallback while a backgrounded game is still holding the low-latency route.
                if (removedActiveType) {
                    gameModeFallbackUntilMs.remove(key);
                }
                bumpGenerationLocked(key);
            }
            suppressed = isGameModeSuppressedLocked(key, now);
            activeTypeCount = activeGameModeTypeCountLocked(key);
            replayAfterExit = wasSuppressed && !suppressed && !active;
        }
        MLog.event("game.mode.state",
                "mac", A2dpRouteReadiness.redactMac(key),
                "type", type,
                "active", active,
                "source", safeSource,
                "ttlMs", fallbackTtlMs,
                "suppressed", suppressed,
                "activeTypes", activeTypeCount);
        if (active) {
            MLog.event("replay.suppress.game_active",
                    "mac", A2dpRouteReadiness.redactMac(key),
                    "source", safeSource,
                    "type", type);
        } else if (replayAfterExit) {
            MLog.event("replay.suppress.game_exit",
                    "mac", A2dpRouteReadiness.redactMac(key),
                    "source", safeSource,
                    "delayMs", GAME_MODE_EXIT_REPLAY_DELAY_MS);
            maybeReplayConnected(key, GAME_MODE_EXIT_REPLAY_DELAY_MS, "game_mode_exit");
        }
    }

    private void scheduleGameModeFallbackExpiry(String key, long expectedUntil, long delayMs) {
        mainHandler.postDelayed(() -> expireGameModeFallback(key, expectedUntil),
                Math.max(1_000L, delayMs + 250L));
    }

    private void expireGameModeFallback(String key, long expectedUntil) {
        boolean replayAfterExpiry;
        synchronized (this) {
            Long currentUntil = gameModeFallbackUntilMs.get(key);
            if (currentUntil == null || currentUntil != expectedUntil) return;
            if (hasActiveGameModeTypesLocked(key)) return;
            gameModeFallbackUntilMs.remove(key);
            bumpGenerationLocked(key);
            replayAfterExpiry = true;
        }
        if (replayAfterExpiry) {
            MLog.event("replay.suppress.game_fallback_expired",
                    "mac", A2dpRouteReadiness.redactMac(key));
            maybeReplayConnected(key, GAME_MODE_EXIT_REPLAY_DELAY_MS, "game_mode_fallback_expired");
        }
    }

    private void scheduleBootstrapScans() {
        if (!replayEnabled) return;
        for (int i = 0; i < BOOTSTRAP_SCAN_DELAYS_MS.length; i++) {
            final int pass = i + 1;
            long delayMs = BOOTSTRAP_SCAN_DELAYS_MS[i];
            mainHandler.postDelayed(() -> runBootstrapScan(pass), delayMs);
        }
    }

    private void runBootstrapScan(int pass) {
        Thread worker = new Thread(() -> {
            Map<String, Boolean> devices = queryCurrentA2dpDevices();
            MLog.event("replay.bootstrap.scan",
                    "pass", pass,
                    "count", devices.size());
            for (Map.Entry<String, Boolean> entry : devices.entrySet()) {
                handleBootstrapConnected(entry.getKey(), entry.getValue(), pass);
            }
            probeRememberedDevices(pass, devices);
        }, "MelodyCodecLsp-replayBootstrap");
        worker.setDaemon(true);
        worker.start();
    }

    private void probeRememberedDevices(int pass, Map<String, Boolean> alreadySeen) {
        String[] rememberedMacs = prefs.rememberedMacs();
        int candidates = 0;
        int matched = 0;
        for (String mac : rememberedMacs) {
            String key = A2dpRouteReadiness.normalizeMac(mac);
            if (key == null || alreadySeen.containsKey(key)) continue;
            PreferenceStore.RememberedValue stored = prefs.readSnapshot(key);
            if (stored == null) continue;
            candidates++;
            CodecSnapshot live = bridge.getStatus(key);
            if (live == null) continue;
            matched++;
            MLog.event("replay.bootstrap.live_vs_stored",
                    "mac", A2dpRouteReadiness.redactMac(key),
                    "pass", pass,
                    "stored_codec", stored.codecType,
                    "stored_rate", stored.sampleRate,
                    "stored_specific1", stored.codecSpecific1,
                    "live_codec", live.activeCodecType,
                    "live_rate", live.activeSampleRate,
                    "live_specific1", live.activeCodecSpecific1);
            maybeReplayConnected(key, REPLAY_DELAY_MS, "startup_stored_snapshot");
        }
        if (candidates > 0 || alreadySeen.isEmpty()) {
            MLog.event("replay.bootstrap.remembered_probe",
                    "pass", pass,
                    "candidates", candidates,
                    "matched", matched);
        }
    }

    @SuppressWarnings("deprecation")
    private Map<String, Boolean> queryCurrentA2dpDevices() {
        Map<String, Boolean> devices = new HashMap<>();
        BluetoothAdapter adapter = resolveAdapter();
        if (adapter != null) {
            try {
                Method getActiveDevices = adapter.getClass().getMethod("getActiveDevices", int.class);
                Object activeDevices = getActiveDevices.invoke(adapter, BluetoothProfile.A2DP);
                addDeviceCollection(devices, activeDevices, true);
            } catch (Throwable t) {
                if (containsSecurityException(t)) {
                    MLog.event("replay.bootstrap.active_denied");
                } else {
                    MLog.event("replay.bootstrap.active_unavailable",
                            "error", t.getClass().getSimpleName());
                }
            }
        }

        try {
            Object service = context.getSystemService(Context.BLUETOOTH_SERVICE);
            if (service instanceof BluetoothManager) {
                List<BluetoothDevice> connected =
                        ((BluetoothManager) service).getConnectedDevices(BluetoothProfile.A2DP);
                addDeviceCollection(devices, connected, false);
            }
        } catch (Throwable t) {
            if (containsSecurityException(t)) {
                MLog.event("replay.bootstrap.connected_denied");
            } else {
                MLog.event("replay.bootstrap.connected_unavailable",
                        "error", t.getClass().getSimpleName());
            }
        }
        return devices;
    }

    private BluetoothAdapter resolveAdapter() {
        try {
            Object service = context.getSystemService(Context.BLUETOOTH_SERVICE);
            if (service instanceof BluetoothManager) {
                BluetoothAdapter adapter = ((BluetoothManager) service).getAdapter();
                if (adapter != null) return adapter;
            }
        } catch (Throwable ignored) {
        }
        try {
            return BluetoothAdapter.getDefaultAdapter();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void addDeviceCollection(
            Map<String, Boolean> out,
            Object devices,
            boolean active) {
        if (devices == null) return;
        if (devices instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) devices) {
                addDevice(out, item, active);
            }
            return;
        }
        Class<?> cls = devices.getClass();
        if (!cls.isArray()) return;
        int len = java.lang.reflect.Array.getLength(devices);
        for (int i = 0; i < len; i++) {
            addDevice(out, java.lang.reflect.Array.get(devices, i), active);
        }
    }

    private static void addDevice(Map<String, Boolean> out, Object item, boolean active) {
        String mac = macFromObject(item);
        if (mac == null) return;
        Boolean oldActive = out.get(mac);
        out.put(mac, Boolean.TRUE.equals(oldActive) || active);
    }

    private static String macFromObject(Object item) {
        if (item == null) return null;
        if (item instanceof BluetoothDevice) {
            try {
                return A2dpRouteReadiness.normalizeMac(((BluetoothDevice) item).getAddress());
            } catch (Throwable ignored) {
                return null;
            }
        }
        return A2dpRouteReadiness.normalizeMac(String.valueOf(item));
    }

    private static boolean containsSecurityException(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof SecurityException) return true;
            cur = cur.getCause();
        }
        return false;
    }

    private void scheduleReplay(
            String mac,
            PreferenceStore.RememberedValue stored,
            long delayMs,
            String reason) {
        if (!replayEnabled) return;
        if (isGameModeSuppressed(mac)) {
            suppressReplayForGameMode(mac, reason);
            return;
        }
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
            if (isGameModeSuppressed(mac)) {
                suppressReplayForGameMode(mac, reason);
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
            if (isGameModeSuppressed(mac)) {
                suppressReplayForGameMode(mac, "worker_start");
                return;
            }
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
        if (isGameModeSuppressed(mac)) {
            suppressReplayForGameMode(mac, "before_read");
            return;
        }
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

        if (isGameModeSbcSnapshot(live)) {
            onOfficialGameModeState(
                    mac,
                    -1,
                    true,
                    "live.sbc_s2_" + live.activeCodecSpecific2,
                    GAME_MODE_LIVE_SBC_FALLBACK_MS);
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

        int replayCodecType = replayCodecType(live, stored.codecType);
        boolean codecSelectable = replayCodecType >= 0
                && !sameCodecFamily(live.activeCodecType, replayCodecType);
        int requestCodecType = replayCodecType >= 0 ? replayCodecType : live.activeCodecType;
        boolean specific1Selectable =
                isSpecific1Selectable(live, requestCodecType, stored.codecSpecific1);
        boolean sampleRateSelectable =
                isSampleRateSelectable(live, requestCodecType, stored.sampleRate);

        if (!codecSelectable && !specific1Selectable && !sampleRateSelectable) {
            MLog.event("replay.skip.both",
                    "mac", mac,
                    "stored_codec", stored.codecType,
                    "stored_specific1", stored.codecSpecific1,
                    "stored_rate", stored.sampleRate);
            scheduleReplayRetry(mac, stored, generation, attempt);
            return;
        }

        CodecRequest.Builder builder = CodecRequest.fromActive(live);
        if (codecSelectable) {
            int capIndex = selectableCodecIndex(live, requestCodecType);
            builder.codecType(requestCodecType)
                    .codecSpecific2(0L)
                    .codecSpecific3(0L)
                    .codecSpecific4(0L)
                    .bitsPerSample(firstBit(selectableIntValue(
                            live.selectableCodecBitsPerSample, capIndex)))
                    .channelMode(firstBit(selectableIntValue(
                            live.selectableCodecChannelModes, capIndex)));
        }
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
                "mac", A2dpRouteReadiness.redactMac(mac),
                "attempt", attempt,
                "request", req);
        bridge.setCodec(req, () -> isReplayStillCurrent(mac, stored, generation))
                .whenComplete((result, throwable) -> {
            if (throwable != null) {
                MLog.e("replay future failed", throwable);
            } else {
                MLog.event("replay.outcome",
                        "mac", A2dpRouteReadiness.redactMac(mac),
                        "attempt", attempt,
                        "path", result != null ? result.path : "unknown",
                        "outcome", result != null ? result.outcome : "null");
            }
            scheduleReplayRetry(mac, stored, generation, attempt);
        });
    }

    private void scheduleReplayRetry(
            String mac,
            PreferenceStore.RememberedValue stored,
            long generation,
            int attempt) {
        mainHandler.postDelayed(() -> {
            if (!isReplayStillCurrent(mac, stored, generation)) return;
            if (isGameModeSuppressed(mac)) {
                suppressReplayForGameMode(mac, "verify");
                return;
            }
            if (!routeReadiness.isReadyOrUnknown(mac)) {
                MLog.event("replay.verify.skip_not_ready",
                        "mac", A2dpRouteReadiness.redactMac(mac),
                        "attempt", attempt);
                return;
            }
            Thread worker = new Thread(() -> {
                if (!isReplayStillCurrent(mac, stored, generation)) return;
                if (isGameModeSuppressed(mac)) {
                    suppressReplayForGameMode(mac, "verify_worker");
                    return;
                }
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
        if (isGameModeSuppressed(mac)) {
            MLog.event("replay.stale.game_mode",
                    "mac", A2dpRouteReadiness.redactMac(mac),
                    "generation", generation);
            return false;
        }
        PreferenceStore.RememberedValue current = prefs.readSnapshot(mac);
        if (!sameRememberedValue(stored, current)) {
            MLog.event("replay.stale.preference",
                    "mac", A2dpRouteReadiness.redactMac(mac),
                    "generation", generation);
            return false;
        }
        return true;
    }

    private void suppressReplayForGameMode(String mac, String reason) {
        synchronized (this) {
            pendingReplays.remove(mac);
            bumpGenerationLocked(mac);
        }
        MLog.event("replay.suppress.game_active",
                "mac", A2dpRouteReadiness.redactMac(mac),
                "reason", reason,
                "activeTypes", activeGameModeTypeCount(mac));
    }

    private boolean isGameModeSuppressed(String mac) {
        String key = A2dpRouteReadiness.normalizeMac(mac);
        if (key == null) return false;
        synchronized (this) {
            return isGameModeSuppressedLocked(key, System.currentTimeMillis());
        }
    }

    private boolean isGameModeSuppressedLocked(String key, long now) {
        if (hasActiveGameModeTypesLocked(key)) return true;
        Long until = gameModeFallbackUntilMs.get(key);
        if (until == null) return false;
        if (until > now) return true;
        gameModeFallbackUntilMs.remove(key);
        return false;
    }

    private boolean hasActiveGameModeTypesLocked(String key) {
        Set<Integer> activeTypes = gameModeActiveTypes.get(key);
        return activeTypes != null && !activeTypes.isEmpty();
    }

    private int activeGameModeTypeCount(String mac) {
        String key = A2dpRouteReadiness.normalizeMac(mac);
        if (key == null) return 0;
        synchronized (this) {
            return activeGameModeTypeCountLocked(key);
        }
    }

    private int activeGameModeTypeCountLocked(String key) {
        Set<Integer> activeTypes = gameModeActiveTypes.get(key);
        return activeTypes != null ? activeTypes.size() : 0;
    }

    private static boolean isStandardCodec(int codecType) {
        return codecType == CodecLabelTable.CODEC_SBC
                || codecType == CodecLabelTable.CODEC_AAC;
    }

    private static boolean isGameModeSbcSnapshot(CodecSnapshot snapshot) {
        return snapshot != null
                && snapshot.activeCodecType == CodecLabelTable.CODEC_SBC
                && snapshot.activeCodecSpecific2 > 0L;
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

    private static int replayCodecType(CodecSnapshot live, int storedCodecType) {
        if (live == null || storedCodecType < 0) return -1;
        if (sameCodecFamily(live.activeCodecType, storedCodecType)) {
            return live.activeCodecType;
        }
        int index = selectableCodecIndex(live, storedCodecType);
        if (index >= 0) return live.selectableCodecTypes[index];
        if (!isStandardCodec(storedCodecType)) return storedCodecType;
        return -1;
    }

    private static int selectableCodecIndex(CodecSnapshot live, int codecType) {
        if (live == null || live.selectableCodecTypes == null) return -1;
        for (int i = 0; i < live.selectableCodecTypes.length; i++) {
            if (live.selectableCodecTypes[i] == codecType) return i;
        }
        if (CodecLabelTable.isLhdc(codecType)) {
            for (int i = 0; i < live.selectableCodecTypes.length; i++) {
                if (CodecLabelTable.isLhdc(live.selectableCodecTypes[i])) return i;
            }
        }
        return -1;
    }

    private static int selectableIntValue(int[] values, int index) {
        if (values == null || index < 0 || index >= values.length) return 0;
        return values[index];
    }

    private static int firstBit(int mask) {
        if (mask == 0) return 0;
        return mask & -mask;
    }

    private static boolean isSpecific1Selectable(CodecSnapshot live, int codecType, long value) {
        if (live == null) return false;
        if (value < 0L) return false;
        if (arrayContains(live.selectableCodecSpecific1, value)) return true;
        return CodecLabelTable.isLhdc(codecType)
                || codecType == CodecLabelTable.CODEC_LDAC;
    }

    private static boolean isSampleRateSelectable(CodecSnapshot live, int codecType, int value) {
        if (live == null) return false;
        if (value == 0) return true;
        int index = selectableCodecIndex(live, codecType);
        int mask = selectableIntValue(live.selectableCodecSampleRates, index);
        if (mask == 0 && sameCodecFamily(live.activeCodecType, codecType)) {
            mask = live.selectableSampleRateMask;
        }
        if ((mask & value) != 0) return true;
        if (CodecLabelTable.isLhdc(codecType)) {
            return value == 0x2 || value == 0x8 || value == 0x20;
        }
        return codecType == CodecLabelTable.CODEC_LDAC
                && (value == 0x2 || value == 0x8);
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
