package xyz.melodylsp.codec.system;

import java.util.HashMap;
import java.util.Map;

import xyz.melodylsp.codec.BuildConfig;

/**
 * Learns whether each headset can sustain the next LHDC quality rung.
 *
 * <p>This class deliberately has no Android dependencies. All callbacks are serialized by the
 * Bluetooth main looper. Learned per-MAC boundary locks survive encoder replacement within one
 * A2DP session, while an explicit disconnect or codec/policy exit starts fresh learning.</p>
 */
final class LhdcLinkHealthController {

    static final int EVENT_QUICK_FAILURE = 1;
    static final int EVENT_UPGRADE_STABLE = 2;
    static final int EVENT_UPGRADE_APPLIED = 3;
    /** Native getter confirmed the peer cannot sustain the requested rung (e.g. actual 900 for target 1000). */
    static final int EVENT_PEER_CEILING_DETECTED = 4;
    /** Native set_rate actually wrote a new target (transition evidence for diagnostics). */
    static final int EVENT_TRANSITION_APPLIED = 5;

    static final long QUICK_FAILURE_HISTORY_MS = 5 * 60_000L;
    static final int[] REQUIRED_HEALTHY_WINDOWS = {3, 4, 5};
    static final long[] REQUIRED_QUIET_MS = {15_000L, 30_000L, 45_000L};
    static final long MIN_BQR_INTERVAL_MS = 3_000L;
    static final long MAX_BQR_INTERVAL_MS = 15_000L;
    static final int REQUIRED_SHADOW_UNSTABLE_WINDOWS = 2;
    static final long SHADOW_CANDIDATE_COOLDOWN_MS = 15_000L;
    static final String CHOPPY_CAPABILITY_UNKNOWN = "UNKNOWN";
    static final String CHOPPY_CAPABILITY_OBSERVED = "OBSERVED";

    // A recovery probe is admitted conservatively, then maintained with wider limits. This
    // hysteresis prevents one borderline six-second BQR window from bouncing 900 -> 500.
    static final int MAX_UNUSED_AFH_CHANNELS = 49;
    static final double MAX_RETRANSMISSIONS_PER_SECOND = 60.0;
    static final double MAX_NO_RX_PER_SECOND = 60.0;
    static final int MAX_PROBE_UNUSED_AFH_CHANNELS = 59;
    static final double MAX_PROBE_RETRANSMISSIONS_PER_SECOND = 90.0;
    static final double MAX_PROBE_NO_RX_PER_SECOND = 90.0;
    static final int SEVERE_UNUSED_AFH_CHANNELS = 69;
    static final double SEVERE_RETRANSMISSIONS_PER_SECOND = 120.0;
    static final double SEVERE_NO_RX_PER_SECOND = 120.0;
    static final int MAX_CONSECUTIVE_PROBE_BAD_WINDOWS = 2;

    // Native needs 15 seconds of low queue before it can apply 500 -> 900. Never revoke an
    // ordinary BQR probe before that native window has had a chance to complete.
    static final long MIN_PROBE_OPEN_MS = 15_000L;
    static final long MIN_PROBE_COOLDOWN_MS = 10_000L;
    static final long CRITICAL_QUEUE_HOLD_MS = 300L;
    static final long EVIDENCE_TIER_DECAY_MS = 10 * 60_000L;

    // Experimental BQR fallback downgrade (validation build, Issue-8 Buds Ace 3): the headset
    // never reports remote choppy, so sustained BQR retransmission/noRx pressure becomes the
    // only usable strong signal for 900 -> 500. Calibrated from 20260806-221217/222058:
    // audible-bad windows had retx 27-43/s and noRx 26-36/s; X3 healthy sessions stayed
    // retx < 25 and noRx < 22. The cap is temporary: healthy windows plus a minimum hold
    // restore the peer ceiling.
    static final int BQR_FALLBACK_CAP_KBPS = 500;
    static final double BQR_FALLBACK_BAD_RETX_PER_SEC = 30.0;
    static final double BQR_FALLBACK_BAD_NO_RX_PER_SEC = 25.0;
    static final int BQR_FALLBACK_REQUIRED_BAD_WINDOWS = 4;
    static final double BQR_FALLBACK_HEALTHY_RETX_PER_SEC = 24.0;
    static final double BQR_FALLBACK_HEALTHY_NO_RX_PER_SEC = 21.0;
    static final int BQR_FALLBACK_REQUIRED_HEALTHY_WINDOWS = 6;
    /**
     * Escalating recovery hold (2026-08-07): a re-trigger shortly after a recovery escalates the
     * next hold 60s -> 2min -> 5min (capped). A 900 phase that survives
     * {@link #BQR_FALLBACK_SUCCESS_WINDOW_MS} resets the escalation. Mirrors TCP RTO backoff,
     * AARF doubled success counts, and the LDAC ABR nPenalty observing-count penalty.
     */
    static final long[] BQR_FALLBACK_HOLD_MS = {60_000L, 120_000L, 300_000L};
    static final long BQR_FALLBACK_SUCCESS_WINDOW_MS = 2 * 60_000L;
    /**
     * Queue fast-fail (2026-08-07, probe window only). Calibrated from X3 healthy sessions
     * (sampled queue p99 <= 36, max 40) versus Buds Ace 3 bad 900 phases (queue pegged at 45
     * within ~1.5s of a restore). Active only for the first 30s after an upgrade/recovery/codec
     * write; a sustained high-water queue there means the air cannot drain the rate, so clamp
     * immediately instead of waiting four bad BQR windows (~24s).
     */
    static final int BQR_FAST_FAIL_QUEUE_THRESHOLD = 40;
    static final long BQR_FAST_FAIL_HOLD_MS = 3_000L;
    static final long BQR_FAST_FAIL_PROBE_WINDOW_MS = 30_000L;

    interface Listener {
        void onProbeCeilingChanged(String mac, int ceilingKbps, String reason);

        default void onProbeStateChanged(String mac, int ceilingKbps, String reason) {
        }

        /** Stage-D evidence only. Implementations must not change the native or Java ceiling. */
        default void onBqrShadowCandidate(
                String mac,
                int fromKbps,
                int candidateKbps,
                long overflowCount,
                long underflowCount,
                int candidateCount,
                long streamSessionId) {
        }

