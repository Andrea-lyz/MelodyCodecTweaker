package xyz.melodylsp.codec.system;

import java.util.HashMap;
import java.util.Map;

/**
 * Learns whether each headset can sustain the next LHDC quality rung.
 *
 * <p>This class deliberately has no Android dependencies. All callbacks are serialized by the
 * Bluetooth main looper, while the per-MAC state survives ordinary headset disconnects and encoder
 * handle replacement for as long as the Bluetooth process remains alive.</p>
 */
final class LhdcLinkHealthController {

    static final int EVENT_QUICK_FAILURE = 1;
    static final int EVENT_UPGRADE_STABLE = 2;

    static final long QUICK_FAILURE_HISTORY_MS = 5 * 60_000L;
    static final int[] REQUIRED_HEALTHY_WINDOWS = {3, 4, 5};
    static final long[] REQUIRED_QUIET_MS = {15_000L, 30_000L, 45_000L};
    static final long MIN_BQR_INTERVAL_MS = 3_000L;
    static final long MAX_BQR_INTERVAL_MS = 15_000L;
    static final int MAX_UNUSED_AFH_CHANNELS = 49;
    static final double MAX_RETRANSMISSIONS_PER_SECOND = 60.0;
    static final double MAX_NO_RX_PER_SECOND = 60.0;
    static final int MIN_PROBE_STABLE_WINDOWS = 3;
    static final long MIN_PROBE_COOLDOWN_MS = 10_000L;
    static final long MIN_RECONNECT_GAP_MS = 5_000L;
    static final long LOCK_DECAY_MS = 10 * 60_000L;

    interface Listener {
        void onProbeCeilingChanged(String mac, int ceilingKbps, String reason);
    }

    static final class BqrSample {
        final int unusedAfhChannels;
        final int unidealAfhChannels;
        final long retransmissionCount;
        final long noRxCount;
        final long nakCount;
        final int rssi;
        final int snr;
        final long overflowCount;
        final long underflowCount;

        BqrSample(
                int unusedAfhChannels,
                int unidealAfhChannels,
                long retransmissionCount,
                long noRxCount,
                long nakCount,
                int rssi,
                int snr,
                long overflowCount,
                long underflowCount) {
            this.unusedAfhChannels = unusedAfhChannels;
            this.unidealAfhChannels = unidealAfhChannels;
            this.retransmissionCount = retransmissionCount;
            this.noRxCount = noRxCount;
            this.nakCount = nakCount;
            this.rssi = rssi;
            this.snr = snr;
            this.overflowCount = overflowCount;
            this.underflowCount = underflowCount;
        }
    }

    static final class Snapshot {
        final int ceilingKbps;
        final int healthyBqrWindows;
        final int usableAfhChannels;
        final double retransmissionsPerSecond;
        final double noRxPerSecond;
        final boolean boundary500To900Locked;
        final boolean boundary900To1000Locked;
        final int requiredHealthyBqrWindows;
        final long requiredQuietMs;

        Snapshot(
                int ceilingKbps,
                int healthyBqrWindows,
                int usableAfhChannels,
                double retransmissionsPerSecond,
                double noRxPerSecond,
                boolean boundary500To900Locked,
                boolean boundary900To1000Locked,
                int requiredHealthyBqrWindows,
                long requiredQuietMs) {
            this.ceilingKbps = ceilingKbps;
            this.healthyBqrWindows = healthyBqrWindows;
            this.usableAfhChannels = usableAfhChannels;
            this.retransmissionsPerSecond = retransmissionsPerSecond;
            this.noRxPerSecond = noRxPerSecond;
            this.boundary500To900Locked = boundary500To900Locked;
            this.boundary900To1000Locked = boundary900To1000Locked;
            this.requiredHealthyBqrWindows = requiredHealthyBqrWindows;
            this.requiredQuietMs = requiredQuietMs;
        }
    }

    private static final class BoundaryState {
        final int fromKbps;
        final int toKbps;
        int quickFailureCount;
        long firstQuickFailureMs;
        boolean locked;
        boolean probeInFlight;
        boolean recoveryAttempt;
        int evidenceTier;

        BoundaryState(int fromKbps, int toKbps) {
            this.fromKbps = fromKbps;
            this.toKbps = toKbps;
        }
    }

