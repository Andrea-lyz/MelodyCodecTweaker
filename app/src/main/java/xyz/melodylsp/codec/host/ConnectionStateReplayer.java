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

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
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
import xyz.melodylsp.codec.util.TrustedBroadcasts;

/**
 * Watches A2DP connection events and replays the stored codec snapshot when
 * {@code Remember_Toggle=true}. Replays are skipped silently when the persisted value is no longer
 * in the freshly negotiated capabilities (Requirement 7.9).
 */
public final class ConnectionStateReplayer {

    private static final String ACTION_CONNECTION_STATE_CHANGED =
            "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED";
    private static final String ACTION_USER_CODEC_WRITE =
            "xyz.melodylsp.codec.action.USER_CODEC_WRITE";
    private static final String EXTRA_DEVICE = "android.bluetooth.device.extra.DEVICE";
    private static final String EXTRA_STATE = "android.bluetooth.profile.extra.STATE";
    private static final String EXTRA_USER_CODEC_WRITE_REASON = "user_codec_write_reason";
    private static final String EXTRA_USER_CODEC_WRITE_ORIGIN = "user_codec_write_origin";
    private static final int STATE_CONNECTED = 2;

    private static final long REPLAY_DELAY_MS = 1_500L;
    private static final long REPLAY_VERIFY_DELAY_MS = 2_000L;
    private static final long GAME_MODE_EXIT_REPLAY_DELAY_MS = 800L;
    private static final long GAME_MODE_LIVE_SBC_FALLBACK_MS = 180_000L;
    private static final long GAME_MODE_EXIT_PROBE_DELAY_MS = 5_000L;
    private static final long GAME_MODE_EXIT_PROBE_FAST_DELAY_MS = 250L;
    private static final long GAME_MODE_EXIT_PROBE_MIN_DELAY_MS = 250L;
    private static final long USER_CODEC_WRITE_QUIET_MS = 20_000L;
    private static final long[] BOOTSTRAP_SCAN_DELAYS_MS = {1_500L, 6_000L, 15_000L};
    private static final int MAX_REPLAY_WRITE_ATTEMPTS = 2;

    private final Context context;
    private final CodecBridgeClient bridge;
    private final PreferenceStore prefs;
    private final A2dpRouteReadiness routeReadiness;
    private final String processName;
    private final ReplayOwnership replayOwnership;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, PendingReplay> pendingReplays = new HashMap<>();
    private final Map<String, Long> replayGenerations = new HashMap<>();
    private final Map<String, Set<Integer>> gameModeActiveTypes = new HashMap<>();
    private final Map<String, Long> gameModeFallbackUntilMs = new HashMap<>();
    private final Map<String, GameModeProbeSchedule> gameModeProbeSchedules = new HashMap<>();
    private final Map<String, Long> userCodecWriteQuietUntilMs = new HashMap<>();