        /** Experimental BQR fallback state change; implementations only log, never re-enter. */
        default void onBqrFallbackStateChanged(
                String mac,
                int capKbps,
                String reason,
                int badWindows,
                int healthyWindows,
                double retransmissionsPerSecond,
                double noRxPerSecond,
                int escalationLevel,
                long holdMs) {
        }
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
        final int currentQueueLength;
        final int queueCapacity;
        final long lowQueueDurationMs;
        final long lastCongestionAgoMs;
        final String probePhase;
        final long probeElapsedMs;
        final int probeBadBqrWindows;
        final long recoveryWaitRemainingMs;
        final long nativeBackoffRemainingMs;
        final String blockedReason;
        final long streamSessionId;
        /** 0 = unknown, 500/900/1000 = stack-confirmed peer max bitrate capability. */
        final int peerCeilingKbps;
        /** Only meaningful when {@link #peerCeilingKbps} is known; never claims support for unknown. */
        final boolean boundary900To1000Supported;
        /** Age of the most recent BQR sample; -1 when no BQR has arrived yet. */
        final long lastBqrAgoMs;
        final int lastRemoteChoppyLevel;
        final long lastRemoteChoppyAgoMs;
        final int remoteChoppyCount5s;
        final String choppyCapabilityState;
        final long lastBqrOverflowCount;
        final long lastBqrUnderflowCount;
        final int shadowUnstableWindows;
        final int shadowCandidateCount;
        final int lastShadowCandidateKbps;
        final long lastShadowCandidateAgoMs;
        final long shadowStreamSessionId;
        /** BQR fallback cap in kbps; 0 when inactive. */
        final int bqrFallbackCapKbps;

        Snapshot(
                int ceilingKbps,
                int healthyBqrWindows,
                int usableAfhChannels,
                double retransmissionsPerSecond,
                double noRxPerSecond,
                boolean boundary500To900Locked,
                boolean boundary900To1000Locked,
                int requiredHealthyBqrWindows,
                long requiredQuietMs,
                int currentQueueLength,
                int queueCapacity,
                long lowQueueDurationMs,
                long lastCongestionAgoMs,
                String probePhase,
                long probeElapsedMs,
                int probeBadBqrWindows,
                long recoveryWaitRemainingMs,
                long nativeBackoffRemainingMs,
                String blockedReason,
                long streamSessionId,
                int peerCeilingKbps,
                boolean boundary900To1000Supported,
                long lastBqrAgoMs,
                int lastRemoteChoppyLevel,
                long lastRemoteChoppyAgoMs,
                int remoteChoppyCount5s,
                String choppyCapabilityState,
                long lastBqrOverflowCount,
                long lastBqrUnderflowCount,
                int shadowUnstableWindows,
                int shadowCandidateCount,
                int lastShadowCandidateKbps,
                long lastShadowCandidateAgoMs,
                long shadowStreamSessionId,
                int bqrFallbackCapKbps) {
            this.ceilingKbps = ceilingKbps;
            this.healthyBqrWindows = healthyBqrWindows;
            this.usableAfhChannels = usableAfhChannels;
            this.retransmissionsPerSecond = retransmissionsPerSecond;
            this.noRxPerSecond = noRxPerSecond;
            this.boundary500To900Locked = boundary500To900Locked;
            this.boundary900To1000Locked = boundary900To1000Locked;
            this.requiredHealthyBqrWindows = requiredHealthyBqrWindows;
            this.requiredQuietMs = requiredQuietMs;
            this.currentQueueLength = currentQueueLength;
            this.queueCapacity = queueCapacity;
            this.lowQueueDurationMs = lowQueueDurationMs;
            this.lastCongestionAgoMs = lastCongestionAgoMs;
            this.probePhase = probePhase;
            this.probeElapsedMs = probeElapsedMs;
            this.probeBadBqrWindows = probeBadBqrWindows;
            this.recoveryWaitRemainingMs = recoveryWaitRemainingMs;
            this.nativeBackoffRemainingMs = nativeBackoffRemainingMs;
            this.blockedReason = blockedReason;
            this.streamSessionId = streamSessionId;
            this.peerCeilingKbps = peerCeilingKbps;
            this.boundary900To1000Supported = boundary900To1000Supported;
            this.lastBqrAgoMs = lastBqrAgoMs;
            this.lastRemoteChoppyLevel = lastRemoteChoppyLevel;
            this.lastRemoteChoppyAgoMs = lastRemoteChoppyAgoMs;
            this.remoteChoppyCount5s = remoteChoppyCount5s;
            this.choppyCapabilityState = choppyCapabilityState;
            this.lastBqrOverflowCount = lastBqrOverflowCount;
            this.lastBqrUnderflowCount = lastBqrUnderflowCount;
            this.shadowUnstableWindows = shadowUnstableWindows;
            this.shadowCandidateCount = shadowCandidateCount;
            this.lastShadowCandidateKbps = lastShadowCandidateKbps;
            this.lastShadowCandidateAgoMs = lastShadowCandidateAgoMs;
            this.shadowStreamSessionId = shadowStreamSessionId;
            this.bqrFallbackCapKbps = bqrFallbackCapKbps;
        }
    }

    private static final class BoundaryState {
        final int fromKbps;
        final int toKbps;
        int quickFailureCount;
        long firstQuickFailureMs;
        boolean locked;
        boolean probeInFlight;
        boolean upgradeApplied;
        int evidenceTier;
        long probeOpenedMs;
        long lastProbeClosedMs;
        long nativeBackoffUntilMs;

        BoundaryState(int fromKbps, int toKbps) {
            this.fromKbps = fromKbps;
            this.toKbps = toKbps;
        }
    }