    private static final class DeviceState {
        final BoundaryState to900 = new BoundaryState(500, 900);
        final BoundaryState to1000 = new BoundaryState(900, 1000);
        long lastBqrMs;
        int healthyBqrWindows;
        int usableAfhChannels;
        double retransmissionsPerSecond = Double.NaN;
        double noRxPerSecond = Double.NaN;
        long lastCongestionMs;
        long lowQueueSinceMs;
        int lastPublishedCeiling = -1;
        long lastProbeOpenedMs;
        int probeStableBqrWindows;
        long lastRecoveryActionMs;
        long lastSeenStreamingMs;
    }

    private final Map<String, DeviceState> devices = new HashMap<>();
    private final Listener listener;
    private String activeMac;

    LhdcLinkHealthController(Listener listener) {
        this.listener = listener;
    }

    synchronized void activate(String mac, long nowMs) {
        if (mac == null || mac.isEmpty()) return;
        boolean sameMac = mac.equals(activeMac);
        activeMac = mac;
        DeviceState state = stateFor(mac);
        // Treat every A2DP re-attach of the active device — even when it is
        // the same MAC within the Bluetooth process lifetime — as the start
        // of a fresh LHDC learning window once a meaningful streaming gap
        // has passed. The previous session's locks only reflect a session
        // that is no longer in progress. Switching to a different MAC does
        // NOT clear the per-MAC learning for the device we are leaving.
        boolean stale = sameMac
                && state.lastSeenStreamingMs != 0L
                && nowMs - state.lastSeenStreamingMs >= MIN_RECONNECT_GAP_MS;
        if (stale) {
            resetLearnedBoundary(state);
        }
        state.lastRecoveryActionMs = nowMs;
        state.lastSeenStreamingMs = nowMs;
        // Always announce the current ceiling once on activate so listeners
        // get a fresh snapshot of the device we just attached to.
        if (!sameMac) state.lastPublishedCeiling = -1;
        maybeOpenRecoveryProbe(mac, state, nowMs);
        publishCeiling(mac, state, "device_active");
    }

    private static void resetLearnedBoundary(DeviceState state) {
        clearBoundary(state.to900);
        clearBoundary(state.to1000);
        state.lastCongestionMs = 0L;
        state.lowQueueSinceMs = 0L;
        state.lastProbeOpenedMs = 0L;
        state.probeStableBqrWindows = 0;
        state.lastRecoveryActionMs = 0L;
        state.lastPublishedCeiling = -1;
    }

    synchronized String activeMac() {
        return activeMac;
    }

    synchronized void onBqrSample(String mac, BqrSample sample, long nowMs) {
        onBqrSample(mac, sample, nowMs, true);
    }

    synchronized void onBqrSample(
            String mac, BqrSample sample, long nowMs, boolean streaming) {
        if (mac == null || sample == null) return;
        DeviceState state = stateFor(mac);
        long intervalMs = state.lastBqrMs == 0L ? 0L : nowMs - state.lastBqrMs;
        state.lastBqrMs = nowMs;
        state.usableAfhChannels = Math.max(0, 79 - sample.unusedAfhChannels);
        if (intervalMs >= MIN_BQR_INTERVAL_MS && intervalMs <= MAX_BQR_INTERVAL_MS) {
            double seconds = intervalMs / 1000.0;
            state.retransmissionsPerSecond = sample.retransmissionCount / seconds;
            state.noRxPerSecond = sample.noRxCount / seconds;
            boolean healthy = streaming
                    && sample.unusedAfhChannels <= MAX_UNUSED_AFH_CHANNELS
                    && state.retransmissionsPerSecond <= MAX_RETRANSMISSIONS_PER_SECOND
                    && state.noRxPerSecond <= MAX_NO_RX_PER_SECOND;
            state.healthyBqrWindows = healthy ? state.healthyBqrWindows + 1 : 0;
            applyEvidenceTierDecay(state, nowMs);
            BoundaryState inFlight = state.to900.probeInFlight ? state.to900
                    : state.to1000.probeInFlight ? state.to1000 : null;
            if (inFlight != null) {
                if (healthy) {
                    state.probeStableBqrWindows++;
                } else {
                    state.probeStableBqrWindows = 0;
                }
                if (state.probeStableBqrWindows >= MIN_PROBE_STABLE_WINDOWS
                        && cancelRecoveryProbes(state, false)) {
                    state.lastProbeOpenedMs = 0L;
                    publishCeiling(mac, state, "probe_stable");
                } else if (state.probeStableBqrWindows == 0
                        && cancelRecoveryProbes(state, false)) {
                    publishCeiling(mac, state, "probe_health_lost");
                }
            } else if (state.healthyBqrWindows == 0
                    && cancelRecoveryProbes(state, false)) {
                publishCeiling(mac, state, "probe_health_lost");
            }
        } else {
            state.retransmissionsPerSecond = Double.NaN;
            state.noRxPerSecond = Double.NaN;
            state.healthyBqrWindows = 0;
            state.probeStableBqrWindows = 0;
            state.lowQueueSinceMs = 0L;
        }
        if (streaming) state.lastSeenStreamingMs = nowMs;
        if (mac.equals(activeMac)) {
            if (!streaming) {
                state.lowQueueSinceMs = 0L;
                if (cancelRecoveryProbes(state, true)) {
                    publishCeiling(mac, state, "probe_stream_idle");
                }
                return;
            }
            maybeOpenRecoveryProbe(mac, state, nowMs);
        }
    }