    private BroadcastReceiver receiver;
    private BroadcastReceiver userWriteReceiver;
    private BroadcastReceiver gameModeReceiver;
    private boolean bootstrapActiveQueryFallbackLogged;
    private boolean bootstrapConnectedQueryFallbackLogged;
    private long nextGameModeProbeToken;

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
        this.replayOwnership = new ReplayOwnership(this.context, processName);
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
        gameModeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (TrustedBroadcasts.supportsSenderIdentity()
                        && !TrustedBroadcasts.isTrustedSender(
                        ctx,
                        TrustedBroadcasts.captureSender(this),
                        CodecIpc.MELODY_PKG,
                        CodecIpc.BLUETOOTH_PKG)) {
                    MLog.w("game mode state rejected: untrusted sender");
                    return;
                }
                handleGameModeStateBroadcast(intent);
            }
        };
        IntentFilter gameModeFilter = new IntentFilter(CodecIpc.ACTION_GAME_MODE_STATE);
        // Both Melody's sibling process and com.android.bluetooth publish game-state hints.
        // The OPlus signature permission authenticates them on API 31-33; Android 14+ also
        // verifies the framework-supplied package/UID identity above.
        if (!TrustedBroadcasts.registerExportedReceiver(
                context,
                gameModeReceiver,
                gameModeFilter,
                TrustedBroadcasts.PERMISSION_OPLUS_COMPONENT_SAFE,
                mainHandler)) {
            gameModeReceiver = null;
            MLog.w("game mode receiver registration failed");
        }
        userWriteReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                handleUserCodecWriteBroadcast(intent);
            }
        };
        IntentFilter userWriteFilter = new IntentFilter(ACTION_USER_CODEC_WRITE);
        if (!TrustedBroadcasts.registerNotExportedReceiver(
                context,
                userWriteReceiver,
                userWriteFilter,
                TrustedBroadcasts.PERMISSION_OPLUS_COMPONENT_SAFE,
                mainHandler)) {
            userWriteReceiver = null;
            MLog.w("user codec write receiver registration failed");
        }
        MLog.event("replay.receiver.started",
                "process", processName,
                "replay", hasReplayOwnership());
        scheduleBootstrapScans();
    }

    public void stop() {
        if (receiver == null) return;
        try {
            context.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
        }
        receiver = null;
        if (userWriteReceiver != null) {
            try {
                context.unregisterReceiver(userWriteReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            userWriteReceiver = null;
        }
        if (gameModeReceiver != null) {
            try {
                context.unregisterReceiver(gameModeReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            gameModeReceiver = null;
        }
        replayOwnership.release();
    }

    void onUserCodecWrite(String mac, CodecRequest request, String reason) {
        String key = A2dpRouteReadiness.normalizeMac(mac);
        if (key == null && request != null) {
            key = A2dpRouteReadiness.normalizeMac(request.mac);
        }
        if (key == null) return;
        String safeReason = safeUserCodecWriteReason(reason);
        applyUserCodecWriteQuiet(key, request, safeReason, "local");
        Intent intent = new Intent(ACTION_USER_CODEC_WRITE);
        intent.setPackage(context.getPackageName());
        intent.putExtra(CodecIpc.EXTRA_TOKEN, CodecIpc.TOKEN);
        intent.putExtra(CodecIpc.EXTRA_MAC, key);
        intent.putExtra(EXTRA_USER_CODEC_WRITE_REASON, safeReason);
        intent.putExtra(EXTRA_USER_CODEC_WRITE_ORIGIN, processName);
        try {
            context.sendBroadcast(intent);
        } catch (Throwable t) {
            MLog.w("user codec write broadcast failed", t);
        }
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
        if (!hasReplayOwnership()) {
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
        if (skipReplayForUserCodecWriteQuiet(key, reason)) {
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
                pendingReplays.put(key, new PendingReplay(value, delayMs, reason));
                bumpGenerationLocked(key);
            }
            MLog.event("replay.pending_ready",
                    "mac", A2dpRouteReadiness.redactMac(key),
                    "reason", reason,
                    "delayMs", delayMs);
            return;
        }
        scheduleReplay(key, value, delayMs, reason);
    }

    private void handleDisconnected(String mac) {
        String key = A2dpRouteReadiness.normalizeMac(mac);
        if (key == null) return;
        synchronized (this) {
            pendingReplays.remove(key);
            userCodecWriteQuietUntilMs.remove(key);
            gameModeActiveTypes.remove(key);
            gameModeFallbackUntilMs.remove(key);
            gameModeProbeSchedules.remove(key);
            bumpGenerationLocked(key);
        }
        routeReadiness.markDisconnected(key);
    }

    private void handleUserCodecWriteBroadcast(Intent intent) {
        if (intent == null
                || !ACTION_USER_CODEC_WRITE.equals(intent.getAction())
                || !CodecIpc.TOKEN.equals(intent.getStringExtra(CodecIpc.EXTRA_TOKEN))) {
            return;
        }
        String origin = intent.getStringExtra(EXTRA_USER_CODEC_WRITE_ORIGIN);
        if (processName != null && processName.equals(origin)) return;
        String key = A2dpRouteReadiness.normalizeMac(intent.getStringExtra(CodecIpc.EXTRA_MAC));
        if (key == null) return;
        applyUserCodecWriteQuiet(
                key,
                null,
                safeUserCodecWriteReason(intent.getStringExtra(EXTRA_USER_CODEC_WRITE_REASON)),
                origin != null && !origin.isEmpty() ? "broadcast:" + origin : "broadcast");
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
        if (!hasReplayOwnership()) return;
        PendingReplay pending;
        Long fallbackUntil;
        synchronized (this) {
            pending = pendingReplays.remove(key);
            fallbackUntil = gameModeFallbackExitProbeUntilLocked(key);
        }
        if (fallbackUntil != null) {
            scheduleGameModeFallbackExitProbe(
                    key, fallbackUntil, GAME_MODE_EXIT_PROBE_FAST_DELAY_MS);
        }
        if (pending != null) {
            scheduleReplay(key, pending.value, pending.delayMs, pending.activeReadyReason());
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
        boolean probeAfterInactive = false;
        long probeExpectedUntil = 0L;
        boolean suppressed;
        int activeTypeCount;
        synchronized (this) {
            boolean wasSuppressed = isGameModeSuppressedLocked(key);
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
                    scheduleGameModeFallbackExitProbe(
                            key, until, GAME_MODE_EXIT_PROBE_DELAY_MS);
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
                if (removedActiveType) {
                    bumpGenerationLocked(key);
                } else if (wasSuppressed) {
                    Long fallbackUntil = gameModeFallbackExitProbeUntilLocked(key);
                    if (fallbackUntil != null) {
                        probeAfterInactive = true;
                        probeExpectedUntil = fallbackUntil;
                    }
                }
            }
            suppressed = isGameModeSuppressedLocked(key);
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
        } else if (probeAfterInactive) {
            scheduleGameModeFallbackExitProbe(
                    key, probeExpectedUntil, GAME_MODE_EXIT_PROBE_FAST_DELAY_MS);
        }
    }

    private void scheduleGameModeFallbackExpiry(String key, long expectedUntil, long delayMs) {
        mainHandler.postDelayed(() -> expireGameModeFallback(key, expectedUntil),
                Math.max(1_000L, delayMs + 250L));
    }

    private void expireGameModeFallback(String key, long expectedUntil) {
        synchronized (this) {
            Long currentUntil = gameModeFallbackUntilMs.get(key);
            if (currentUntil == null || currentUntil != expectedUntil) return;
            if (hasActiveGameModeTypesLocked(key)) return;
        }
        MLog.event("game.mode.fallback.expiry_probe",
                "mac", A2dpRouteReadiness.redactMac(key));
        scheduleGameModeFallbackExitProbe(key, expectedUntil, 1L);
    }

    private void scheduleGameModeFallbackExitProbe(
            String key,
            long expectedUntil,
            long delayMs) {
        if (!hasReplayOwnership()) return;
        long safeDelayMs = Math.max(GAME_MODE_EXIT_PROBE_MIN_DELAY_MS, delayMs);
        long dueAtMs = System.currentTimeMillis() + safeDelayMs;
        long token;
        synchronized (this) {
            GameModeProbeSchedule current = gameModeProbeSchedules.get(key);
            if (current != null
                    && current.expectedUntil == expectedUntil
                    && current.dueAtMs <= dueAtMs) {
                return;
            }
            token = ++nextGameModeProbeToken;
            gameModeProbeSchedules.put(
                    key, new GameModeProbeSchedule(expectedUntil, dueAtMs, token));
        }
        mainHandler.postDelayed(() -> runGameModeFallbackExitProbe(key, expectedUntil, token),
                safeDelayMs);
    }

    private void runGameModeFallbackExitProbe(String key, long expectedUntil, long token) {
        synchronized (this) {
            GameModeProbeSchedule scheduled = gameModeProbeSchedules.get(key);
            if (scheduled == null
                    || scheduled.token != token
                    || scheduled.expectedUntil != expectedUntil) {
                return;
            }
            gameModeProbeSchedules.remove(key);
            Long currentUntil = gameModeFallbackUntilMs.get(key);
            if (currentUntil == null || currentUntil != expectedUntil) return;
            if (hasActiveGameModeTypesLocked(key)) return;
        }
        Thread worker = new Thread(() -> probeGameModeFallbackExit(key, expectedUntil),
                "MelodyCodecLsp-gameModeProbe");
        worker.setDaemon(true);
        worker.start();
    }

    private void probeGameModeFallbackExit(String key, long expectedUntil) {
        CodecSnapshot live = bridge.getStatus(key);
        boolean stillGameMode = isGameModeSbcSnapshot(live);
        boolean replayAfterProbe = false;
        long nextExpectedUntil = expectedUntil;
        synchronized (this) {
            Long currentUntil = gameModeFallbackUntilMs.get(key);
            if (currentUntil == null || currentUntil != expectedUntil) return;
            if (hasActiveGameModeTypesLocked(key)) return;
            if (stillGameMode) {
                long now = System.currentTimeMillis();
                if (currentUntil <= now + GAME_MODE_EXIT_PROBE_DELAY_MS) {
                    nextExpectedUntil = now + GAME_MODE_LIVE_SBC_FALLBACK_MS;
                    gameModeFallbackUntilMs.put(key, nextExpectedUntil);
                    scheduleGameModeFallbackExpiry(
                            key, nextExpectedUntil, GAME_MODE_LIVE_SBC_FALLBACK_MS);
                }
            } else if (live != null) {
                gameModeFallbackUntilMs.remove(key);
                bumpGenerationLocked(key);
                replayAfterProbe = true;
            }
        }
        if (stillGameMode) {
            MLog.event("game.mode.probe.active",
                    "mac", A2dpRouteReadiness.redactMac(key),
                    "source", "live.sbc_s2_" + live.activeCodecSpecific2);
            scheduleGameModeFallbackExitProbe(
                    key, nextExpectedUntil, GAME_MODE_EXIT_PROBE_DELAY_MS);
            return;
        }
        if (live == null) {
            MLog.event("game.mode.probe.unavailable",
                    "mac", A2dpRouteReadiness.redactMac(key));
            scheduleGameModeFallbackExitProbe(
                    key, expectedUntil, GAME_MODE_EXIT_PROBE_DELAY_MS);
            return;
        }
        if (replayAfterProbe) {
            MLog.event("replay.suppress.game_probe_exit",
                    "mac", A2dpRouteReadiness.redactMac(key),
                    "live", live);
            maybeReplayConnected(key, GAME_MODE_EXIT_REPLAY_DELAY_MS, "game_mode_probe_exit");
        }
    }

    private void scheduleBootstrapScans() {
        if (!hasReplayOwnership()) return;
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
                    "live_specific1", live.activeCodecSpecific1,
                    "live_specific2", live.activeCodecSpecific2);
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
    @android.annotation.SuppressLint("MissingPermission")
    private Map<String, Boolean> queryCurrentA2dpDevices() {
        Map<String, Boolean> devices = new HashMap<>();
        BluetoothAdapter adapter = resolveAdapter();
        if (adapter != null) {
            try {
                Method getActiveDevices = adapter.getClass().getMethod("getActiveDevices", int.class);
                Object activeDevices = getActiveDevices.invoke(adapter, BluetoothProfile.A2DP);
                addDeviceCollection(devices, activeDevices, true);
            } catch (Throwable t) {
                logBootstrapActiveQueryFallback(t);
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
            logBootstrapConnectedQueryFallback(t);
        }
        return devices;
    }

    private void logBootstrapActiveQueryFallback(Throwable t) {
        synchronized (this) {
            if (bootstrapActiveQueryFallbackLogged) return;
            bootstrapActiveQueryFallbackLogged = true;
        }
        MLog.event("replay.bootstrap.active_query_fallback",
                "reason", bootstrapQueryFallbackReason(t));
    }

    private void logBootstrapConnectedQueryFallback(Throwable t) {
        synchronized (this) {
            if (bootstrapConnectedQueryFallbackLogged) return;
            bootstrapConnectedQueryFallbackLogged = true;
        }
        MLog.event("replay.bootstrap.connected_query_fallback",
                "reason", bootstrapQueryFallbackReason(t));
    }

    private static String bootstrapQueryFallbackReason(Throwable t) {
        if (containsSecurityException(t)) return "permission";
        return t != null ? t.getClass().getSimpleName() : "unknown";
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
        if (!hasReplayOwnership()) return;
        if (skipReplayForUserCodecWriteQuiet(mac, reason)) {
            return;
        }
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
            if (skipReplayForUserCodecWriteQuiet(mac, reason)) {
                return;
            }
            if (isGameModeSuppressed(mac)) {
                suppressReplayForGameMode(mac, reason);
                return;
            }
            if (!routeReadiness.isReadyOrUnknown(mac)) {
                synchronized (ConnectionStateReplayer.this) {
                    pendingReplays.put(mac, new PendingReplay(stored, delayMs, reason));
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
            if (isUserCodecWriteQuiet(mac)) {
                MLog.event("replay.stale.user_write",
                        "mac", A2dpRouteReadiness.redactMac(mac),
                        "generation", generation,
                        "stage", "worker_start");
                return;
            }
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
        if (!hasReplayOwnership()) return;
        if (!isReplayStillCurrent(mac, stored, generation)) return;
        if (isGameModeSuppressed(mac)) {
            suppressReplayForGameMode(mac, "before_read");
            return;
        }
        if (!routeReadiness.isReadyOrUnknown(mac)) {
            synchronized (this) {
                pendingReplays.put(mac,
                        new PendingReplay(stored, REPLAY_DELAY_MS, "worker_not_ready"));
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

        int replayCodecType = replayCodecType(live, stored.codecType);
        if (stored.codecType >= 0
                && replayCodecType < 0
                && !sameCodecFamily(live.activeCodecType, stored.codecType)) {
            MLog.event("replay.skip.codec_unavailable",
                    "mac", A2dpRouteReadiness.redactMac(mac),
                    "stored_codec", stored.codecType,
                    "active_codec", live.activeCodecType);
            return;
        }
        boolean codecSelectable = replayCodecType >= 0
                && live.activeCodecType != replayCodecType;
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
                    .codecSpecific1(0L)
                    .codecSpecific2(0L)
                    .codecSpecific3(0L)
                    .codecSpecific4(0L)
                    .bitsPerSample(firstBit(selectableIntValue(
                            live.selectableCodecBitsPerSample, capIndex)))
                    .channelMode(firstBit(selectableIntValue(
                            live.selectableCodecChannelModes, capIndex)));
            if (!sampleRateSelectable) {
                int targetRate = firstBit(selectableIntValue(
                        live.selectableCodecSampleRates, capIndex));
                if (targetRate != 0) builder.sampleRate(targetRate);
            }
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
        if (isUserCodecWriteQuiet(mac)) {
            MLog.event("replay.stale.user_write",
                    "mac", A2dpRouteReadiness.redactMac(mac),
                    "generation", generation,
                    "stage", "current_check");
            return false;
        }
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

    private void applyUserCodecWriteQuiet(
            String key,
            CodecRequest request,
            String reason,
            String source) {
        long until = System.currentTimeMillis() + USER_CODEC_WRITE_QUIET_MS;
        synchronized (this) {
            pendingReplays.remove(key);
            userCodecWriteQuietUntilMs.put(key, until);
            bumpGenerationLocked(key);
        }
        MLog.event("replay.cancel.user_write",
                "mac", A2dpRouteReadiness.redactMac(key),
                "source", source,
                "reason", reason,
                "quietMs", USER_CODEC_WRITE_QUIET_MS,
                "request", String.valueOf(request));
    }

    private boolean skipReplayForUserCodecWriteQuiet(String mac, String reason) {
        String key = A2dpRouteReadiness.normalizeMac(mac);
        if (key == null) return false;
        long remainingMs = userCodecWriteQuietRemainingMs(key);
        if (remainingMs <= 0L) return false;
        MLog.event("replay.skip.user_write_quiet",
                "mac", A2dpRouteReadiness.redactMac(key),
                "reason", reason,
                "remainingMs", remainingMs);
        return true;
    }

    private boolean isUserCodecWriteQuiet(String mac) {
        String key = A2dpRouteReadiness.normalizeMac(mac);
        return key != null && userCodecWriteQuietRemainingMs(key) > 0L;
    }

    private long userCodecWriteQuietRemainingMs(String key) {
        long now = System.currentTimeMillis();
        synchronized (this) {
            Long until = userCodecWriteQuietUntilMs.get(key);
            if (until == null) return 0L;
            long remainingMs = until - now;
            if (remainingMs <= 0L) {
                userCodecWriteQuietUntilMs.remove(key);
                return 0L;
            }
            return remainingMs;
        }
    }

    private static String safeUserCodecWriteReason(String reason) {
        if (reason == null || reason.isEmpty()) return "codec_write";
        return reason.replaceAll("\\s+", "_");
    }

    private boolean isGameModeSuppressed(String mac) {
        String key = A2dpRouteReadiness.normalizeMac(mac);
        if (key == null) return false;
        synchronized (this) {
            return isGameModeSuppressedLocked(key);
        }
    }

    private boolean hasReplayOwnership() {
        return replayOwnership.acquireIfAvailable();
    }

    private boolean isGameModeSuppressedLocked(String key) {
        if (hasActiveGameModeTypesLocked(key)) return true;
        // Do not clear the Bluetooth-observed fallback just because its TTL elapsed.
        // The timeout only means "start probing"; clearing it here would let an unrelated
        // replay path race ahead before we have confirmed that the live codec is no longer
        // the official low-latency SBC marker.
        return gameModeFallbackUntilMs.containsKey(key);
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

    private Long gameModeFallbackExitProbeUntilLocked(String key) {
        if (hasActiveGameModeTypesLocked(key)) return null;
        return gameModeFallbackUntilMs.get(key);
    }

    private static final class PendingReplay {
        final PreferenceStore.RememberedValue value;
        final long delayMs;
        final String reason;

        PendingReplay(PreferenceStore.RememberedValue value, long delayMs, String reason) {
            this.value = value;
            this.delayMs = delayMs;
            this.reason = reason != null && !reason.isEmpty() ? reason : "pending_ready";
        }

        String activeReadyReason() {
            return "active_ready/" + reason;
        }
    }

    private static final class GameModeProbeSchedule {
        final long expectedUntil;
        final long dueAtMs;
        final long token;

        GameModeProbeSchedule(long expectedUntil, long dueAtMs, long token) {
            this.expectedUntil = expectedUntil;
            this.dueAtMs = dueAtMs;
            this.token = token;
        }
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

    static boolean matchesStoredValue(
            CodecSnapshot live,
            PreferenceStore.RememberedValue stored) {
        if (live == null || stored == null) return false;
        if (stored.codecType >= 0 && live.activeCodecType != stored.codecType) {
            if (!sameCodecFamily(live.activeCodecType, stored.codecType)
                    || isCodecExplicitlySelectable(live, stored.codecType)) {
                return false;
            }
        }
        if (stored.sampleRate > 0 && live.activeSampleRate != stored.sampleRate) {
            return false;
        }
        // SBC/AAC do not expose a meaningful playback-quality word, but their negotiated sample
        // rate is still user-selectable and part of the per-device memory contract.
        if (isStandardCodec(stored.codecType)) return true;
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

    static int replayCodecType(CodecSnapshot live, int storedCodecType) {
        if (live == null || storedCodecType < 0) return -1;
        if (live.activeCodecType == storedCodecType) return live.activeCodecType;
        int index = selectableCodecIndex(live, storedCodecType);
        if (index >= 0) return live.selectableCodecTypes[index];
        if (sameCodecFamily(live.activeCodecType, storedCodecType)) {
            return live.activeCodecType;
        }
        if (live.selectableCodecTypes == null || live.selectableCodecTypes.length == 0) {
            // Some OPlus stacks temporarily expose no capability array immediately after
            // reconnect. Unknown capability is distinct from an explicit non-match, including
            // for mandatory SBC and a previously negotiated AAC selection.
            return storedCodecType;
        }
        return -1;
    }

    private static boolean isCodecExplicitlySelectable(CodecSnapshot live, int codecType) {
        if (live == null || live.selectableCodecTypes == null) return false;
        for (int selectable : live.selectableCodecTypes) {
            if (selectable == codecType) return true;
        }
        return false;
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
        if (value < 0) return false;
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

    private static boolean isPreferredReplayOwnerProcess(Context context, String processName) {
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

    /**
     * Cross-process single-owner lease. Melody creates a controller in both its main and
     * {@code :fg} processes; a kernel file lock prevents duplicate writes while allowing the
     * surviving process to take over automatically after the former owner is killed.
     */
    private static final class ReplayOwnership {
        private final File lockFile;
        private final String processName;
        private final boolean fallbackOwner;
        private FileOutputStream stream;
        private FileChannel channel;
        private FileLock lock;
        private boolean fileLockUnavailable;
        private boolean failureLogged;

        ReplayOwnership(Context context, String processName) {
            File filesDir = context != null ? context.getFilesDir() : null;
            this.lockFile = filesDir != null ? new File(filesDir, "codec-replay-owner.lock") : null;
            this.processName = processName;
            this.fallbackOwner = isPreferredReplayOwnerProcess(context, processName);
        }

        synchronized boolean acquireIfAvailable() {
            if (lock != null && lock.isValid()) return true;
            if (fileLockUnavailable || lockFile == null) return fallbackOwner;
            closeQuietly();
            try {
                stream = new FileOutputStream(lockFile, true);
                channel = stream.getChannel();
                lock = channel.tryLock();
                if (lock == null) {
                    closeQuietly();
                    return false;
                }
                MLog.event("replay.owner.acquired", "process", processName);
                return true;
            } catch (OverlappingFileLockException e) {
                closeQuietly();
                return false;
            } catch (Throwable t) {
                closeQuietly();
                fileLockUnavailable = true;
                if (!failureLogged) {
                    failureLogged = true;
                    MLog.w("replay owner file lock unavailable; using process fallback", t);
                }
                return fallbackOwner;
            }
        }

        synchronized void release() {
            closeQuietly();
        }

        private void closeQuietly() {
            if (lock != null) {
                try {
                    lock.release();
                } catch (Throwable ignored) {
                }
                lock = null;
            }
            if (channel != null) {
                try {
                    channel.close();
                } catch (Throwable ignored) {
                }
                channel = null;
            }
            if (stream != null) {
                try {
                    stream.close();
                } catch (Throwable ignored) {
                }
                stream = null;
            }
        }
    }
}