    private static final class DeviceState {
        final BoundaryState to900 = new BoundaryState(500, 900);
        final BoundaryState to1000 = new BoundaryState(900, 1000);
        long lastBqrMs;
        long lastValidBqrMs;
        int healthyBqrWindows;
        int usableAfhChannels;
        double retransmissionsPerSecond = Double.NaN;
        double noRxPerSecond = Double.NaN;
        int bqrFallbackCapKbps;
        int bqrFallbackBadWindows;
        int bqrFallbackHealthyWindows;
        long bqrFallbackSinceMs;
        int bqrFallbackEscalationLevel;
        long bqrFallbackRecoveredMs;
        long bqrFastFailUntilMs;
        long bqrFastFailQueueSinceMs;
        long lastCongestionMs;
        long lowQueueSinceMs;
        long criticalQueueSinceMs;
        int currentQueueLength = -1;
        int queueCapacity;
        int lastPublishedCeiling = -1;
        int probeStableBqrWindows;
        int probeBadBqrWindows;
        long healthyDecaySinceMs;
        boolean streaming;
        long streamSessionId;
        int lastRemoteChoppyLevel;
        long lastRemoteChoppyMs;
        long remoteChoppyWindowStartMs;
        int remoteChoppyCount;
        long lastBqrOverflowCount;
        long lastBqrUnderflowCount;
        int shadowUnstableWindows;
        long lastShadowUnstableWindowMs;
        long lastShadowCandidateMs;
        int shadowCandidateCount;
        int lastShadowCandidateKbps;
        long shadowStreamSessionId;
        /**
         * Peer max-bitrate ceiling in kbps reported by the stack-confirmed codec config
         * (0 = unknown, treated as 1000). A value of 900 or lower permanently blocks the
         * 900→1000 upgrade boundary because it is a peer capability, not link quality.
         */
        int peerCeilingKbps;
    }

    private final Map<String, DeviceState> devices = new HashMap<>();
    /** Positive reports observed per MAC for the lifetime of this Bluetooth process. */
    private final Map<String, Boolean> choppyCapabilityObservedByMac = new HashMap<>();
    private final Listener listener;
    private String activeMac;

    LhdcLinkHealthController(Listener listener) {
        this.listener = listener;
    }

    /** Makes a device active. Repeating this for the same BQR callback is strictly idempotent. */
    synchronized void activate(String mac, long nowMs) {
        if (mac == null || mac.isEmpty()) return;
        if (mac.equals(activeMac)) return;
        if (activeMac != null) {
            DeviceState previous = devices.get(activeMac);
            if (previous != null) {
                cancelRecoveryProbe(previous, nowMs, true);
                clearShadowWindow(previous);
            }
        }
        activeMac = mac;
        DeviceState state = stateFor(mac);
        resetSessionEvidence(state, nowMs);
        state.lastPublishedCeiling = -1;
        publishCeiling(mac, state, "device_active");
    }

    /**
     * Resets only transient evidence when native captures a new encoder generation. Learned
     * boundary locks intentionally survive an ordinary reconnect for the same headset.
     */
    synchronized void onStreamSessionChanged(String mac, long sessionId, long nowMs) {
        if (mac == null || sessionId <= 0L) return;
        DeviceState state = stateFor(mac);
        if (state.streamSessionId == sessionId) return;
        state.streamSessionId = sessionId;
        resetSessionEvidence(state, nowMs);
        if (mac.equals(activeMac)) {
            state.lastPublishedCeiling = -1;
            publishCeiling(mac, state, "stream_session");
            if (effectiveCeiling(state) >= 900) {
                // A fresh stream at a fixed high tier is a new 900+ attempt: arm the queue
                // fast-fail probe window so an unsustainable rate is caught within seconds.
                state.bqrFastFailUntilMs = nowMs + BQR_FAST_FAIL_PROBE_WINDOW_MS;
                state.bqrFastFailQueueSinceMs = 0L;
            }
        }
    }

    synchronized String activeMac() {
        return activeMac;
    }

    /**
     * Records the peer max-bitrate ceiling learned from the stack-confirmed codec config.
     * A ceiling of 900 kbps or lower cancels any in-flight 900→1000 probe and prevents the
     * boundary from ever reopening; the 500→900 boundary keeps learning normally.
     */
    synchronized void setPeerCeilingKbps(
            String mac, int ceilingKbps, long nowMs, String reason) {
        if (mac == null || mac.isEmpty() || ceilingKbps <= 0) return;
        DeviceState state = stateFor(mac);
        state.peerCeilingKbps = ceilingKbps;
        if (ceilingKbps >= 900 && "codec_confirmed".equals(reason)) {
            // A stack-confirmed fixed-quality config is an explicit 900+ attempt (the user picked
            // 音质优先, or the remembered config was replayed). Grant it even when a BQR fallback
            // cap is active, reset the escalation bookkeeping, and arm the fast-fail window so an
            // unsustainable rate is clamped within seconds instead of staying capped.
            state.bqrFallbackCapKbps = 0;
            state.bqrFallbackBadWindows = 0;
            state.bqrFallbackHealthyWindows = 0;
            state.bqrFallbackSinceMs = 0L;
            state.bqrFallbackEscalationLevel = 0;
            state.bqrFallbackRecoveredMs = 0L;
            state.bqrFastFailUntilMs = nowMs + BQR_FAST_FAIL_PROBE_WINDOW_MS;
            state.bqrFastFailQueueSinceMs = 0L;
        }
        if (ceilingKbps <= 900) {
            if (state.to1000.probeInFlight) {
                state.to1000.probeInFlight = false;
                state.to1000.upgradeApplied = false;
                state.to1000.lastProbeClosedMs = nowMs;
                state.probeStableBqrWindows = 0;
                state.probeBadBqrWindows = 0;
            }
            // A hard peer capability replaces the learned 900->1000 lock semantics. A repeated
            // 1000-capable codec snapshot, however, must not erase a real link-quality lock.
            state.to1000.locked = false;
        }
        if (mac.equals(activeMac)) {
            state.lastPublishedCeiling = -1;
            publishCeiling(mac, state,
                    reason != null && !reason.isEmpty() ? reason : "peer_ceiling");
        }
    }

    /** Clears all learned and transient state after the LHDC user-visible session ends. */
    synchronized boolean resetDevice(String mac, long nowMs, String reason) {
        if (mac == null || mac.isEmpty()) return false;
        DeviceState state = devices.get(mac);
        boolean wasActive = mac.equals(activeMac);
        if (state == null) {
            if (wasActive) activeMac = null;
            return wasActive;
        }
        clearBoundary(state.to900);
        clearBoundary(state.to1000);
        state.peerCeilingKbps = 0;
        state.streamSessionId = 0L;
        resetSessionEvidence(state, nowMs);
        if (wasActive) {
            state.lastPublishedCeiling = -1;
            publishCeiling(mac, state,
                    reason != null && !reason.isEmpty() ? reason : "session_reset");
            activeMac = null;
        }
        devices.remove(mac);
        return wasActive;
    }