    synchronized void onQueueSample(String mac, int length, int capacity, long nowMs) {
        if (mac == null || capacity <= 0 || length < 0) return;
        DeviceState state = stateFor(mac);
        long occupancy = (long) length * 100L;
        if (occupancy <= (long) capacity * 25L) {
            if (state.lowQueueSinceMs == 0L) state.lowQueueSinceMs = nowMs;
        } else {
            state.lowQueueSinceMs = 0L;
        }
        if (occupancy >= (long) capacity * 90L) {
            noteCongestion(state, nowMs);
            if (cancelRecoveryProbes(state, false)) {
                publishCeiling(mac, state, "probe_queue_congested");
            }
        }
        if (mac.equals(activeMac)) {
            maybeOpenRecoveryProbe(mac, state, nowMs);
        }
    }

    synchronized void onCongestion(String mac, long nowMs) {
        if (mac == null) return;
        DeviceState state = stateFor(mac);
        noteCongestion(state, nowMs);
        if (cancelRecoveryProbes(state, false)) {
            publishCeiling(mac, state, "probe_choppy");
        }
    }

    synchronized void onGovernorEvent(
            String mac, int event, int fromKbps, int toKbps, long nowMs) {
        if (mac == null) return;
        DeviceState state = stateFor(mac);
        BoundaryState boundary = boundaryFor(state, fromKbps, toKbps);
        if (boundary == null) return;
        if (event == EVENT_QUICK_FAILURE) {
            state.lastRecoveryActionMs = nowMs;
            noteCongestion(state, nowMs);
            if (boundary.recoveryAttempt) {
                boundary.probeInFlight = false;
                boundary.recoveryAttempt = false;
                boundary.locked = true;
                boundary.evidenceTier = Math.min(2, boundary.evidenceTier + 1);
            } else {
                if (boundary.firstQuickFailureMs == 0L
                        || nowMs - boundary.firstQuickFailureMs > QUICK_FAILURE_HISTORY_MS) {
                    boundary.firstQuickFailureMs = nowMs;
                    boundary.quickFailureCount = 1;
                } else {
                    boundary.quickFailureCount++;
                }
                if (boundary.quickFailureCount >= 2) {
                    boundary.locked = true;
                    boundary.evidenceTier = 0;
                }
            }
            publishCeiling(mac, state, boundary.locked ? "boundary_locked" : "quick_failure");
        } else if (event == EVENT_UPGRADE_STABLE) {
            clearBoundary(boundary);
            publishCeiling(mac, state, "boundary_stable");
        }
    }

    synchronized Snapshot snapshot(String mac, long nowMs) {
        DeviceState state = devices.get(mac);
        if (state == null) {
            return new Snapshot(1000, 0, 0, Double.NaN, Double.NaN,
                    false, false, 0, 0L);
        }
        BoundaryState blocked = firstBlockedBoundary(state);
        return new Snapshot(
                effectiveCeiling(state),
                state.healthyBqrWindows,
                state.usableAfhChannels,
                state.retransmissionsPerSecond,
                state.noRxPerSecond,
                state.to900.locked && !state.to900.probeInFlight,
                state.to1000.locked && !state.to1000.probeInFlight,
                blocked == null ? 0 : healthyWindowsForTier(blocked.evidenceTier),
                blocked == null ? 0L : quietMsForTier(blocked.evidenceTier));
    }

    private DeviceState stateFor(String mac) {
        DeviceState state = devices.get(mac);
        if (state == null) {
            state = new DeviceState();
            devices.put(mac, state);
        }
        return state;
    }

