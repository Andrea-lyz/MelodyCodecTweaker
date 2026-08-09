package xyz.melodylsp.codec.system;

import java.util.HashMap;
import java.util.Locale;
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
    /**
     * Phase N-2 START_GUARD: after first play / connect / resume / stream rebuild, bad BQR
     * windows do not participate in slow-heat downgrade counting and choppy reports are
     * recorded but not integrated. The startup pseudo-high retx window (162.5/s evidence) is
     * absorbed here instead of a bare first-window skip.
     */
    static final long START_GUARD_MS = 15_000L;
    /**
     * Phase N-2 POST_SWITCH_GUARD: after any Target_Cap switch, choppy (a soft signal) is
     * recorded but not integrated for this long, so switch-transient glitches do not cancel
     * recovery probes or count as congestion. Hard evidence (queue fast-fail, Phase 3
     * disaster) is not blocked by this guard.
     */
    static final long POST_SWITCH_GUARD_MS = 10_000L;
    /**
     * Phase N-2 BQR_SUSPECT_INVALID: retx at or above this rate with ~zero noRx is
     * physically contradictory; combined with a low queue and no choppy during stream
     * warm-up it is logged and excluded from decisions.
     */
    static final double BQR_SUSPECT_RETX_PER_SEC = 110.0;
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
    /**
     * Phase N-1 requestId transaction: if the native side did not confirm a Target_Cap write
     * (TRANSITION_APPLIED/UPGRADE_APPLIED with the matching requestId) within this window, the
     * switch is marked timed out and Java falls back to the getter for the real bitrate.
     */
    static final long SWITCH_CONFIRM_TIMEOUT_MS = 2_500L;
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
    /**
     * Mid-tier (500 -> 900) recovery noRx gate (2026-08-09 compromise, decision 44): the
     * Buds-calibrated <21 noRx sits inside the X3-family normal band (noRx 21-29), so
     * 500->900 recoveries took ~4 min waiting for a rare sub-21 window (feedback 233639:
     * noRx 22.5/21.2 windows kept resetting the streak). retx keeps the strict <24 gate;
     * noRx relaxes to the non-bad boundary <25, keeping a one-sided hot window (e.g.
     * retx 23/noRx 26) from counting.
     */
    static final double BQR_FALLBACK_HEALTHY_NO_RX_MID_PER_SEC = 25.0;
    static final int BQR_FALLBACK_REQUIRED_HEALTHY_WINDOWS = 6;
    /**
     * Escalating recovery hold (2026-08-07): a re-trigger shortly after a recovery escalates the
     * next hold 60s -> 2min -> 5min (capped). A 900 phase that survives
     * {@link #BQR_FALLBACK_SUCCESS_WINDOW_MS} resets the escalation. Mirrors TCP RTO backoff,
     * AARF doubled success counts, and the LDAC ABR nPenalty observing-count penalty.
     */
    static final long[] BQR_FALLBACK_HOLD_MS = {60_000L, 120_000L, 300_000L};
    static final long BQR_FALLBACK_SUCCESS_WINDOW_MS = 2 * 60_000L;

    // Phase N-4 (6.8.4): absolute downgrade dead zone. After any successful downgrade, BQR
    // recovery windows and the 8 s leap window are frozen for this long, so evidence cannot
    // start from the old bitrate's tail (no fast-in-fast-out ping-pong). Shares the 10 s
    // timing of MIN_PROBE_COOLDOWN_MS. Disaster evidence is the only exception (6.8.4).
    static final long DOWNGRADE_DEAD_ZONE_MS = 10_000L;

    // Phase N-4 asymmetric recovery (6.8.5): tiers recover at different speeds.
    // 400 -> 500 is the fast channel: 5 windows at relaxed evidence (retx<=40/noRx<=25,
    // short stay) — fast means short stay and light window count, not skipping verification.
    static final double RECOVERY_FAST_RETX_PER_SEC = 40.0;
    static final double RECOVERY_FAST_NO_RX_PER_SEC = 25.0;
    static final int RECOVERY_FAST_HEALTHY_WINDOWS = 5;
    static final long RECOVERY_FAST_HOLD_MS = 30_000L;
    // 900 -> 1000 is the strictest tier: more windows and a longer hold than the regular
    // 500 -> 900 tier (which keeps the calibrated <24 retx with a relaxed <25 noRx,
    // decision 44, plus the escalating hold).
    static final int BQR_FALLBACK_REQUIRED_HEALTHY_WINDOWS_STRICT = 8;
    /**
     * Strict-tier (900 -> 1000) escalating hold (decision 45, 2026-08-09): the base 120 s
     * stay is unchanged, but a re-trigger within {@link #BQR_FALLBACK_SUCCESS_WINDOW_MS}
     * escalates 120s -> 240s -> 300s. With the one-sided-neutral recovery evidence the
     * strict tier upgrades more easily, so a marginal link must not cycle 900<->1000 at a
     * constant period (feedback 011139).
     */
    static final long[] BQR_FALLBACK_HOLD_MS_STRICT_ESCALATION = {120_000L, 240_000L, 300_000L};

    // Phase N-3 leaky bucket (6.8.3/6.8.9): the deduped choppy soft signal integrates at
    // +10 per event with a linear -0.5/s decay; threshold 15 ≈ 2 events within ~8-10 s
    // (calibrated from the 05:44:34/42 double-burst evidence). Filling downgrades one rung.
    static final double LEAKY_BUCKET_FILL_PER_EVENT = 10.0;
    static final double LEAKY_BUCKET_DECAY_PER_SEC = 0.5;
    static final double LEAKY_BUCKET_THRESHOLD = 15.0;
    /**
     * Phase N-3 (review P1-1): after a leaky recovery, re-triggering is blocked for this
     * long, so an X3-edge environment cannot oscillate 1000<->900 every ~1.5-3 min. This is
     * the trigger-side precursor of the Phase 4 dead zone and must be carried into that
     * design explicitly.
     */
    static final long LEAKY_RETRIGGER_DEAD_ZONE_MS = 60_000L;

    // Phase N-3 8 s leap window (6.8.3): a consistent triple alignment (deduped choppy +
    // sustained high queue + BQR bad window, queue not draining) within 8 s would cross
    // 1000 -> 500 on A devices. Shadow-first: candidates are logged, never applied.
    static final long LEAP_LOOKBACK_MS = 8_000L;
    static final long LEAP_QUEUE_HIGH_ACCUM_MS = 300L;
    static final int LEAP_QUEUE_HIGH_THRESHOLD = 40;

    // Phase N-3 disaster circuit breaker (6.8.3): noRx >= 110/s (single valid window) AND
    // local queue >= 90% for >= 300 ms is a physical block -> 1000->400; retx >= 110/s with
    // low noRx -> 1000->500 (semantic split). No real 110/s samples exist yet, so this runs
    // as a shadow sentinel: hit line + 10 s snapshot, no action.
    static final double DISASTER_NO_RX_PER_SEC = 110.0;
    static final double DISASTER_RETX_PER_SEC = 110.0;
    // Review P2-2: the ring holds the last 4 decision-eligible windows (~24 s at the 6 s
    // cadence), which is wider than the blueprint's "last 10 s" — a superset is safer for
    // calibration. Phase 4 may switch to time-based trimming.
    static final int DISASTER_SNAPSHOT_WINDOWS = 4;
    static final long DISASTER_SHADOW_COOLDOWN_MS = 15_000L;
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

        /**
         * Phase N-2: a BQR window was excluded from downgrade/recovery bookkeeping. Reasons:
         * illegal_fields / start_guard / suspect_invalid. Implementations only log.
         */
        default void onBqrWindowSkipped(
                String mac,
                String reason,
                double retransmissionsPerSecond,
                double noRxPerSecond,
                long nowMs) {
        }

        /**
         * Phase N-3: a shadow-only decision fired (kind = leap_8s / disaster_noRx / disaster_retx).
         * The controller never applies the downgrade; implementations only log for calibration.
         */
        default void onShadowTrigger(
                String mac,
                String kind,
                int fromKbps,
                int toKbps,
                long nowMs,
                double retransmissionsPerSecond,
                double noRxPerSecond,
                int queueLength,
                long queueHighAccumMs,
                int choppyCount5s,
                String snapshot) {
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
        /** Leaky-bucket fallback cap in kbps; 0 when inactive. */
        final int leakyFallbackCapKbps;
        /** BQR recovery streak: counted windows while capped; 0 when inactive. */
        final int bqrFallbackHealthyWindows;
        /** Windows the current BQR tier needs (400->5, 500->6, 900->8); 0 when inactive. */
        final int bqrFallbackRequiredHealthyWindows;
        /** BQR hold remaining (incl. escalation) in ms; 0 when inactive or expired. */
        final long bqrFallbackHoldRemainingMs;
        /** Leaky-bucket recovery streak; 0 when inactive. */
        final int leakyFallbackHealthyWindows;
        /** Leaky-bucket required windows (6); 0 when inactive. */
        final int leakyFallbackRequiredHealthyWindows;
        /** Leaky-bucket hold remaining in ms; 0 when inactive or expired. */
        final long leakyFallbackHoldRemainingMs;

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
                int bqrFallbackCapKbps,
                int leakyFallbackCapKbps,
                int bqrFallbackHealthyWindows,
                int bqrFallbackRequiredHealthyWindows,
                long bqrFallbackHoldRemainingMs,
                int leakyFallbackHealthyWindows,
                int leakyFallbackRequiredHealthyWindows,
                long leakyFallbackHoldRemainingMs) {
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
            this.leakyFallbackCapKbps = leakyFallbackCapKbps;
            this.bqrFallbackHealthyWindows = bqrFallbackHealthyWindows;
            this.bqrFallbackRequiredHealthyWindows = bqrFallbackRequiredHealthyWindows;
            this.bqrFallbackHoldRemainingMs = bqrFallbackHoldRemainingMs;
            this.leakyFallbackHealthyWindows = leakyFallbackHealthyWindows;
            this.leakyFallbackRequiredHealthyWindows = leakyFallbackRequiredHealthyWindows;
            this.leakyFallbackHoldRemainingMs = leakyFallbackHoldRemainingMs;
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
        /**
         * Phase N-4 step evidence: while a fallback cap is active, consecutive bad windows
         * keep accumulating (one rung per trigger, decision 32) so a capped tier can keep
         * stepping down instead of waiting for a recovery that a bad link never grants.
         */
        int bqrFallbackStepBadWindows;
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
        /**
         * Phase N-1 switch transaction: the most recent Target_Cap issued to native and whether
         * it has been confirmed by a native event with the matching requestId. 0 = no pending
         * transaction (IDLE).
         */
        int pendingTargetKbps;
        int pendingRequestId;
        long pendingSinceMs;
        int currentConfirmedKbps;
        /** Phase N-4: absolute dead zone after a downgrade (6.8.4). 0 = not armed. */
        long downgradeDeadZoneUntilMs;
        /**
         * Phase N-2 guards: START_GUARD blocks bad-window accumulation and choppy integration
         * during stream warm-up; POST_SWITCH_GUARD blocks only the choppy soft signal after a
         * Target_Cap switch. 0 = guard not armed.
         */
        long startGuardUntilMs;
        long postSwitchGuardUntilMs;
        /** Count of decision-eligible (valid interval) BQR windows since the last session reset. */
        int validBqrWindowCount;
        // Phase N-3 leaky bucket state.
        double choppyBucket;
        long choppyBucketLastDecayMs;
        int leakyFallbackCapKbps;
        long leakyFallbackSinceMs;
        int leakyFallbackHealthyWindows;
        long leakyRecoveredMs;
        // Phase N-3 8 s leap window state (shadow): high-queue accumulation is sampled on the
        // 200 ms queue tick, so only continuous segments accumulate.
        long lastQueueSampleMs;
        long queueHighAccumMs;
        long lastQueueHighMs;
        long lastLeapShadowMs;
        // Phase N-3 disaster shadow: rolling BQR snapshot for the 10 s pre-trigger record.
        final double[] bqrSnapshotRetx = new double[DISASTER_SNAPSHOT_WINDOWS];
        final double[] bqrSnapshotNoRx = new double[DISASTER_SNAPSHOT_WINDOWS];
        final long[] bqrSnapshotTimes = new long[DISASTER_SNAPSHOT_WINDOWS];
        int bqrSnapshotIndex;
        long lastDisasterShadowMs;
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
        publishCeiling(mac, state, "device_active", nowMs);
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
            publishCeiling(mac, state, "stream_session", nowMs);
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
            state.bqrFallbackStepBadWindows = 0;
            state.downgradeDeadZoneUntilMs = 0L;
            state.bqrFastFailUntilMs = nowMs + BQR_FAST_FAIL_PROBE_WINDOW_MS;
            state.bqrFastFailQueueSinceMs = 0L;
            // A user codec write is an explicit attempt: drop any active leaky-bucket cap
            // the same way the BQR fallback cap is dropped.
            state.leakyFallbackCapKbps = 0;
            state.leakyFallbackHealthyWindows = 0;
            state.leakyRecoveredMs = 0L;
            // Review P2-5: the attempt also clears the bucket and the shadow cooldowns so a
            // fresh user write starts with a clean slate instead of inherited state.
            state.choppyBucket = 0.0;
            state.lastLeapShadowMs = 0L;
            state.lastDisasterShadowMs = 0L;
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
                    reason != null && !reason.isEmpty() ? reason : "peer_ceiling", nowMs);
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
                    reason != null && !reason.isEmpty() ? reason : "session_reset", nowMs);
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
        if (!state.streaming && streaming && state.lastBqrMs != 0L) {
            // Phase N-2: resume (streaming false -> true after the device already streamed)
            // is a new stream start and re-arms the START_GUARD. The very first BQR of a
            // session is not an edge: activate() already armed the guard.
            state.startGuardUntilMs = nowMs + START_GUARD_MS;
        }
        state.streaming = streaming;
        boolean legalWindow = sample.unusedAfhChannels >= 0
                && sample.unusedAfhChannels <= 79
                && sample.retransmissionCount >= 0L
                && sample.noRxCount >= 0L
                && sample.nakCount >= 0L
                && sample.overflowCount >= 0L
                && sample.underflowCount >= 0L;
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
            state.validBqrWindowCount++;
            double seconds = intervalMs / 1000.0;
            state.retransmissionsPerSecond = sample.retransmissionCount / seconds;
            state.noRxPerSecond = sample.noRxCount / seconds;
            strictlyHealthy = streaming
                    && legalWindow
                    && sample.unusedAfhChannels <= MAX_UNUSED_AFH_CHANNELS
                    && state.retransmissionsPerSecond <= MAX_RETRANSMISSIONS_PER_SECOND
                    && state.noRxPerSecond <= MAX_NO_RX_PER_SECOND;
            state.healthyBqrWindows = strictlyHealthy ? state.healthyBqrWindows + 1 : 0;
            if (BuildConfig.LHDC_BQR_FALLBACK) {
                evaluateBqrFallback(mac, state, nowMs, streaming, legalWindow);
                recordBqrSnapshot(state, nowMs);
                evaluateDisasterShadow(mac, state, nowMs);
                if (state.leakyFallbackCapKbps > 0) {
                    // Phase N-3 transitional recovery: 6 healthy windows plus the base hold
                    // restore the rung. Phase 4 replaces this with the full asymmetric
                    // recovery (fast 400->500, long 500->900, strictest 900->1000).
                    // Phase 3 device fixes (feedback 205714/213744): the rung is only one
                    // step down, so recovery evidence is the conservative AND sub-complement
                    // of the bad-window gate (retx<30 && noRx<25, no AFH condition) instead
                    // of the strict BQR-recovery thresholds (<24/<21). A single-sided hot
                    // window (e.g. retx=32/noRx=10) is not a bad window but still resets the
                    // streak (review P2-1). X3 sits in the 24-35 band after interference
                    // stops; the strict gate never accumulated six windows and stranded the
                    // 900 rung (earlier the AFH<=49 gate made it unreachable). The BQR
                    // fallback recovery keeps the calibrated <24 retx; noRx is <25 for the
                    // 500 tier (decision 44) and non-bad <25 for the 900 tier (3a7d64a).
                    if (streaming && legalWindow) {
                        if (nowMs < state.downgradeDeadZoneUntilMs) {
                            // Phase N-4 dead zone (6.8.4): freeze recovery evidence too.
                        } else {
                            // Only decision-eligible windows participate (review P2-1): a
                            // suspended or illegal window keeps the streak instead of
                            // resetting it, matching the BQR fallback early-return semantics.
                            state.leakyFallbackHealthyWindows =
                                    state.retransmissionsPerSecond
                                                    < BQR_FALLBACK_BAD_RETX_PER_SEC
                                            && state.noRxPerSecond
                                                    < BQR_FALLBACK_BAD_NO_RX_PER_SEC
                                    ? state.leakyFallbackHealthyWindows + 1 : 0;
                        }
                    }
                    if (state.leakyFallbackHealthyWindows >= BQR_FALLBACK_REQUIRED_HEALTHY_WINDOWS
                            && nowMs - state.leakyFallbackSinceMs >= BQR_FALLBACK_HOLD_MS[0]) {
                        state.leakyFallbackCapKbps = 0;
                        state.leakyFallbackHealthyWindows = 0;
                        state.leakyRecoveredMs = nowMs;
                        publishCeiling(mac, state, "leaky_bucket_recovered", nowMs);
                    }
                }
                evaluateLeapWindow(mac, state, nowMs);
            }

            BoundaryState probe = inFlightBoundary(state);
            if (legalWindow) {
                updateBqrShadowCandidate(
                        mac, state, sample, nowMs, streaming,
                        probe == null && mac.equals(activeMac));
            }
            if (legalWindow && probe != null) {
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
                                    severe ? "probe_bqr_severe" : "probe_bqr_sustained_bad",
                                    nowMs);
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
                publishCeiling(mac, state, "probe_stream_idle", nowMs);
            }
            return;
        }
        if (mac.equals(activeMac)) maybeOpenRecoveryProbe(mac, state, nowMs);
    }

    synchronized void onQueueSample(String mac, int length, int capacity, long nowMs) {
        if (mac == null || capacity <= 0 || length < 0) return;
        DeviceState state = stateFor(mac);
        if (!state.streaming && state.lastBqrMs != 0L) {
            // Phase N-2: resume (queue sampling restarts after an A2DP suspend on a device
            // that already streamed) re-arms the START_GUARD.
            state.startGuardUntilMs = nowMs + START_GUARD_MS;
        }
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
                        full ? "probe_queue_full" : "probe_queue_congested", nowMs);
            }
        } else {
            maybeCancelDegradedProbe(mac, state, nowMs);
        }
        if (BuildConfig.LHDC_BQR_FALLBACK) {
            evaluateBqrQueueFastFail(mac, state, length, nowMs);
            // Phase N-3: 8 s leap window accumulates sustained high queue (continuous
            // segments only, so the startup fill-and-drain transient cannot accumulate).
            if (state.lastQueueSampleMs != 0L) {
                long gapMs = nowMs - state.lastQueueSampleMs;
                if (length >= LEAP_QUEUE_HIGH_THRESHOLD) {
                    if (gapMs <= 1_000L) {
                        state.queueHighAccumMs += gapMs;
                    } else {
                        state.queueHighAccumMs = 0L;
                    }
                    state.lastQueueHighMs = nowMs;
                } else {
                    // Review P2-1: a drained queue must not keep a stale high-accumulation
                    // value that a later single-sample spike could revive.
                    state.queueHighAccumMs = 0L;
                }
            }
            state.lastQueueSampleMs = nowMs;
            decayLeakyBucket(state, nowMs);
        }
        if (mac.equals(activeMac)) maybeOpenRecoveryProbe(mac, state, nowMs);
    }

    synchronized void onCongestion(String mac, long nowMs) {
        if (mac == null) return;
        DeviceState state = stateFor(mac);
        noteCongestion(state, nowMs);
        if (cancelRecoveryProbe(state, nowMs, false)) {
            publishCeiling(mac, state, "probe_choppy", nowMs);
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
        if (nowMs < state.startGuardUntilMs || nowMs < state.postSwitchGuardUntilMs) {
            // Phase N-2 guards: choppy during stream warm-up (START_GUARD) or right after a
            // Target_Cap switch (POST_SWITCH_GUARD) is recorded but not integrated, so a
            // startup spike or a switch-transient glitch cannot cancel recovery probes or
            // count as congestion. Hard evidence paths (queue fast-fail, Phase 3 disaster)
            // are not blocked here.
            return;
        }
        if (BuildConfig.LHDC_BQR_FALLBACK) {
            // Phase N-3 leaky bucket: decay first, then fill (+10 per deduped event). A full
            // bucket downgrades one rung (decision 30 keeps choppy independently effective).
            decayLeakyBucket(state, nowMs);
            if (state.leakyFallbackCapKbps == 0 && state.bqrFallbackCapKbps == 0) {
                // Review P2-3: while any downgrade cap is active the bucket does not integrate,
                // so a recovery is not immediately followed by a re-trigger.
                state.choppyBucket += LEAKY_BUCKET_FILL_PER_EVENT;
                evaluateLeakyBucket(mac, state, nowMs);
            }
        }
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
            publishCeiling(mac, state,
                    boundary.locked ? "boundary_locked" : "quick_failure", nowMs);
        } else if (event == EVENT_UPGRADE_STABLE) {
            clearBoundary(boundary);
            state.probeStableBqrWindows = 0;
            state.probeBadBqrWindows = 0;
            publishCeiling(mac, state, "boundary_stable", nowMs);
        }
    }

    // Kept for source compatibility with older tests/callers that do not carry event detail.
    synchronized void onGovernorEvent(
            String mac, int event, int fromKbps, int toKbps, long nowMs) {
        onGovernorEvent(mac, event, fromKbps, toKbps, 0L, nowMs);
    }

    static final class PendingTransaction {
        final int targetKbps;
        final int requestId;
        final long sinceMs;

        PendingTransaction(int targetKbps, int requestId, long sinceMs) {
            this.targetKbps = targetKbps;
            this.requestId = requestId;
            this.sinceMs = sinceMs;
        }
    }

    /**
     * Phase N-1 requestId transaction: records that a Target_Cap write was issued to native.
     * The transaction stays open until a native event confirms it with the matching requestId
     * or {@link #tickSwitchTransactions} times it out.
     */
    synchronized void onTargetCapIssued(
            String mac, int targetKbps, int requestId, long nowMs) {
        if (mac == null || requestId <= 0 || targetKbps <= 0) return;
        DeviceState state = stateFor(mac);
        state.pendingTargetKbps = targetKbps;
        state.pendingRequestId = requestId;
        state.pendingSinceMs = nowMs;
    }

    /**
     * Phase N-1 requestId transaction: a native transition confirmed the requested rung. Only
     * the latest transaction's requestId is accepted; stale confirmations are ignored.
     */
    synchronized void onTransitionConfirmed(
            String mac, int toKbps, int requestId, long nowMs) {
        if (mac == null || requestId <= 0) return;
        DeviceState state = devices.get(mac);
        if (state == null || state.pendingRequestId != requestId) return;
        state.currentConfirmedKbps = toKbps;
        state.pendingTargetKbps = 0;
        state.pendingRequestId = 0;
        state.pendingSinceMs = 0L;
    }

    /**
     * Phase N-1 requestId transaction: called on the 200 ms queue tick. Returns the pending
     * transaction when it exceeded {@link #SWITCH_CONFIRM_TIMEOUT_MS} without a native
     * confirmation, so the caller can fall back to the getter for the real bitrate.
     */
    synchronized PendingTransaction tickSwitchTransactions(String mac, long nowMs) {
        DeviceState state = devices.get(mac);
        if (state == null || state.pendingRequestId == 0) return null;
        if (nowMs - state.pendingSinceMs < SWITCH_CONFIRM_TIMEOUT_MS) return null;
        PendingTransaction timedOut = new PendingTransaction(
                state.pendingTargetKbps, state.pendingRequestId, state.pendingSinceMs);
        state.pendingTargetKbps = 0;
        state.pendingRequestId = 0;
        state.pendingSinceMs = 0L;
        return timedOut;
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
                    choppyCapabilityState, 0L, 0L, 0, 0, 0, -1L, 0L, 0, 0,
                    0, 0, 0L, 0, 0, 0L);
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
        int bqrRequired = state.bqrFallbackCapKbps > 0
                ? requiredWindowsForBqrCap(state.bqrFallbackCapKbps) : 0;
        long bqrHoldRemaining = state.bqrFallbackCapKbps > 0
                ? Math.max(0L, state.bqrFallbackSinceMs
                        + tierHoldMsForBqrCap(state.bqrFallbackCapKbps, state) - nowMs)
                : 0L;
        int leakyRequired = state.leakyFallbackCapKbps > 0
                ? BQR_FALLBACK_REQUIRED_HEALTHY_WINDOWS : 0;
        long leakyHoldRemaining = state.leakyFallbackCapKbps > 0
                ? Math.max(0L, state.leakyFallbackSinceMs
                        + BQR_FALLBACK_HOLD_MS[0] - nowMs)
                : 0L;
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
                state.bqrFallbackCapKbps,
                state.leakyFallbackCapKbps,
                state.bqrFallbackHealthyWindows,
                bqrRequired,
                bqrHoldRemaining,
                state.leakyFallbackHealthyWindows,
                leakyRequired,
                leakyHoldRemaining);
    }

    /** Phase 5 countdown UI: required recovery windows per BQR cap tier. */
    private static int requiredWindowsForBqrCap(int capKbps) {
        if (capKbps <= 400) return RECOVERY_FAST_HEALTHY_WINDOWS;
        if (capKbps <= 500) return BQR_FALLBACK_REQUIRED_HEALTHY_WINDOWS;
        return BQR_FALLBACK_REQUIRED_HEALTHY_WINDOWS_STRICT;
    }

    /** Phase 5 countdown UI: effective hold (incl. escalation) for the active BQR tier. */
    private static long tierHoldMsForBqrCap(int capKbps, DeviceState state) {
        if (capKbps <= 400) return RECOVERY_FAST_HOLD_MS;
        if (capKbps <= 500) return bqrFallbackHoldMs(state);
        return strictFallbackHoldMs(state);
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
        publishCeiling(mac, state, "healthy_recovery_probe", nowMs);
    }

    private void maybeCancelDegradedProbe(String mac, DeviceState state, long nowMs) {
        BoundaryState probe = inFlightBoundary(state);
        if (probe == null
                || state.probeBadBqrWindows < MAX_CONSECUTIVE_PROBE_BAD_WINDOWS
                || nowMs - probe.probeOpenedMs < MIN_PROBE_OPEN_MS) {
            return;
        }
        if (cancelRecoveryProbe(state, nowMs, false)) {
            publishCeiling(mac, state, "probe_bqr_sustained_bad", nowMs);
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

    private void publishCeiling(String mac, DeviceState state, String reason, long nowMs) {
        if (!mac.equals(activeMac)) return;
        int ceiling = effectiveCeiling(state);
        if (state.lastPublishedCeiling == ceiling) {
            if (listener != null) listener.onProbeStateChanged(mac, ceiling, reason);
            return;
        }
        state.lastPublishedCeiling = ceiling;
        // Phase N-2 POST_SWITCH_GUARD: a real Target_Cap decision arms the switch-transient
        // window in which the choppy soft signal is recorded but not integrated. Pure status
        // re-publishes (activation / stream rebuild / teardown) are not Target_Cap switches
        // and are already covered by the longer START_GUARD (review P2-2).
        if (!"device_active".equals(reason)
                && !"stream_session".equals(reason)
                && !"session_reset".equals(reason)) {
            state.postSwitchGuardUntilMs = nowMs + POST_SWITCH_GUARD_MS;
        }
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
        if (state.leakyFallbackCapKbps > 0 && state.leakyFallbackCapKbps < ceiling) {
            ceiling = state.leakyFallbackCapKbps;
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
        evaluateBqrFallback(mac, state, nowMs, streaming, true);
    }

    private void evaluateBqrFallback(
            String mac, DeviceState state, long nowMs, boolean streaming, boolean legalWindow) {
        if (!streaming) return;
        double retx = state.retransmissionsPerSecond;
        double noRx = state.noRxPerSecond;
        if (Double.isNaN(retx) || Double.isNaN(noRx)) return;
        if (!legalWindow) {
            // Phase N-2 BQR valid gate: illegal fields must never drive downgrade bookkeeping.
            if (listener != null) {
                listener.onBqrWindowSkipped(mac, "illegal_fields", retx, noRx, nowMs);
            }
            return;
        }

        // Phase N-2 BQR_SUSPECT_INVALID: high retx with ~zero noRx is physically
        // contradictory; with a low queue, no choppy and stream warm-up it is logged and
        // never allowed to participate in decisions. Logged for every device (including
        // peer-capped ones) because it is the calibration sample source for the Phase 3
        // disaster threshold (review P2-4).
        if (retx >= BQR_SUSPECT_RETX_PER_SEC
                && noRx < 1.0
                && (state.currentQueueLength < 0
                        || state.currentQueueLength * 100L
                                <= (long) state.queueCapacity * 25L)
                && (state.remoteChoppyWindowStartMs == 0L
                        || nowMs - state.remoteChoppyWindowStartMs > 5_000L)
                && state.validBqrWindowCount <= 2) {
            if (listener != null) {
                listener.onBqrWindowSkipped(mac, "suspect_invalid", retx, noRx, nowMs);
            }
            return;
        }
        if (state.peerCeilingKbps > 0 && state.peerCeilingKbps <= BQR_FALLBACK_CAP_KBPS) return;

        boolean bad = retx >= BQR_FALLBACK_BAD_RETX_PER_SEC
                && noRx >= BQR_FALLBACK_BAD_NO_RX_PER_SEC;

        if (state.bqrFallbackCapKbps == 0) {
            if (bad) {
                if (nowMs < state.downgradeDeadZoneUntilMs) {
                    // Phase N-4 review P1-3: the dead zone also freezes the uncapped bad
                    // streak, so a leaky downgrade is not immediately followed by a BQR
                    // step from borrowed pre-downgrade windows.
                    if (listener != null) {
                        listener.onBqrWindowSkipped(mac, "dead_zone", retx, noRx, nowMs);
                    }
                } else if (nowMs < state.startGuardUntilMs) {
                    // Phase N-2 START_GUARD: bad windows do not participate in slow-heat
                    // counting during stream warm-up; they are only recorded.
                    if (listener != null) {
                        listener.onBqrWindowSkipped(mac, "start_guard", retx, noRx, nowMs);
                    }
                } else {
                    state.bqrFallbackBadWindows++;
                }
            } else {
                state.bqrFallbackBadWindows = 0;
            }
            if (state.bqrFallbackBadWindows >= BQR_FALLBACK_REQUIRED_BAD_WINDOWS) {
                int windows = state.bqrFallbackBadWindows;
                triggerBqrFallback(mac, state, nowMs, "triggered", windows, false);
            }
            return;
        }

        if (nowMs < state.downgradeDeadZoneUntilMs) {
            // Phase N-4 dead zone (6.8.4): no recovery evidence during the old bitrate's
            // tail — windows are frozen until the dead zone ends.
            if (listener != null) {
                listener.onBqrWindowSkipped(mac, "dead_zone", retx, noRx, nowMs);
            }
            return;
        }
        int cap = state.bqrFallbackCapKbps;
        if (bad) {
            // Phase N-4 step evidence (decision 32): while capped, consecutive bad windows
            // keep accumulating so the tier steps down again instead of waiting for a
            // recovery that a persistently bad link never grants.
            // START_GUARD excludes warm-up pseudo-high windows from the step evidence,
            // matching the uncapped path (review P2-2).
            if (nowMs >= state.startGuardUntilMs) {
                state.bqrFallbackStepBadWindows++;
            }
            state.bqrFallbackHealthyWindows = 0;
        } else {
            state.bqrFallbackStepBadWindows = 0;
            // Phase N-4 asymmetric recovery (6.8.5): one rung up per tier.
            boolean tierHealthy;
            int requiredWindows;
            long holdMs;
            if (cap <= 400) {
                // Fast channel 400 -> 500: relaxed evidence (retx<=40/noRx<=25), short stay.
                tierHealthy = retx <= RECOVERY_FAST_RETX_PER_SEC
                        && noRx <= RECOVERY_FAST_NO_RX_PER_SEC;
                requiredWindows = RECOVERY_FAST_HEALTHY_WINDOWS;
                holdMs = RECOVERY_FAST_HOLD_MS;
            } else if (cap <= 500) {
                // Regular tier 500 -> 900: retx keeps the calibrated <24, noRx relaxes to
                // <25 (decision 44) + escalating hold. A one-sided hot window (noRx >= 25)
                // still resets the streak, and the bad gate (>=30/>=25) is unchanged.
                tierHealthy = retx < BQR_FALLBACK_HEALTHY_RETX_PER_SEC
                        && noRx < BQR_FALLBACK_HEALTHY_NO_RX_MID_PER_SEC;
                requiredWindows = BQR_FALLBACK_REQUIRED_HEALTHY_WINDOWS;
                holdMs = bqrFallbackHoldMs(state);
            } else {
                // Strictest tier 900 -> 1000: 8 windows at non-bad evidence + escalating
                // hold 120s -> 240s -> 300s (decision 45). One-sided hot windows (only
                // retx>=30 or only noRx>=25) are neutral for this tier: feedback 011139
                // showed this headset's 900-tier retx band (26-42) straddles the 30 gate
                // while noRx stays clean, so resetting on one-sided windows made
                // 900->1000 unreachable even in a tolerable environment. Strictness now
                // lives in the 8-window count, the escalating hold and the true-bad reset.
                tierHealthy = retx < BQR_FALLBACK_BAD_RETX_PER_SEC
                        && noRx < BQR_FALLBACK_BAD_NO_RX_PER_SEC;
                requiredWindows = BQR_FALLBACK_REQUIRED_HEALTHY_WINDOWS_STRICT;
                holdMs = strictFallbackHoldMs(state);
            }
            if (cap >= 900) {
                // Decision 45: the bad branch above already reset the streak for true bad
                // windows; a one-sided hot window keeps the streak without counting.
                if (tierHealthy) {
                    state.bqrFallbackHealthyWindows++;
                }
            } else {
                state.bqrFallbackHealthyWindows =
                        tierHealthy ? state.bqrFallbackHealthyWindows + 1 : 0;
            }
            if (state.bqrFallbackHealthyWindows >= requiredWindows
                    && nowMs - state.bqrFallbackSinceMs >= holdMs) {
                int windows = state.bqrFallbackHealthyWindows;
                state.bqrFallbackHealthyWindows = 0;
                // One rung up: 400->500, 500->900 (or straight to the peer ceiling on
                // peer-capped devices), 900->peer ceiling.
                state.bqrFallbackCapKbps = cap <= 400
                        ? 500
                        : cap <= 500 && state.peerCeilingKbps > 0
                                && state.peerCeilingKbps <= 900
                        ? 0
                        : cap <= 500 ? 900 : 0;
                if (state.bqrFallbackCapKbps > 0) {
                    // Phase N-4 review P1-2: a partial recovery enters a new tier, so the
                    // next tier's hold counts from now — otherwise a 1000->900->500->900
                    // path would enter the strict 900 tier with a stale sinceMs and only
                    // hold ~60 s instead of the blueprint's 2-3 min.
                    state.bqrFallbackSinceMs = nowMs;
                }
                state.bqrFallbackRecoveredMs = nowMs;
                state.bqrFastFailUntilMs = nowMs + BQR_FAST_FAIL_PROBE_WINDOW_MS;
                state.bqrFastFailQueueSinceMs = 0L;
                publishCeiling(mac, state, "bqr_fallback_recovered", nowMs);
                notifyBqrFallback(mac, state, "recovered", 0, windows, holdMs);
            }
        }
        if (state.bqrFallbackStepBadWindows >= BQR_FALLBACK_REQUIRED_BAD_WINDOWS) {
            state.bqrFallbackStepBadWindows = 0;
            triggerBqrFallback(mac, state, nowMs, "triggered",
                    BQR_FALLBACK_REQUIRED_BAD_WINDOWS, false);
        }
    }

    /**
     * Phase N-3 leaky bucket: linear decay -0.5/s, driven by choppy events and the 200 ms
     * queue tick so a burst that stops decays back to zero instead of lingering.
     */
    private void decayLeakyBucket(DeviceState state, long nowMs) {
        if (state.choppyBucket <= 0.0) {
            state.choppyBucket = 0.0;
            state.choppyBucketLastDecayMs = nowMs;
            return;
        }
        if (state.choppyBucketLastDecayMs == 0L) {
            state.choppyBucketLastDecayMs = nowMs;
            return;
        }
        long elapsedMs = nowMs - state.choppyBucketLastDecayMs;
        if (elapsedMs <= 0L) return;
        state.choppyBucket = Math.max(0.0,
                state.choppyBucket - elapsedMs / 1000.0 * LEAKY_BUCKET_DECAY_PER_SEC);
        state.choppyBucketLastDecayMs = nowMs;
    }

    /**
     * Phase N-3 leaky bucket trigger: a full bucket downgrades one rung (1000 -> 900, or
     * 900 -> 500 on a peer-capped device). 400 stays reserved for the disaster tier. The
     * transitional recovery (healthy windows + base hold) lives in onBqrSample; Phase 4
     * replaces it with the full asymmetric recovery.
     */
    private void evaluateLeakyBucket(String mac, DeviceState state, long nowMs) {
        if (state.choppyBucket < LEAKY_BUCKET_THRESHOLD) return;
        if (state.leakyFallbackCapKbps > 0) return;
        if (state.leakyRecoveredMs != 0L
                && nowMs - state.leakyRecoveredMs < LEAKY_RETRIGGER_DEAD_ZONE_MS) {
            // Review P1-1: re-trigger dead zone after a recovery prevents 1000<->900
            // oscillation in edge environments. Phase 4 generalizes this into the dead zone.
            return;
        }
        int ceiling = effectiveCeiling(state);
        if (ceiling <= 500) return;
        int target = ceiling >= 1000 ? 900 : 500;
        state.leakyFallbackCapKbps = target;
        state.leakyFallbackSinceMs = nowMs;
        state.leakyFallbackHealthyWindows = 0;
        state.choppyBucket = 0.0;
        // Phase N-4 review P1-3 (6.8.4): a downgrade clears the uncapped BQR bad streak so
        // the next tier needs fresh evidence.
        state.bqrFallbackBadWindows = 0;
        state.downgradeDeadZoneUntilMs = nowMs + DOWNGRADE_DEAD_ZONE_MS;
        publishCeiling(mac, state, "leaky_bucket_triggered", nowMs);
    }

    private void recordBqrSnapshot(DeviceState state, long nowMs) {
        if (!state.streaming) return;  // review P1-1: suspended-window pseudo-high must not pollute
        int i = state.bqrSnapshotIndex % DISASTER_SNAPSHOT_WINDOWS;
        state.bqrSnapshotTimes[i] = nowMs;
        state.bqrSnapshotRetx[i] = state.retransmissionsPerSecond;
        state.bqrSnapshotNoRx[i] = state.noRxPerSecond;
        state.bqrSnapshotIndex++;
    }

    /**
     * Phase N-3 disaster circuit breaker (shadow sentinel): noRx >= 110/s AND queue >= 90%
     * for >= 300 ms -> 1000->400 (receiver deaf); retx >= 110/s with low noRx -> 1000->500
     * (still talking). No real 110/s samples exist, so this only hits the line and records
     * the pre-trigger snapshot for threshold calibration.
     */
    private void evaluateDisasterShadow(String mac, DeviceState state, long nowMs) {
        if (!state.streaming) return;  // review P1-1: suspended accumulation is not a disaster
        if (state.bqrSnapshotIndex == 0) return;
        if (state.lastDisasterShadowMs != 0L
                && nowMs - state.lastDisasterShadowMs < DISASTER_SHADOW_COOLDOWN_MS) return;
        boolean queueSustainedCritical = state.criticalQueueSinceMs != 0L
                && nowMs - state.criticalQueueSinceMs >= CRITICAL_QUEUE_HOLD_MS;
        if (!queueSustainedCritical) return;
        if (effectiveCeiling(state) < 1000) return;
        String kind = null;
        int toKbps = 0;
        if (state.noRxPerSecond >= DISASTER_NO_RX_PER_SEC) {
            kind = "disaster_noRx";
            toKbps = 400;
        } else if (state.retransmissionsPerSecond >= DISASTER_RETX_PER_SEC
                && state.noRxPerSecond < BQR_FALLBACK_BAD_NO_RX_PER_SEC) {
            kind = "disaster_retx";
            toKbps = 500;
        }
        if (kind == null) return;
        state.lastDisasterShadowMs = nowMs;
        if (listener != null) {
            listener.onShadowTrigger(
                    mac, kind, 1000, toKbps, nowMs,
                    state.retransmissionsPerSecond, state.noRxPerSecond,
                    state.currentQueueLength, state.queueHighAccumMs,
                    remoteChoppyCount5s(state, nowMs),
                    bqrSnapshotText(state, nowMs));
        }
    }

    /**
     * Phase N-3 8 s leap window (shadow-first): a BQR bad window aligned within 8 s with a
     * deduped choppy event AND >= 300 ms of sustained high queue AND the queue still not
     * draining would cross 1000 -> 500 on A devices. Only logged for calibration.
     */
    private void evaluateLeapWindow(String mac, DeviceState state, long nowMs) {
        if (!state.streaming) return;  // review P1-1: stale queue/choppy from a suspend must not align
        if (nowMs < state.startGuardUntilMs) return;
        if (nowMs < state.downgradeDeadZoneUntilMs) return;  // Phase N-4 dead zone
        if (state.lastLeapShadowMs != 0L
                && nowMs - state.lastLeapShadowMs < SHADOW_CANDIDATE_COOLDOWN_MS) return;
        double retx = state.retransmissionsPerSecond;
        double noRx = state.noRxPerSecond;
        if (Double.isNaN(retx) || Double.isNaN(noRx)) return;
        if (retx < BQR_FALLBACK_BAD_RETX_PER_SEC || noRx < BQR_FALLBACK_BAD_NO_RX_PER_SEC) return;
        if (effectiveCeiling(state) < 1000) return;  // A devices only
        long choppyAgoMs = state.lastRemoteChoppyMs == 0L
                ? -1L : nowMs - state.lastRemoteChoppyMs;
        boolean choppyRecent = choppyAgoMs >= 0L && choppyAgoMs <= LEAP_LOOKBACK_MS;
        boolean queueHighRecent = state.queueHighAccumMs >= LEAP_QUEUE_HIGH_ACCUM_MS
                && state.lastQueueHighMs != 0L
                && nowMs - state.lastQueueHighMs <= 2_000L;
        boolean queueNotDraining = state.currentQueueLength >= LEAP_QUEUE_HIGH_THRESHOLD;
        if (!choppyRecent || !queueHighRecent || !queueNotDraining) return;
        state.lastLeapShadowMs = nowMs;
        if (listener != null) {
            listener.onShadowTrigger(
                    mac, "leap_8s", 1000, 500, nowMs, retx, noRx,
                    state.currentQueueLength, state.queueHighAccumMs,
                    remoteChoppyCount5s(state, nowMs), "");
        }
    }

    private static int remoteChoppyCount5s(DeviceState state, long nowMs) {
        return state.remoteChoppyWindowStartMs != 0L
                && nowMs - state.remoteChoppyWindowStartMs <= 5_000L
                ? state.remoteChoppyCount : 0;
    }

    private String bqrSnapshotText(DeviceState state, long nowMs) {
        StringBuilder sb = new StringBuilder();
        int count = Math.min(state.bqrSnapshotIndex, DISASTER_SNAPSHOT_WINDOWS);
        for (int i = 0; i < count; i++) {
            int idx = (state.bqrSnapshotIndex - count + i) % DISASTER_SNAPSHOT_WINDOWS;
            if (sb.length() > 0) sb.append(';');
            sb.append(String.format(Locale.ROOT, "r%.1f/n%.1f@%d",
                    state.bqrSnapshotRetx[idx], state.bqrSnapshotNoRx[idx],
                    state.bqrSnapshotTimes[idx]));
        }
        return sb.toString();
    }

    private void triggerBqrFallback(
            String mac,
            DeviceState state,
            long nowMs,
            String notifyReason,
            int badWindows,
            boolean queueFastFail) {
        state.bqrFallbackBadWindows = 0;
        state.bqrFallbackStepBadWindows = 0;
        // Phase N-4 (6.8.3/decision 32): one rung per trigger — 1000->900, 900->500.
        // 400 stays reserved for the disaster tier; the bucket is already cleared.
        state.bqrFallbackCapKbps = nextLowerRung(effectiveCeiling(state));
        state.bqrFallbackSinceMs = nowMs;
        state.bqrFastFailUntilMs = 0L;
        state.bqrFastFailQueueSinceMs = 0L;
        // Review P2-3: any downgrade is a success under the 6.8.4 semantics — clear the
        // choppy bucket so the recovery is not immediately re-triggered by stale fill.
        state.choppyBucket = 0.0;
        if (state.bqrFallbackRecoveredMs != 0L) {
            if (nowMs - state.bqrFallbackRecoveredMs <= BQR_FALLBACK_SUCCESS_WINDOW_MS) {
                // The previous 900 phase died shortly after a recovery: failed probe, escalate.
                // Clamp to the shorter escalation table so both tiers stay in bounds.
                state.bqrFallbackEscalationLevel = Math.min(
                        Math.min(BQR_FALLBACK_HOLD_MS.length,
                                BQR_FALLBACK_HOLD_MS_STRICT_ESCALATION.length) - 1,
                        state.bqrFallbackEscalationLevel + 1);
            } else {
                // The previous 900 phase survived long enough: start over with the base hold.
                state.bqrFallbackEscalationLevel = 0;
            }
        } else {
            state.bqrFallbackEscalationLevel = 0;
        }
        // Phase N-4 dead zone (6.8.4): recovery evidence must not start from the old
        // bitrate's tail.
        state.downgradeDeadZoneUntilMs = nowMs + DOWNGRADE_DEAD_ZONE_MS;
        publishCeiling(mac, state,
                queueFastFail ? "bqr_fallback_triggered_queue" : "bqr_fallback_triggered",
                nowMs);
        notifyBqrFallback(mac, state, notifyReason, badWindows, 0);
    }

    /** Phase N-4: the rung below the current effective ceiling; 500 is the slow-heat floor. */
    private static int nextLowerRung(int ceilingKbps) {
        if (ceilingKbps >= 1000) return 900;
        if (ceilingKbps >= 900) return 500;
        return 500;
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
        // Phase N-4 review P1-1: no cap early-return. A capped tier (500->900 recovery)
        // must still be queue-protected during its probe window; cap 500/400 tiers are
        // already excluded by the effectiveCeiling < 900 gate below.
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
        notifyBqrFallback(mac, state, reason, badWindows, healthyWindows,
                state.bqrFallbackCapKbps >= 900
                        ? strictFallbackHoldMs(state)
                        : bqrFallbackHoldMs(state));
    }

    private void notifyBqrFallback(
            String mac, DeviceState state, String reason, int badWindows, int healthyWindows,
            long holdMs) {
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
                holdMs);
    }

    private static long bqrFallbackHoldMs(DeviceState state) {
        return BQR_FALLBACK_HOLD_MS[Math.max(0,
                Math.min(state.bqrFallbackEscalationLevel, BQR_FALLBACK_HOLD_MS.length - 1))];
    }

    private static long strictFallbackHoldMs(DeviceState state) {
        return BQR_FALLBACK_HOLD_MS_STRICT_ESCALATION[Math.max(0,
                Math.min(state.bqrFallbackEscalationLevel,
                        BQR_FALLBACK_HOLD_MS_STRICT_ESCALATION.length - 1))];
    }

    /**
     * Test-only hook: forces the BQR fallback cap so the recovery tiers (including the
     * disaster 400 rung, which has no live trigger while the sentinel is shadow-only) can be
     * verified without a real downgrade sequence.
     */
    synchronized void setBqrFallbackCapKbpsForTest(
            String mac, int capKbps, long nowMs) {
        DeviceState state = stateFor(mac);
        state.bqrFallbackCapKbps = capKbps;
        state.bqrFallbackSinceMs = nowMs;
        state.bqrFallbackHealthyWindows = 0;
        state.bqrFallbackRecoveredMs = 0L;
        state.bqrFallbackEscalationLevel = 0;
        state.downgradeDeadZoneUntilMs = 0L;
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
        // Phase N-3: do NOT reset the sustained-critical timer here. Congestion events
        // (choppy, full queue) are exactly the conditions the disaster circuit breaker
        // needs to keep accumulating; the timer is queue-driven and reset only when the
        // queue leaves the critical band or the stream idles (review: pre-existing bug
        // exposed by the Phase 3 disaster path).
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
        state.pendingTargetKbps = 0;
        state.pendingRequestId = 0;
        state.pendingSinceMs = 0L;
        state.currentConfirmedKbps = 0;
        state.downgradeDeadZoneUntilMs = 0L;
        // Phase N-2: every stream start (first play / connect / stream rebuild) arms the
        // START_GUARD. Resume within a session re-arms it through the streaming edge.
        state.startGuardUntilMs = nowMs + START_GUARD_MS;
        state.postSwitchGuardUntilMs = 0L;
        state.validBqrWindowCount = 0;
        state.choppyBucket = 0;
        state.choppyBucketLastDecayMs = nowMs;
        state.leakyFallbackCapKbps = 0;
        state.leakyFallbackSinceMs = 0L;
        state.leakyFallbackHealthyWindows = 0;
        state.leakyRecoveredMs = 0L;
        state.lastQueueSampleMs = 0L;
        state.queueHighAccumMs = 0L;
        state.lastQueueHighMs = 0L;
        state.lastLeapShadowMs = 0L;
        state.bqrSnapshotIndex = 0;
        state.lastDisasterShadowMs = 0L;
        state.lastBqrMs = 0L;
        state.lastValidBqrMs = 0L;
        state.healthyBqrWindows = 0;
        state.retransmissionsPerSecond = Double.NaN;
        state.noRxPerSecond = Double.NaN;
        state.bqrFallbackEscalationLevel = 0;
        state.bqrFallbackRecoveredMs = 0L;
        state.bqrFallbackStepBadWindows = 0;
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