    synchronized void onBqrSample(String mac, BqrSample sample, long nowMs) {
        onBqrSample(mac, sample, nowMs, true);
    }

    synchronized void onBqrSample(
            String mac, BqrSample sample, long nowMs, boolean streaming) {
        if (mac == null || sample == null) return;
        DeviceState state = stateFor(mac);
        state.streaming = streaming;
        long intervalMs = state.lastBqrMs == 0L ? 0L : nowMs - state.lastBqrMs;
        state.lastBqrMs = nowMs;
        state.usableAfhChannels = Math.max(0, 79 - sample.unusedAfhChannels);
        state.lastBqrOverflowCount = sample.overflowCount;
        state.lastBqrUnderflowCount = sample.underflowCount;

        boolean validInterval = intervalMs >= MIN_BQR_INTERVAL_MS
                && intervalMs <= MAX_BQR_INTERVAL_MS;
        boolean strictlyHealthy = false;
        if (validInterval) {
            state.lastValidBqrMs = nowMs;
            double seconds = intervalMs / 1000.0;
            state.retransmissionsPerSecond = sample.retransmissionCount / seconds;
            state.noRxPerSecond = sample.noRxCount / seconds;
            strictlyHealthy = streaming
                    && sample.unusedAfhChannels <= MAX_UNUSED_AFH_CHANNELS
                    && state.retransmissionsPerSecond <= MAX_RETRANSMISSIONS_PER_SECOND
                    && state.noRxPerSecond <= MAX_NO_RX_PER_SECOND;
            state.healthyBqrWindows = strictlyHealthy ? state.healthyBqrWindows + 1 : 0;
            if (BuildConfig.LHDC_BQR_FALLBACK) {
                evaluateBqrFallback(mac, state, nowMs, streaming);
            }

            BoundaryState probe = inFlightBoundary(state);
            updateBqrShadowCandidate(
                    mac, state, sample, nowMs, streaming,
                    probe == null && mac.equals(activeMac));
            if (probe != null) {
                boolean maintainable = streaming
                        && sample.unusedAfhChannels <= MAX_PROBE_UNUSED_AFH_CHANNELS
                        && state.retransmissionsPerSecond
                        <= MAX_PROBE_RETRANSMISSIONS_PER_SECOND
                        && state.noRxPerSecond <= MAX_PROBE_NO_RX_PER_SECOND;
                boolean severe = sample.unusedAfhChannels >= SEVERE_UNUSED_AFH_CHANNELS
                        || state.retransmissionsPerSecond
                        >= SEVERE_RETRANSMISSIONS_PER_SECOND
                        || state.noRxPerSecond >= SEVERE_NO_RX_PER_SECOND
                        || sample.overflowCount > 0L
                        || sample.underflowCount > 0L;
                if (maintainable) {
                    state.probeStableBqrWindows++;
                    state.probeBadBqrWindows = 0;
                } else {
                    state.probeStableBqrWindows = 0;
                    state.probeBadBqrWindows++;
                    long openMs = Math.max(0L, nowMs - probe.probeOpenedMs);
                    if (severe || (state.probeBadBqrWindows
                            >= MAX_CONSECUTIVE_PROBE_BAD_WINDOWS
                            && openMs >= MIN_PROBE_OPEN_MS)) {
                        if (cancelRecoveryProbe(state, nowMs, false)) {
                            publishCeiling(mac, state,
                                    severe ? "probe_bqr_severe" : "probe_bqr_sustained_bad");
                        }
                    }
                }
            }
            applyEvidenceTierDecay(state, nowMs, strictlyHealthy);
        } else {
            // Missing or delayed BQR is absence of evidence, not proof of congestion. Do not
            // revoke an active probe or destroy the independently sampled low-queue window.
            state.retransmissionsPerSecond = Double.NaN;
            state.noRxPerSecond = Double.NaN;
            state.healthyBqrWindows = 0;
            state.healthyDecaySinceMs = 0L;
            clearShadowWindow(state);
        }

        if (!streaming) {
            state.lowQueueSinceMs = 0L;
            state.criticalQueueSinceMs = 0L;
            state.healthyDecaySinceMs = 0L;
            if (cancelRecoveryProbe(state, nowMs, true)) {
                publishCeiling(mac, state, "probe_stream_idle");
            }
            return;
        }
        if (mac.equals(activeMac)) maybeOpenRecoveryProbe(mac, state, nowMs);
    }

    synchronized void onQueueSample(String mac, int length, int capacity, long nowMs) {
        if (mac == null || capacity <= 0 || length < 0) return;
        DeviceState state = stateFor(mac);
        state.streaming = true;
        state.currentQueueLength = length;
        state.queueCapacity = capacity;
        long occupancy = (long) length * 100L;
        if (occupancy <= (long) capacity * 25L) {
            if (state.lowQueueSinceMs == 0L) state.lowQueueSinceMs = nowMs;
        } else {
            state.lowQueueSinceMs = 0L;
            state.healthyDecaySinceMs = 0L;
        }

        boolean full = length >= capacity;
        boolean critical = occupancy >= (long) capacity * 90L;
        if (critical) {
            if (state.criticalQueueSinceMs == 0L) state.criticalQueueSinceMs = nowMs;
        } else {
            state.criticalQueueSinceMs = 0L;
        }
        boolean sustainedCritical = state.criticalQueueSinceMs != 0L
                && nowMs - state.criticalQueueSinceMs >= CRITICAL_QUEUE_HOLD_MS;
        if (full || sustainedCritical) {
            noteCongestion(state, nowMs);
            if (cancelRecoveryProbe(state, nowMs, false)) {
                publishCeiling(mac, state,
                        full ? "probe_queue_full" : "probe_queue_congested");
            }
        } else {
            maybeCancelDegradedProbe(mac, state, nowMs);
        }
        if (BuildConfig.LHDC_BQR_FALLBACK) {
            evaluateBqrQueueFastFail(mac, state, length, nowMs);
        }
        if (mac.equals(activeMac)) maybeOpenRecoveryProbe(mac, state, nowMs);
    }