    private void maybeOpenRecoveryProbe(String mac, DeviceState state, long nowMs) {
        BoundaryState boundary = firstBlockedBoundary(state);
        if (boundary == null) return;
        long quietMs = quietMsForTier(boundary.evidenceTier);
        if (state.healthyBqrWindows < healthyWindowsForTier(boundary.evidenceTier)
                || state.lowQueueSinceMs == 0L
                || nowMs - state.lowQueueSinceMs < quietMs
                || (state.lastCongestionMs != 0L
                && nowMs - state.lastCongestionMs < quietMs)) {
            return;
        }
        if (state.lastProbeOpenedMs != 0L
                && nowMs - state.lastProbeOpenedMs < MIN_PROBE_COOLDOWN_MS) {
            return;
        }
        boundary.probeInFlight = true;
        boundary.recoveryAttempt = true;
        state.lastProbeOpenedMs = nowMs;
        state.probeStableBqrWindows = 0;
        publishCeiling(mac, state, "healthy_recovery_probe");
    }

    private static void applyEvidenceTierDecay(DeviceState state, long nowMs) {
        if (state.lastRecoveryActionMs == 0L) return;
        long elapsed = nowMs - state.lastRecoveryActionMs;
        if (elapsed < LOCK_DECAY_MS) return;
        int steps = (int) (elapsed / LOCK_DECAY_MS);
        if (steps <= 0) return;
        state.lastRecoveryActionMs = nowMs;
        decayBoundary(state.to900, steps);
        decayBoundary(state.to1000, steps);
    }

    private static void decayBoundary(BoundaryState boundary, int steps) {
        if (!boundary.locked) return;
        int target = boundary.evidenceTier - steps;
        if (target > 0) {
            boundary.evidenceTier = target;
            return;
        }
        // Reaching tier 0 means we have given up the prior evidence and
        // start over with the base threshold; unlock so the controller can
        // re-evaluate the boundary against fresh data.
        boundary.evidenceTier = 0;
        boundary.locked = false;
        boundary.quickFailureCount = 0;
        boundary.firstQuickFailureMs = 0L;
        boundary.probeInFlight = false;
        boundary.recoveryAttempt = false;
    }

    private void publishCeiling(String mac, DeviceState state, String reason) {
        if (!mac.equals(activeMac)) return;
        int ceiling = effectiveCeiling(state);
        if (state.lastPublishedCeiling == ceiling) return;
        state.lastPublishedCeiling = ceiling;
        if (listener != null) listener.onProbeCeilingChanged(mac, ceiling, reason);
    }

    private static int effectiveCeiling(DeviceState state) {
        if (state.to900.locked) return state.to900.probeInFlight ? 900 : 500;
        if (state.to1000.locked) return state.to1000.probeInFlight ? 1000 : 900;
        return 1000;
    }

    private static BoundaryState firstBlockedBoundary(DeviceState state) {
        if (state.to900.locked) return state.to900.probeInFlight ? null : state.to900;
        if (state.to1000.locked) return state.to1000.probeInFlight ? null : state.to1000;
        return null;
    }

    private static BoundaryState boundaryFor(DeviceState state, int fromKbps, int toKbps) {
        if (fromKbps == 500 && toKbps == 900) return state.to900;
        if (fromKbps == 900 && toKbps == 1000) return state.to1000;
        return null;
    }

    private static void noteCongestion(DeviceState state, long nowMs) {
        state.lastCongestionMs = nowMs;
        state.lowQueueSinceMs = 0L;
        state.healthyBqrWindows = 0;
    }

    private static void clearBoundary(BoundaryState boundary) {
        boundary.quickFailureCount = 0;
        boundary.firstQuickFailureMs = 0L;
        boundary.locked = false;
        boundary.probeInFlight = false;
        boundary.recoveryAttempt = false;
        boundary.evidenceTier = 0;
    }

    private static boolean cancelRecoveryProbes(DeviceState state, boolean clearAttempt) {
        boolean changed = state.to900.probeInFlight || state.to1000.probeInFlight;
        state.to900.probeInFlight = false;
        state.to1000.probeInFlight = false;
        if (clearAttempt) {
            state.to900.recoveryAttempt = false;
            state.to1000.recoveryAttempt = false;
        }
        return changed;
    }

    private static int healthyWindowsForTier(int tier) {
        return REQUIRED_HEALTHY_WINDOWS[Math.max(0,
                Math.min(tier, REQUIRED_HEALTHY_WINDOWS.length - 1))];
    }

    private static long quietMsForTier(int tier) {
        return REQUIRED_QUIET_MS[Math.max(0,
                Math.min(tier, REQUIRED_QUIET_MS.length - 1))];
    }
}