    synchronized void onCongestion(String mac, long nowMs) {
        if (mac == null) return;
        DeviceState state = stateFor(mac);
        noteCongestion(state, nowMs);
        if (cancelRecoveryProbe(state, nowMs, false)) {
            publishCeiling(mac, state, "probe_choppy");
        }
    }

    /**
     * Records every positive headset-side choppy report with a rolling 5-second counter so the
     * diagnostic page can distinguish "reports arrived" from "a downgrade was actually issued"
     * (the latter still surfaces through {@link #onCongestion} and native governor events).
     */
    synchronized void onRemoteChoppyReport(String mac, int level, long nowMs) {
        if (mac == null || level <= 0) return;
        choppyCapabilityObservedByMac.put(mac, Boolean.TRUE);
        DeviceState state = stateFor(mac);
        state.lastRemoteChoppyLevel = level;
        state.lastRemoteChoppyMs = nowMs;
        if (state.remoteChoppyWindowStartMs == 0L
                || nowMs - state.remoteChoppyWindowStartMs > 5_000L) {
            state.remoteChoppyWindowStartMs = nowMs;
            state.remoteChoppyCount = 0;
        }
        state.remoteChoppyCount++;
        onCongestion(mac, nowMs);
    }

    synchronized void onGovernorEvent(
            String mac, int event, int fromKbps, int toKbps, long detailMs, long nowMs) {
        if (mac == null) return;
        DeviceState state = stateFor(mac);
        BoundaryState boundary = boundaryFor(state, fromKbps, toKbps);
        if (boundary == null) return;
        if (event == EVENT_UPGRADE_APPLIED) {
            // This is the actual native set_target_bitrate success, not merely an open ceiling.
            if (boundary.probeInFlight) boundary.upgradeApplied = true;
            return;
        }
        if (event == EVENT_QUICK_FAILURE) {
            noteCongestion(state, nowMs);
            if (detailMs > 0L) boundary.nativeBackoffUntilMs = nowMs + detailMs;
            if (boundary.probeInFlight || boundary.upgradeApplied) {
                boundary.probeInFlight = false;
                boundary.upgradeApplied = false;
                boundary.locked = true;
                boundary.lastProbeClosedMs = nowMs;
                boundary.evidenceTier = Math.min(2, boundary.evidenceTier + 1);
                state.probeStableBqrWindows = 0;
                state.probeBadBqrWindows = 0;
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
            state.probeStableBqrWindows = 0;
            state.probeBadBqrWindows = 0;
            publishCeiling(mac, state, "boundary_stable");
        }
    }

    // Kept for source compatibility with older tests/callers that do not carry event detail.
    synchronized void onGovernorEvent(
            String mac, int event, int fromKbps, int toKbps, long nowMs) {
        onGovernorEvent(mac, event, fromKbps, toKbps, 0L, nowMs);
    }

    synchronized Snapshot snapshot(String mac, long nowMs) {
        DeviceState state = devices.get(mac);
        String choppyCapabilityState = Boolean.TRUE.equals(choppyCapabilityObservedByMac.get(mac))
                ? CHOPPY_CAPABILITY_OBSERVED : CHOPPY_CAPABILITY_UNKNOWN;
        if (state == null) {
            return new Snapshot(1000, 0, 0, Double.NaN, Double.NaN,
                    false, false, 0, 0L, -1, 0, 0L, -1L,
                    "stable", 0L, 0, 0L, 0L, "none", 0L,
                    0, false, -1L, 0, -1L, 0,
                    choppyCapabilityState, 0L, 0L, 0, 0, 0, -1L, 0L, 0);
        }
        BoundaryState probe = inFlightBoundary(state);
        BoundaryState blocked = probe != null ? probe : firstBlockedBoundary(state);
        long lowQueueDurationMs = state.lowQueueSinceMs == 0L
                ? 0L : Math.max(0L, nowMs - state.lowQueueSinceMs);
        long lastCongestionAgoMs = state.lastCongestionMs == 0L
                ? -1L : Math.max(0L, nowMs - state.lastCongestionMs);
        long probeElapsedMs = probe == null || probe.probeOpenedMs == 0L
                ? 0L : Math.max(0L, nowMs - probe.probeOpenedMs);
        long nativeBackoffRemainingMs = blocked == null
                ? 0L : remaining(blocked.nativeBackoffUntilMs, nowMs);
        long lastBqrAgoMs = state.lastBqrMs == 0L
                ? -1L : Math.max(0L, nowMs - state.lastBqrMs);
        long lastRemoteChoppyAgoMs = state.lastRemoteChoppyMs == 0L
                ? -1L : Math.max(0L, nowMs - state.lastRemoteChoppyMs);
        int remoteChoppyCount5s = state.remoteChoppyWindowStartMs != 0L
                && nowMs - state.remoteChoppyWindowStartMs <= 5_000L
                ? state.remoteChoppyCount : 0;
        long lastShadowCandidateAgoMs = state.lastShadowCandidateMs == 0L
                ? -1L : Math.max(0L, nowMs - state.lastShadowCandidateMs);
        return new Snapshot(
                effectiveCeiling(state),
                state.healthyBqrWindows,
                state.usableAfhChannels,
                state.retransmissionsPerSecond,
                state.noRxPerSecond,
                state.to900.locked && !state.to900.probeInFlight,
                state.to1000.locked && !state.to1000.probeInFlight,
                blocked == null ? 0 : healthyWindowsForTier(blocked.evidenceTier),
                blocked == null ? 0L : quietMsForTier(blocked.evidenceTier),
                state.currentQueueLength,
                state.queueCapacity,
                lowQueueDurationMs,
                lastCongestionAgoMs,
                probePhase(state),
                probeElapsedMs,
                state.probeBadBqrWindows,
                recoveryWaitRemainingMs(state, blocked, nowMs),
                nativeBackoffRemainingMs,
                blockedReason(state, blocked, nowMs),
                state.streamSessionId,
                state.peerCeilingKbps,
                state.peerCeilingKbps > 900,
                lastBqrAgoMs,
                state.lastRemoteChoppyLevel,
                lastRemoteChoppyAgoMs,
                remoteChoppyCount5s,
                choppyCapabilityState,
                state.lastBqrOverflowCount,
                state.lastBqrUnderflowCount,
                state.shadowUnstableWindows,
                state.shadowCandidateCount,
                state.lastShadowCandidateKbps,
                lastShadowCandidateAgoMs,
                state.shadowStreamSessionId,
                state.bqrFallbackCapKbps);
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
        if (boundary == null || !state.streaming) return;
        long quietMs = quietMsForTier(boundary.evidenceTier);
        if (state.healthyBqrWindows < healthyWindowsForTier(boundary.evidenceTier)
                || state.lastValidBqrMs == 0L
                || nowMs - state.lastValidBqrMs > MAX_BQR_INTERVAL_MS
                || state.lowQueueSinceMs == 0L
                || nowMs - state.lowQueueSinceMs < quietMs
                || (state.lastCongestionMs != 0L
                && nowMs - state.lastCongestionMs < quietMs)
                || remaining(boundary.nativeBackoffUntilMs, nowMs) > 0L
                || (boundary.lastProbeClosedMs != 0L
                && nowMs - boundary.lastProbeClosedMs < MIN_PROBE_COOLDOWN_MS)) {
            return;
        }
        boundary.probeInFlight = true;
        boundary.upgradeApplied = false;
        boundary.probeOpenedMs = nowMs;
        state.probeStableBqrWindows = 0;
        state.probeBadBqrWindows = 0;
        publishCeiling(mac, state, "healthy_recovery_probe");
    }

    private void maybeCancelDegradedProbe(String mac, DeviceState state, long nowMs) {
        BoundaryState probe = inFlightBoundary(state);
        if (probe == null
                || state.probeBadBqrWindows < MAX_CONSECUTIVE_PROBE_BAD_WINDOWS
                || nowMs - probe.probeOpenedMs < MIN_PROBE_OPEN_MS) {
            return;
        }
        if (cancelRecoveryProbe(state, nowMs, false)) {
            publishCeiling(mac, state, "probe_bqr_sustained_bad");
        }
    }

    private static void applyEvidenceTierDecay(
            DeviceState state, long nowMs, boolean strictlyHealthy) {
        if (!strictlyHealthy
                || state.lowQueueSinceMs == 0L
                || (state.lastCongestionMs != 0L
                && state.lastCongestionMs >= state.lowQueueSinceMs)
                || (!state.to900.locked && !state.to1000.locked)) {
            state.healthyDecaySinceMs = 0L;
            return;
        }
        if (state.healthyDecaySinceMs == 0L) {
            state.healthyDecaySinceMs = nowMs;
            return;
        }
        long elapsed = nowMs - state.healthyDecaySinceMs;
        if (elapsed < EVIDENCE_TIER_DECAY_MS) return;
        int steps = (int) (elapsed / EVIDENCE_TIER_DECAY_MS);
        decayBoundaryTier(state.to900, steps);
        decayBoundaryTier(state.to1000, steps);
        state.healthyDecaySinceMs += steps * EVIDENCE_TIER_DECAY_MS;
    }

    private static void decayBoundaryTier(BoundaryState boundary, int steps) {
        if (!boundary.locked || steps <= 0) return;
        // Decay relaxes the next probe threshold; it never bypasses the probe by unlocking.
        boundary.evidenceTier = Math.max(0, boundary.evidenceTier - steps);
    }

    private void publishCeiling(String mac, DeviceState state, String reason) {
        if (!mac.equals(activeMac)) return;
        int ceiling = effectiveCeiling(state);
        if (state.lastPublishedCeiling == ceiling) {
            if (listener != null) listener.onProbeStateChanged(mac, ceiling, reason);
            return;
        }
        state.lastPublishedCeiling = ceiling;
        if (listener != null) listener.onProbeCeilingChanged(mac, ceiling, reason);
    }

    private static int effectiveCeiling(DeviceState state) {
        int ceiling;
        if (state.to900.locked) {
            ceiling = state.to900.probeInFlight ? 900 : 500;
        } else if (state.to1000.locked) {
            ceiling = state.to1000.probeInFlight ? 1000 : 900;
        } else if (state.peerCeilingKbps > 0 && state.peerCeilingKbps <= 900) {
            ceiling = state.peerCeilingKbps;
        } else {
            ceiling = 1000;
        }
        if (state.bqrFallbackCapKbps > 0 && state.bqrFallbackCapKbps < ceiling) {
            ceiling = state.bqrFallbackCapKbps;
        }
        return ceiling;
    }

    /**
     * Experimental validation path: when the headset never reports remote choppy (Buds Ace 3),
     * sustained BQR retransmission/noRx pressure is the only strong downgrade signal. Four
     * consecutive bad windows (about 24 s) clamp the effective ceiling to 500; six consecutive
     * healthy windows after an escalating hold (60s -> 2min -> 5min) restore the peer ceiling.
     * Every transition is reported through the listener so the feedback package contains the
     * full decision record.
     */
    private void evaluateBqrFallback(String mac, DeviceState state, long nowMs, boolean streaming) {
        if (!streaming) return;
        double retx = state.retransmissionsPerSecond;
        double noRx = state.noRxPerSecond;
        if (Double.isNaN(retx) || Double.isNaN(noRx)) return;
        if (state.peerCeilingKbps > 0 && state.peerCeilingKbps <= BQR_FALLBACK_CAP_KBPS) return;

        boolean bad = retx >= BQR_FALLBACK_BAD_RETX_PER_SEC
                && noRx >= BQR_FALLBACK_BAD_NO_RX_PER_SEC;
        boolean healthy = retx < BQR_FALLBACK_HEALTHY_RETX_PER_SEC
                && noRx < BQR_FALLBACK_HEALTHY_NO_RX_PER_SEC;

        if (state.bqrFallbackCapKbps == 0) {
            state.bqrFallbackBadWindows = bad ? state.bqrFallbackBadWindows + 1 : 0;
            if (state.bqrFallbackBadWindows >= BQR_FALLBACK_REQUIRED_BAD_WINDOWS) {
                int windows = state.bqrFallbackBadWindows;
                triggerBqrFallback(mac, state, nowMs, "triggered", windows, false);
            }
            return;
        }

        state.bqrFallbackHealthyWindows = healthy ? state.bqrFallbackHealthyWindows + 1 : 0;
        if (state.bqrFallbackHealthyWindows >= BQR_FALLBACK_REQUIRED_HEALTHY_WINDOWS
                && nowMs - state.bqrFallbackSinceMs >= bqrFallbackHoldMs(state)) {
            int windows = state.bqrFallbackHealthyWindows;
            state.bqrFallbackHealthyWindows = 0;
            state.bqrFallbackCapKbps = 0;
            state.bqrFallbackRecoveredMs = nowMs;
            state.bqrFastFailUntilMs = nowMs + BQR_FAST_FAIL_PROBE_WINDOW_MS;
            state.bqrFastFailQueueSinceMs = 0L;
            publishCeiling(mac, state, "bqr_fallback_recovered");
            notifyBqrFallback(mac, state, "recovered", 0, windows);
        }
    }

    private void triggerBqrFallback(
            String mac,
            DeviceState state,
            long nowMs,
            String notifyReason,
            int badWindows,
            boolean queueFastFail) {
        state.bqrFallbackBadWindows = 0;
        state.bqrFallbackCapKbps = BQR_FALLBACK_CAP_KBPS;
        state.bqrFallbackSinceMs = nowMs;
        state.bqrFastFailUntilMs = 0L;
        state.bqrFastFailQueueSinceMs = 0L;
        if (state.bqrFallbackRecoveredMs != 0L) {
            if (nowMs - state.bqrFallbackRecoveredMs <= BQR_FALLBACK_SUCCESS_WINDOW_MS) {
                // The previous 900 phase died shortly after a recovery: failed probe, escalate.
                state.bqrFallbackEscalationLevel = Math.min(
                        BQR_FALLBACK_HOLD_MS.length - 1,
                        state.bqrFallbackEscalationLevel + 1);
            } else {
                // The previous 900 phase survived long enough: start over with the base hold.
                state.bqrFallbackEscalationLevel = 0;
            }
        } else {
            state.bqrFallbackEscalationLevel = 0;
        }
        publishCeiling(mac, state,
                queueFastFail ? "bqr_fallback_triggered_queue" : "bqr_fallback_triggered");
        notifyBqrFallback(mac, state, notifyReason, badWindows, 0);
    }

    /**
     * Probe-window queue fast-fail: during the first {@link #BQR_FAST_FAIL_PROBE_WINDOW_MS} after
     * an upgrade/recovery/codec write, a sustained high-water TX queue means the air cannot drain
     * the current rate. Clamp to 500 immediately instead of waiting four bad BQR windows.
     * Calibrated so X3 healthy sessions (sampled queue max 40, and only outside probe windows)
     * never trip it, while Buds Ace 3 bad phases peg the queue at 45 within ~1.5s.
     */
    private void evaluateBqrQueueFastFail(
            String mac, DeviceState state, int length, long nowMs) {
        if (state.bqrFallbackCapKbps > 0) return;
        if (effectiveCeiling(state) < 900) return;
        if (state.bqrFastFailUntilMs == 0L || nowMs > state.bqrFastFailUntilMs) {
            state.bqrFastFailQueueSinceMs = 0L;
            return;
        }
        if (length >= BQR_FAST_FAIL_QUEUE_THRESHOLD) {
            if (state.bqrFastFailQueueSinceMs == 0L) {
                state.bqrFastFailQueueSinceMs = nowMs;
            } else if (nowMs - state.bqrFastFailQueueSinceMs >= BQR_FAST_FAIL_HOLD_MS) {
                state.bqrFastFailQueueSinceMs = 0L;
                triggerBqrFallback(
                        mac, state, nowMs, "triggered_queue_fast_fail", 0, true);
            }
        } else {
            state.bqrFastFailQueueSinceMs = 0L;
        }
    }

    private void notifyBqrFallback(
            String mac, DeviceState state, String reason, int badWindows, int healthyWindows) {
        if (listener == null) return;
        listener.onBqrFallbackStateChanged(
                mac,
                state.bqrFallbackCapKbps,
                reason,
                badWindows,
                healthyWindows,
                state.retransmissionsPerSecond,
                state.noRxPerSecond,
                state.bqrFallbackEscalationLevel,
                bqrFallbackHoldMs(state));
    }

    private static long bqrFallbackHoldMs(DeviceState state) {
        return BQR_FALLBACK_HOLD_MS[Math.max(0,
                Math.min(state.bqrFallbackEscalationLevel, BQR_FALLBACK_HOLD_MS.length - 1))];
    }

    private static BoundaryState inFlightBoundary(DeviceState state) {
        if (state.to900.probeInFlight) return state.to900;
        if (state.to1000.probeInFlight) return state.to1000;
        return null;
    }

    private static BoundaryState firstBlockedBoundary(DeviceState state) {
        if (state.to900.locked && !state.to900.probeInFlight) return state.to900;
        if (state.to1000.locked && !state.to1000.probeInFlight) return state.to1000;
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
        state.criticalQueueSinceMs = 0L;
        state.healthyBqrWindows = 0;
        state.healthyDecaySinceMs = 0L;
    }

    private void updateBqrShadowCandidate(
            String mac,
            DeviceState state,
            BqrSample sample,
            long nowMs,
            boolean streaming,
            boolean stableBoundaryState) {
        boolean unstable = streaming
                && stableBoundaryState
                && (sample.overflowCount > 0L || sample.underflowCount > 0L);
        if (!unstable) {
            clearShadowWindow(state);
            return;
        }
        if (state.shadowUnstableWindows > 0
                && (state.shadowStreamSessionId != state.streamSessionId
                || nowMs - state.lastShadowUnstableWindowMs > MAX_BQR_INTERVAL_MS)) {
            clearShadowWindow(state);
        }
        if (state.shadowUnstableWindows == 0) {
            state.shadowStreamSessionId = state.streamSessionId;
        }
        state.shadowUnstableWindows++;
        state.lastShadowUnstableWindowMs = nowMs;
        if (state.shadowUnstableWindows < REQUIRED_SHADOW_UNSTABLE_WINDOWS) return;

        int fromKbps = effectiveCeiling(state);
        int candidateKbps = fromKbps >= 1000 ? 900 : fromKbps == 900 ? 500 : 0;
        boolean cooldownElapsed = state.lastShadowCandidateMs == 0L
                || nowMs - state.lastShadowCandidateMs >= SHADOW_CANDIDATE_COOLDOWN_MS;
        clearShadowWindow(state);
        if (!cooldownElapsed) return;

        state.lastShadowCandidateMs = nowMs;
        state.lastShadowCandidateKbps = candidateKbps;
        state.shadowCandidateCount++;
        if (listener != null) {
            listener.onBqrShadowCandidate(
                    mac,
                    fromKbps,
                    candidateKbps,
                    sample.overflowCount,
                    sample.underflowCount,
                    state.shadowCandidateCount,
                    state.streamSessionId);
        }
    }

    private static void clearShadowWindow(DeviceState state) {
        state.shadowUnstableWindows = 0;
        state.lastShadowUnstableWindowMs = 0L;
        state.shadowStreamSessionId = 0L;
    }

    private static void clearBoundary(BoundaryState boundary) {
        boundary.quickFailureCount = 0;
        boundary.firstQuickFailureMs = 0L;
        boundary.locked = false;
        boundary.probeInFlight = false;
        boundary.upgradeApplied = false;
        boundary.evidenceTier = 0;
        boundary.probeOpenedMs = 0L;
        boundary.lastProbeClosedMs = 0L;
        boundary.nativeBackoffUntilMs = 0L;
    }

    private static boolean cancelRecoveryProbe(
            DeviceState state, long nowMs, boolean clearApplied) {
        BoundaryState boundary = inFlightBoundary(state);
        if (boundary == null) return false;
        boundary.probeInFlight = false;
        boundary.lastProbeClosedMs = nowMs;
        if (!clearApplied && boundary.upgradeApplied) {
            // A probe that reached the higher native target and was then revoked is failed
            // recovery evidence even when native's narrower quick-failure window has elapsed.
            boundary.evidenceTier = Math.min(2, boundary.evidenceTier + 1);
        }
        boundary.upgradeApplied = false;
        state.probeStableBqrWindows = 0;
        state.probeBadBqrWindows = 0;
        return true;
    }

    private static void resetSessionEvidence(DeviceState state, long nowMs) {
        cancelRecoveryProbe(state, nowMs, true);
        state.lastBqrMs = 0L;
        state.lastValidBqrMs = 0L;
        state.healthyBqrWindows = 0;
        state.retransmissionsPerSecond = Double.NaN;
        state.noRxPerSecond = Double.NaN;
        state.bqrFallbackEscalationLevel = 0;
        state.bqrFallbackRecoveredMs = 0L;
        state.bqrFastFailUntilMs = 0L;
        state.bqrFastFailQueueSinceMs = 0L;
        state.lastCongestionMs = nowMs;
        state.lowQueueSinceMs = 0L;
        state.criticalQueueSinceMs = 0L;
        state.currentQueueLength = -1;
        state.queueCapacity = 0;
        state.probeStableBqrWindows = 0;
        state.probeBadBqrWindows = 0;
        state.healthyDecaySinceMs = 0L;
        state.streaming = false;
        state.lastRemoteChoppyLevel = 0;
        state.lastRemoteChoppyMs = 0L;
        state.remoteChoppyWindowStartMs = 0L;
        state.remoteChoppyCount = 0;
        state.lastBqrOverflowCount = 0L;
        state.lastBqrUnderflowCount = 0L;
        clearShadowWindow(state);
        state.lastShadowCandidateMs = 0L;
        state.shadowCandidateCount = 0;
        state.lastShadowCandidateKbps = 0;
    }

    private static String probePhase(DeviceState state) {
        BoundaryState probe = inFlightBoundary(state);
        if (probe != null) return probe.upgradeApplied ? "verifying" : "probing";
        if (state.to900.locked || state.to1000.locked) return "locked";
        return "stable";
    }

    private static String blockedReason(
            DeviceState state, BoundaryState boundary, long nowMs) {
        if (boundary == null) return "none";
        if (boundary.probeInFlight) {
            return boundary.upgradeApplied
                    ? "verifying_native_upgrade" : "waiting_native_upgrade";
        }
        if (!state.streaming) return "stream_idle";
        if (remaining(boundary.nativeBackoffUntilMs, nowMs) > 0L) return "native_backoff";
        if (state.healthyBqrWindows < healthyWindowsForTier(boundary.evidenceTier)) {
            return "waiting_healthy_bqr";
        }
        if (state.lastValidBqrMs == 0L
                || nowMs - state.lastValidBqrMs > MAX_BQR_INTERVAL_MS) {
            return "waiting_fresh_bqr";
        }
        if (state.lowQueueSinceMs == 0L) return "waiting_low_queue";
        long quietMs = quietMsForTier(boundary.evidenceTier);
        if (nowMs - state.lowQueueSinceMs < quietMs) return "waiting_low_queue_duration";
        if (state.lastCongestionMs != 0L && nowMs - state.lastCongestionMs < quietMs) {
            return "congestion_cooldown";
        }
        if (boundary.lastProbeClosedMs != 0L
                && nowMs - boundary.lastProbeClosedMs < MIN_PROBE_COOLDOWN_MS) {
            return "probe_cooldown";
        }
        return "ready";
    }

    private static long recoveryWaitRemainingMs(
            DeviceState state, BoundaryState boundary, long nowMs) {
        if (boundary == null || boundary.probeInFlight) return 0L;
        long remainingMs = remaining(boundary.nativeBackoffUntilMs, nowMs);
        long quietMs = quietMsForTier(boundary.evidenceTier);
        if (state.lowQueueSinceMs == 0L) {
            remainingMs = Math.max(remainingMs, quietMs);
        } else {
            remainingMs = Math.max(remainingMs,
                    Math.max(0L, quietMs - (nowMs - state.lowQueueSinceMs)));
        }
        if (state.lastCongestionMs != 0L) {
            remainingMs = Math.max(remainingMs,
                    Math.max(0L, quietMs - (nowMs - state.lastCongestionMs)));
        }
        if (boundary.lastProbeClosedMs != 0L) {
            remainingMs = Math.max(remainingMs,
                    Math.max(0L, MIN_PROBE_COOLDOWN_MS
                            - (nowMs - boundary.lastProbeClosedMs)));
        }
        return remainingMs;
    }

    private static long remaining(long untilMs, long nowMs) {
        return untilMs <= nowMs ? 0L : untilMs - nowMs;
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
