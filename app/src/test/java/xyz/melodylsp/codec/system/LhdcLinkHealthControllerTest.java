package xyz.melodylsp.codec.system;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public final class LhdcLinkHealthControllerTest {

    private static final String MAC = "AA:BB:CC:DD:EE:01";

    @Test
    public void twoQuickFailuresLockOnlyTheirBoundaryUntilHealthEvidenceReturns() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);

        controller.onGovernorEvent(MAC, LhdcLinkHealthController.EVENT_QUICK_FAILURE,
                500, 900, 1_000L);
        assertEquals(1000, controller.snapshot(MAC, 1_000L).ceilingKbps);

        controller.onGovernorEvent(MAC, LhdcLinkHealthController.EVENT_QUICK_FAILURE,
                500, 900, 2_000L);
        LhdcLinkHealthController.Snapshot snapshot = controller.snapshot(MAC, 2_000L);
        assertTrue(snapshot.boundary500To900Locked);
        assertFalse(snapshot.boundary900To1000Locked);
        assertEquals(500, snapshot.ceilingKbps);
        assertEquals(3, snapshot.requiredHealthyBqrWindows);
        assertEquals(15_000L, snapshot.requiredQuietMs);
        assertEquals("1000:device_active", recorder.events.get(0));
        assertEquals("500:boundary_locked", recorder.events.get(1));
    }

    @Test
    public void failuresAreIndependentAcrossUpgradeBoundaries() {
        LhdcLinkHealthController controller = new LhdcLinkHealthController(null);
        controller.activate(MAC, 100L);
        controller.onGovernorEvent(MAC, LhdcLinkHealthController.EVENT_QUICK_FAILURE,
                500, 900, 1_000L);
        controller.onGovernorEvent(MAC, LhdcLinkHealthController.EVENT_QUICK_FAILURE,
                900, 1000, 2_000L);
        assertEquals(1000, controller.snapshot(MAC, 2_000L).ceilingKbps);

        controller.onGovernorEvent(MAC, LhdcLinkHealthController.EVENT_QUICK_FAILURE,
                900, 1000, 3_000L);
        LhdcLinkHealthController.Snapshot snapshot = controller.snapshot(MAC, 3_000L);
        assertFalse(snapshot.boundary500To900Locked);
        assertTrue(snapshot.boundary900To1000Locked);
        assertEquals(900, snapshot.ceilingKbps);
    }

    @Test
    public void recoveryRequiresFreshHealthyBqrAndQuietQueue() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = lockedTo500(recorder);
        controller.onBqrSample(MAC, healthyBqr(), 10_000L);
        controller.onQueueSample(MAC, 0, 45, 10_100L);
        controller.onBqrSample(MAC, healthyBqr(), 16_000L);
        controller.onBqrSample(MAC, healthyBqr(), 22_000L);
        controller.onQueueSample(MAC, 30, 45, 27_500L);
        controller.onBqrSample(MAC, healthyBqr(), 28_000L);
        assertEquals(500, controller.snapshot(MAC, 28_000L).ceilingKbps);

        controller.onQueueSample(MAC, 0, 45, 28_100L);
        controller.onBqrSample(MAC, healthyBqr(), 34_000L);
        controller.onBqrSample(MAC, healthyBqr(), 40_000L);
        controller.onBqrSample(MAC, healthyBqr(), 46_000L);
        LhdcLinkHealthController.Snapshot snapshot = controller.snapshot(MAC, 46_000L);
        assertEquals(900, snapshot.ceilingKbps);
        assertEquals("probing", snapshot.probePhase);
        assertEquals("waiting_native_upgrade", snapshot.blockedReason);
        assertEquals("900:healthy_recovery_probe",
                recorder.events.get(recorder.events.size() - 1));
    }

    @Test
    public void currentBadEnvironmentDoesNotPassHealthyGate() {
        LhdcLinkHealthController controller = lockedTo500(null);
        controller.onBqrSample(MAC, unhealthyCurrentEnvironment(), 10_000L);
        controller.onQueueSample(MAC, 0, 45, 10_100L);
        for (int i = 1; i <= 5; i++) {
            controller.onBqrSample(MAC, unhealthyCurrentEnvironment(),
                    10_000L + i * 6_000L);
        }
        controller.onQueueSample(MAC, 0, 45, 40_100L);
        LhdcLinkHealthController.Snapshot snapshot = controller.snapshot(MAC, 40_100L);
        assertEquals(500, snapshot.ceilingKbps);
        assertEquals(0, snapshot.healthyBqrWindows);
        assertEquals(20, snapshot.usableAfhChannels);
        assertTrue(snapshot.retransmissionsPerSecond > 60.0);
        assertTrue(snapshot.noRxPerSecond > 60.0);
        assertEquals("waiting_healthy_bqr", snapshot.blockedReason);
    }

    @Test
    public void failedRecoveryProbeEscalatesEvidenceAndCarriesNativeBackoff() {
        LhdcLinkHealthController controller = lockedTo500(null);
        long probeAt = openHealthyProbe(controller);
        controller.onGovernorEvent(MAC, LhdcLinkHealthController.EVENT_UPGRADE_APPLIED,
                500, 900, 0L, probeAt + 200L);

        controller.onGovernorEvent(MAC, LhdcLinkHealthController.EVENT_QUICK_FAILURE,
                500, 900, 30_000L, probeAt + 2_000L);
        LhdcLinkHealthController.Snapshot snapshot =
                controller.snapshot(MAC, probeAt + 2_000L);
        assertEquals(500, snapshot.ceilingKbps);
        assertTrue(snapshot.boundary500To900Locked);
        assertEquals(4, snapshot.requiredHealthyBqrWindows);
        assertEquals(30_000L, snapshot.requiredQuietMs);
        assertEquals(30_000L, snapshot.nativeBackoffRemainingMs);
        assertEquals("native_backoff", snapshot.blockedReason);
    }

    @Test
    public void idleBqrCannotOpenOrKeepARecoveryProbe() {
        LhdcLinkHealthController controller = lockedTo500(null);
        long probeAt = openHealthyProbe(controller);
        assertEquals(900, controller.snapshot(MAC, probeAt).ceilingKbps);

        controller.onBqrSample(MAC, healthyBqr(), probeAt + 6_000L, false);
        LhdcLinkHealthController.Snapshot snapshot =
                controller.snapshot(MAC, probeAt + 6_000L);
        assertEquals(500, snapshot.ceilingKbps);
        assertEquals(0, snapshot.healthyBqrWindows);
        assertEquals("stream_idle", snapshot.blockedReason);
    }

    @Test
    public void nativeUpgradeAppliedMovesProbeIntoVerification() {
        LhdcLinkHealthController controller = lockedTo500(null);
        long probeAt = openHealthyProbe(controller);

        controller.onGovernorEvent(MAC, LhdcLinkHealthController.EVENT_UPGRADE_APPLIED,
                500, 900, 0L, probeAt + 200L);
        LhdcLinkHealthController.Snapshot snapshot =
                controller.snapshot(MAC, probeAt + 200L);
        assertEquals(900, snapshot.ceilingKbps);
        assertEquals("verifying", snapshot.probePhase);
        assertEquals("verifying_native_upgrade", snapshot.blockedReason);
    }

    @Test
    public void stableRecoveryClearsBoundaryLearning() {
        LhdcLinkHealthController controller = lockedTo500(null);
        long probeAt = openHealthyProbe(controller);
        controller.onGovernorEvent(MAC, LhdcLinkHealthController.EVENT_UPGRADE_APPLIED,
                500, 900, 0L, probeAt + 200L);

        controller.onGovernorEvent(MAC, LhdcLinkHealthController.EVENT_UPGRADE_STABLE,
                500, 900, 0L, probeAt + 60_000L);
        LhdcLinkHealthController.Snapshot snapshot =
                controller.snapshot(MAC, probeAt + 60_000L);
        assertEquals(1000, snapshot.ceilingKbps);
        assertFalse(snapshot.boundary500To900Locked);
        assertEquals("stable", snapshot.probePhase);
        assertEquals("none", snapshot.blockedReason);
    }

    @Test
    public void ordinaryDeviceSwitchRestoresPerMacCeiling() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = lockedTo500(recorder);
        String other = "AA:BB:CC:DD:EE:02";
        controller.activate(other, 3_000L);
        assertEquals(1000, controller.snapshot(other, 3_000L).ceilingKbps);

        controller.activate(MAC, 4_000L);
        assertEquals(500, controller.snapshot(MAC, 4_000L).ceilingKbps);
        assertEquals("500:device_active", recorder.events.get(recorder.events.size() - 1));
    }

    @Test
    public void repeatedActivateForEveryBqrIsIdempotent() {
        LhdcLinkHealthController controller = lockedTo500(null);
        controller.activate(MAC, 10_000L);
        controller.onBqrSample(MAC, healthyBqr(), 10_000L);
        controller.onQueueSample(MAC, 0, 45, 10_100L);
        controller.activate(MAC, 16_000L);
        controller.onBqrSample(MAC, healthyBqr(), 16_000L);
        controller.activate(MAC, 22_000L);
        controller.onBqrSample(MAC, healthyBqr(), 22_000L);
        controller.activate(MAC, 28_000L);
        controller.onBqrSample(MAC, healthyBqr(), 28_000L);

        LhdcLinkHealthController.Snapshot snapshot = controller.snapshot(MAC, 28_000L);
        assertEquals(900, snapshot.ceilingKbps);
        assertEquals("probing", snapshot.probePhase);
    }

    @Test
    public void sameMacGapDoesNotEraseLearnedLock() {
        LhdcLinkHealthController controller = lockedTo500(null);
        controller.activate(MAC, 60_000L);
        LhdcLinkHealthController.Snapshot snapshot = controller.snapshot(MAC, 60_000L);
        assertEquals(500, snapshot.ceilingKbps);
        assertTrue(snapshot.boundary500To900Locked);
    }

    @Test
    public void explicitSessionExitClearsLearnedLockBeforeReconnect() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = lockedTo500(recorder);

        assertTrue(controller.resetDevice(MAC, 3_000L, "a2dp_disconnected"));
        assertEquals(null, controller.activeMac());
        LhdcLinkHealthController.Snapshot reset = controller.snapshot(MAC, 3_000L);
        assertEquals(1000, reset.ceilingKbps);
        assertFalse(reset.boundary500To900Locked);
        assertEquals("1000:a2dp_disconnected",
                recorder.events.get(recorder.events.size() - 1));

        controller.activate(MAC, 4_000L);
        LhdcLinkHealthController.Snapshot reconnected = controller.snapshot(MAC, 4_000L);
        assertEquals(1000, reconnected.ceilingKbps);
        assertFalse(reconnected.boundary500To900Locked);
    }

    @Test
    public void nativeStreamGenerationResetsEvidenceButPreservesLearnedLock() {
        LhdcLinkHealthController controller = lockedTo500(null);
        long probeAt = openHealthyProbe(controller);
        assertEquals(900, controller.snapshot(MAC, probeAt).ceilingKbps);

        controller.onStreamSessionChanged(MAC, 7L, probeAt + 1_000L);
        LhdcLinkHealthController.Snapshot snapshot =
                controller.snapshot(MAC, probeAt + 1_000L);
        assertEquals(500, snapshot.ceilingKbps);
        assertTrue(snapshot.boundary500To900Locked);
        assertEquals(0, snapshot.healthyBqrWindows);
        assertEquals(7L, snapshot.streamSessionId);
        assertEquals("stream_idle", snapshot.blockedReason);
    }

    @Test
    public void threeHealthyProbeWindowsDoNotMistakeCeilingForStableUpgrade() {
        LhdcLinkHealthController controller = lockedTo500(null);
        long probeAt = openHealthyProbe(controller);
        controller.onBqrSample(MAC, healthyBqr(), probeAt + 6_000L);
        controller.onBqrSample(MAC, healthyBqr(), probeAt + 12_000L);
        controller.onBqrSample(MAC, healthyBqr(), probeAt + 18_000L);

        LhdcLinkHealthController.Snapshot snapshot =
                controller.snapshot(MAC, probeAt + 18_000L);
        assertEquals(900, snapshot.ceilingKbps);
        assertEquals("probing", snapshot.probePhase);
        assertFalse(snapshot.boundary500To900Locked);
    }

    @Test
    public void walkingLogBorderlineSampleDoesNotRevokeProbe() {
        LhdcLinkHealthController controller = lockedTo500(null);
        long probeAt = openHealthyProbe(controller);

        // 159 / 6s = 26.5/s and 154 / 6s = 25.7/s: the real walking trace sample
        // that previously revoked a probe after only five to six seconds.
        controller.onBqrSample(MAC, borderlineWalkingBqr(), probeAt + 6_000L);
        LhdcLinkHealthController.Snapshot snapshot =
                controller.snapshot(MAC, probeAt + 6_000L);
        assertEquals(900, snapshot.ceilingKbps);
        assertEquals("probing", snapshot.probePhase);
        assertEquals(0, snapshot.probeBadBqrWindows);
    }

    @Test
    public void sustainedMildBadBqrWaitsForNativeWindowBeforeRevokingProbe() {
        LhdcLinkHealthController controller = lockedTo500(null);
        long probeAt = openHealthyProbe(controller);
        controller.onBqrSample(MAC, mildlyBadProbeBqr(), probeAt + 6_000L);
        controller.onBqrSample(MAC, mildlyBadProbeBqr(), probeAt + 12_000L);
        assertEquals(900, controller.snapshot(MAC, probeAt + 12_000L).ceilingKbps);

        controller.onQueueSample(MAC, 0, 45, probeAt + 15_100L);
        LhdcLinkHealthController.Snapshot snapshot =
                controller.snapshot(MAC, probeAt + 15_100L);
        assertEquals(500, snapshot.ceilingKbps);
        assertEquals("locked", snapshot.probePhase);
    }

    @Test
    public void missingBqrWindowDoesNotLookLikeCongestionOrRevokeProbe() {
        LhdcLinkHealthController controller = lockedTo500(null);
        long probeAt = openHealthyProbe(controller);
        controller.onBqrSample(MAC, healthyBqr(), probeAt + 30_000L);

        LhdcLinkHealthController.Snapshot snapshot =
                controller.snapshot(MAC, probeAt + 30_000L);
        assertEquals(900, snapshot.ceilingKbps);
        assertEquals("probing", snapshot.probePhase);
    }

    @Test
    public void severeBqrRevokesProbeImmediately() {
        LhdcLinkHealthController controller = lockedTo500(null);
        long probeAt = openHealthyProbe(controller);
        controller.onBqrSample(MAC, severeBqr(), probeAt + 6_000L);
        assertEquals(500, controller.snapshot(MAC, probeAt + 6_000L).ceilingKbps);
    }

    @Test
    public void queueCriticalRequiresHoldButQueueFullAndChoppyAreImmediate() {
        LhdcLinkHealthController controller = lockedTo500(null);
        long probeAt = openHealthyProbe(controller);
        controller.onQueueSample(MAC, 41, 45, probeAt + 100L);
        controller.onQueueSample(MAC, 41, 45, probeAt + 300L);
        assertEquals(900, controller.snapshot(MAC, probeAt + 300L).ceilingKbps);
        controller.onQueueSample(MAC, 41, 45, probeAt + 500L);
        assertEquals(500, controller.snapshot(MAC, probeAt + 500L).ceilingKbps);

        // Re-open using a fresh controller to prove the two emergency paths independently.
        controller = lockedTo500(null);
        probeAt = openHealthyProbe(controller);
        controller.onQueueSample(MAC, 45, 45, probeAt + 100L);
        assertEquals(500, controller.snapshot(MAC, probeAt + 100L).ceilingKbps);

        controller = lockedTo500(null);
        probeAt = openHealthyProbe(controller);
        controller.onCongestion(MAC, probeAt + 100L);
        assertEquals(500, controller.snapshot(MAC, probeAt + 100L).ceilingKbps);
    }

    @Test
    public void nativeBackoffPreventsCeilingOnlyRetry() {
        LhdcLinkHealthController controller = lockedTo500(null);
        long probeAt = openHealthyProbe(controller);
        controller.onGovernorEvent(MAC, LhdcLinkHealthController.EVENT_UPGRADE_APPLIED,
                500, 900, 0L, probeAt + 100L);
        long failedAt = probeAt + 2_000L;
        controller.onGovernorEvent(MAC, LhdcLinkHealthController.EVENT_QUICK_FAILURE,
                500, 900, 60_000L, failedAt);
        controller.onQueueSample(MAC, 0, 45, failedAt + 100L);
        for (long at = failedAt + 6_000L; at < failedAt + 60_000L; at += 6_000L) {
            controller.onBqrSample(MAC, healthyBqr(), at);
            controller.onQueueSample(MAC, 0, 45, at + 100L);
        }
        assertEquals(500,
                controller.snapshot(MAC, failedAt + 59_000L).ceilingKbps);

        controller.onBqrSample(MAC, healthyBqr(), failedAt + 60_000L);
        controller.onQueueSample(MAC, 0, 45, failedAt + 60_100L);
        assertEquals(900,
                controller.snapshot(MAC, failedAt + 60_100L).ceilingKbps);
    }

    @Test
    public void badEnvironmentCannotDecayOrUnlockBoundaryAfterTenMinutes() {
        LhdcLinkHealthController controller = lockedTo500(null);
        long probeAt = openHealthyProbe(controller);
        controller.onGovernorEvent(MAC, LhdcLinkHealthController.EVENT_UPGRADE_APPLIED,
                500, 900, 0L, probeAt + 100L);
        long failedAt = probeAt + 2_000L;
        controller.onGovernorEvent(MAC, LhdcLinkHealthController.EVENT_QUICK_FAILURE,
                500, 900, 30_000L, failedAt);
        for (int i = 1; i <= 105; i++) {
            long at = failedAt + i * 6_000L;
            controller.onQueueSample(MAC, 0, 45, at - 100L);
            controller.onBqrSample(MAC, unhealthyCurrentEnvironment(), at);
        }
        LhdcLinkHealthController.Snapshot snapshot =
                controller.snapshot(MAC, failedAt + 105 * 6_000L);
        assertEquals(500, snapshot.ceilingKbps);
        assertTrue(snapshot.boundary500To900Locked);
        assertEquals(4, snapshot.requiredHealthyBqrWindows);
    }

    @Test
    public void healthyDecayRelaxesTierButNeverBypassesProbe() {
        LhdcLinkHealthController controller = lockedTo500(null);
        long probeAt = openHealthyProbe(controller);
        controller.onGovernorEvent(MAC, LhdcLinkHealthController.EVENT_UPGRADE_APPLIED,
                500, 900, 0L, probeAt + 100L);
        long failedAt = probeAt + 2_000L;
        controller.onGovernorEvent(MAC, LhdcLinkHealthController.EVENT_QUICK_FAILURE,
                500, 900, 1_000_000L, failedAt);
        controller.onQueueSample(MAC, 0, 45, failedAt + 100L);
        for (int i = 1; i <= 102; i++) {
            long at = failedAt + i * 6_000L;
            controller.onBqrSample(MAC, healthyBqr(), at);
            controller.onQueueSample(MAC, 0, 45, at + 100L);
        }
        LhdcLinkHealthController.Snapshot snapshot =
                controller.snapshot(MAC, failedAt + 102 * 6_000L + 100L);
        assertEquals(500, snapshot.ceilingKbps);
        assertTrue(snapshot.boundary500To900Locked);
        assertEquals(3, snapshot.requiredHealthyBqrWindows);
        assertEquals("native_backoff", snapshot.blockedReason);
    }

    @Test
    public void boundaryStateChangeIsPublishedWhenEffectiveCeilingIsUnchanged() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        controller.onGovernorEvent(MAC, LhdcLinkHealthController.EVENT_QUICK_FAILURE,
                500, 900, 1_000L);
        controller.onGovernorEvent(MAC, LhdcLinkHealthController.EVENT_QUICK_FAILURE,
                500, 900, 2_000L);
        controller.onGovernorEvent(MAC, LhdcLinkHealthController.EVENT_QUICK_FAILURE,
                900, 1000, 3_000L);
        controller.onGovernorEvent(MAC, LhdcLinkHealthController.EVENT_QUICK_FAILURE,
                900, 1000, 4_000L);

        long probeAt = openHealthyProbe(controller);
        assertEquals(900, controller.snapshot(MAC, probeAt).ceilingKbps);
        controller.onGovernorEvent(MAC, LhdcLinkHealthController.EVENT_UPGRADE_STABLE,
                500, 900, probeAt + 1_000L);

        assertFalse(controller.snapshot(MAC, probeAt + 1_000L).boundary500To900Locked);
        assertTrue(controller.snapshot(MAC, probeAt + 1_000L).boundary900To1000Locked);
        assertEquals("900:boundary_stable",
                recorder.stateEvents.get(recorder.stateEvents.size() - 1));
    }

    private static LhdcLinkHealthController lockedTo500(Recorder recorder) {
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        controller.onGovernorEvent(MAC, LhdcLinkHealthController.EVENT_QUICK_FAILURE,
                500, 900, 1_000L);
        controller.onGovernorEvent(MAC, LhdcLinkHealthController.EVENT_QUICK_FAILURE,
                500, 900, 2_000L);
        return controller;
    }

    @Test
    public void peerCeiling900CapsCeilingAndNeverProbesTo1000() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        controller.setPeerCeilingKbps(MAC, 900, 200L, "codec_confirmed");

        LhdcLinkHealthController.Snapshot snapshot = controller.snapshot(MAC, 200L);
        assertEquals(900, snapshot.ceilingKbps);
        assertEquals(900, snapshot.peerCeilingKbps);
        assertFalse(snapshot.boundary900To1000Supported);
        assertFalse(snapshot.boundary900To1000Locked);
        assertEquals("1000:device_active", recorder.events.get(0));
        assertEquals("900:codec_confirmed", recorder.events.get(1));

        // Even after healthy BQR, the controller must never open a 900->1000 recovery probe.
        long probeAt = openHealthyProbe(controller);
        assertEquals(900, controller.snapshot(MAC, probeAt).ceilingKbps);
        assertEquals("stable", controller.snapshot(MAC, probeAt).probePhase);
        assertEquals(2, recorder.events.size());
    }

    @Test
    public void bqrFallbackRequiresFourSustainedBadWindowsBeforeClampingTo500() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        controller.setPeerCeilingKbps(MAC, 900, 200L, "codec_confirmed");

        controller.onBqrSample(MAC, bqrFallbackBad(), 10_000L);  // baseline
        for (int i = 1; i <= 3; i++) {
            controller.onBqrSample(MAC, bqrFallbackBad(), 10_000L + i * 6_000L);
        }
        assertEquals(900, controller.snapshot(MAC, 28_000L).ceilingKbps);
        assertTrue(recorder.fallbackEvents.isEmpty());

        controller.onBqrSample(MAC, bqrFallbackBad(), 34_000L);
        assertEquals(500, controller.snapshot(MAC, 34_000L).ceilingKbps);
        assertTrue(recorder.events.contains("500:bqr_fallback_triggered"));
        assertTrue(recorder.fallbackEvents.contains("500:triggered:4:0"));
    }

    @Test
    public void bqrFallbackSingleBadWindowDoesNotClamp() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        controller.setPeerCeilingKbps(MAC, 900, 200L, "codec_confirmed");

        controller.onBqrSample(MAC, bqrFallbackBad(), 10_000L);  // baseline
        controller.onBqrSample(MAC, bqrFallbackBad(), 16_000L);  // one valid bad window
        assertEquals(900, controller.snapshot(MAC, 16_000L).ceilingKbps);
        assertTrue(recorder.fallbackEvents.isEmpty());
    }

    @Test
    public void bqrFallbackDoesNotClampWhenPeerCeilingAtOrBelowCap() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        controller.setPeerCeilingKbps(MAC, 500, 200L, "codec_confirmed");

        controller.onBqrSample(MAC, bqrFallbackBad(), 10_000L);  // baseline
        for (int i = 1; i <= 6; i++) {
            controller.onBqrSample(MAC, bqrFallbackBad(), 10_000L + i * 6_000L);
        }
        assertEquals(500, controller.snapshot(MAC, 46_000L).ceilingKbps);
        assertTrue(recorder.fallbackEvents.isEmpty());
    }

    @Test
    public void bqrFallbackRecoversOnlyAfterHealthyWindowsAndMinimumHold() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        controller.setPeerCeilingKbps(MAC, 900, 200L, "codec_confirmed");
        controller.onBqrSample(MAC, bqrFallbackBad(), 10_000L);  // baseline
        for (int i = 1; i <= 4; i++) {
            controller.onBqrSample(MAC, bqrFallbackBad(), 10_000L + i * 6_000L);
        }
        assertEquals(500, controller.snapshot(MAC, 34_000L).ceilingKbps);

        // Six healthy windows (36 s after trigger) are still inside the 60 s minimum hold.
        for (int i = 1; i <= 6; i++) {
            controller.onBqrSample(MAC, bqrFallbackHealthy(), 40_000L + i * 6_000L);
        }
        assertEquals(500, controller.snapshot(MAC, 76_000L).ceilingKbps);

        // Once the hold expires the next healthy windows restore the peer ceiling.
        for (int i = 1; i <= 5; i++) {
            controller.onBqrSample(MAC, bqrFallbackHealthy(), 82_000L + i * 6_000L);
        }
        assertEquals(900, controller.snapshot(MAC, 112_000L).ceilingKbps);
        assertTrue(recorder.events.contains("900:bqr_fallback_recovered"));
    }

    @Test
    public void bqrFallbackEscalatesHoldAfterQuickReTrigger() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        controller.setPeerCeilingKbps(MAC, 900, 200L, "codec_confirmed");

        // First trigger at 34s: level 0 -> base 60s hold.
        controller.onBqrSample(MAC, bqrFallbackBad(), 10_000L);  // baseline
        for (int i = 1; i <= 4; i++) {
            controller.onBqrSample(MAC, bqrFallbackBad(), 10_000L + i * 6_000L);
        }
        assertEquals(500, controller.snapshot(MAC, 34_000L).ceilingKbps);
        assertEquals("0:60000", recorder.fallbackDetails.get(recorder.fallbackDetails.size() - 1));

        // Recover at 94s (60s hold elapsed); last healthy sample at 100s.
        for (int i = 1; i <= 10; i++) {
            controller.onBqrSample(MAC, bqrFallbackHealthy(), 40_000L + i * 6_000L);
        }
        assertEquals(900, controller.snapshot(MAC, 100_000L).ceilingKbps);

        // Re-trigger at 124s, 30s after recovery -> escalation to level 1 (120s hold).
        for (int i = 0; i < 4; i++) {
            controller.onBqrSample(MAC, bqrFallbackBad(), 106_000L + i * 6_000L);
        }
        assertEquals(500, controller.snapshot(MAC, 124_000L).ceilingKbps);
        assertEquals("1:120000", recorder.fallbackDetails.get(recorder.fallbackDetails.size() - 1));

        // Six healthy windows by 160s are not enough: the 120s hold blocks until 244s.
        for (int i = 1; i <= 7; i++) {
            controller.onBqrSample(MAC, bqrFallbackHealthy(), 130_000L + i * 6_000L);
        }
        assertEquals(500, controller.snapshot(MAC, 172_000L).ceilingKbps);
        for (int i = 8; i <= 20; i++) {
            controller.onBqrSample(MAC, bqrFallbackHealthy(), 130_000L + i * 6_000L);
        }
        assertEquals(900, controller.snapshot(MAC, 244_000L).ceilingKbps);
        assertTrue(recorder.events.contains("900:bqr_fallback_recovered"));
    }

    @Test
    public void bqrFallbackResetsEscalationAfterLongSuccessfulPhase() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        controller.setPeerCeilingKbps(MAC, 900, 200L, "codec_confirmed");

        // First trigger at 34s, recovery at 94s.
        controller.onBqrSample(MAC, bqrFallbackBad(), 10_000L);  // baseline
        for (int i = 1; i <= 4; i++) {
            controller.onBqrSample(MAC, bqrFallbackBad(), 10_000L + i * 6_000L);
        }
        for (int i = 1; i <= 10; i++) {
            controller.onBqrSample(MAC, bqrFallbackHealthy(), 40_000L + i * 6_000L);
        }
        assertEquals(900, controller.snapshot(MAC, 100_000L).ceilingKbps);

        // The 900 phase survives >2min (healthy samples through 214s), so the next trigger
        // resets the escalation back to the base 60s hold.
        for (int i = 0; i < 20; i++) {
            controller.onBqrSample(MAC, bqrFallbackHealthy(), 100_000L + i * 6_000L);
        }
        for (int i = 0; i < 4; i++) {
            controller.onBqrSample(MAC, bqrFallbackBad(), 220_000L + i * 6_000L);
        }
        assertEquals(500, controller.snapshot(MAC, 238_000L).ceilingKbps);
        assertEquals("0:60000", recorder.fallbackDetails.get(recorder.fallbackDetails.size() - 1));

        // 60s hold: six healthy windows at 280s are still inside the hold; recovery at 298s.
        for (int i = 1; i <= 7; i++) {
            controller.onBqrSample(MAC, bqrFallbackHealthy(), 244_000L + i * 6_000L);
        }
        assertEquals(500, controller.snapshot(MAC, 286_000L).ceilingKbps);
        for (int i = 8; i <= 10; i++) {
            controller.onBqrSample(MAC, bqrFallbackHealthy(), 244_000L + i * 6_000L);
        }
        assertEquals(900, controller.snapshot(MAC, 304_000L).ceilingKbps);
    }

    @Test
    public void bqrFallbackQueueFastFailTriggersWithinProbeWindow() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        controller.setPeerCeilingKbps(MAC, 900, 200L, "codec_confirmed");

        // High-water queue sustained for 3s inside the 30s probe window clamps immediately.
        controller.onQueueSample(MAC, 40, 45, 1_000L);
        controller.onQueueSample(MAC, 40, 45, 2_000L);
        controller.onQueueSample(MAC, 40, 45, 4_000L);
        assertEquals(500, controller.snapshot(MAC, 4_000L).ceilingKbps);
        assertTrue(recorder.events.contains("500:bqr_fallback_triggered_queue"));
        assertTrue(recorder.fallbackEvents.contains("500:triggered_queue_fast_fail:0:0"));
    }

    @Test
    public void bqrFallbackQueueFastFailIgnoredOutsideProbeWindow() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        controller.setPeerCeilingKbps(MAC, 900, 200L, "codec_confirmed");

        // The window armed by codec_confirmed at 200ms expires at 30.2s; the same queue pressure
        // afterwards must not clamp (BQR windows remain the only outside-window downgrade path).
        controller.onQueueSample(MAC, 40, 45, 31_000L);
        controller.onQueueSample(MAC, 40, 45, 34_000L);
        controller.onQueueSample(MAC, 40, 45, 35_000L);
        assertEquals(900, controller.snapshot(MAC, 35_000L).ceilingKbps);
        assertTrue(recorder.fallbackEvents.isEmpty());
    }

    @Test
    public void bqrFallbackQueueFastFailRequiresSustainedHighQueue() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        controller.setPeerCeilingKbps(MAC, 900, 200L, "codec_confirmed");

        // A single dip below the threshold resets the accumulation; 2.5s of high water is not
        // enough, and only the full 3s sustained window triggers.
        controller.onQueueSample(MAC, 40, 45, 1_000L);
        controller.onQueueSample(MAC, 39, 45, 2_000L);
        controller.onQueueSample(MAC, 40, 45, 3_000L);
        controller.onQueueSample(MAC, 40, 45, 5_500L);
        assertEquals(900, controller.snapshot(MAC, 5_500L).ceilingKbps);
        assertTrue(recorder.fallbackEvents.isEmpty());
        controller.onQueueSample(MAC, 40, 45, 6_000L);
        assertEquals(500, controller.snapshot(MAC, 6_000L).ceilingKbps);
        assertTrue(recorder.events.contains("500:bqr_fallback_triggered_queue"));
    }

    @Test
    public void bqrFallbackCodecWriteClearsCapAndReArmsFastFailWindow() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        controller.setPeerCeilingKbps(MAC, 900, 200L, "codec_confirmed");

        // Enter the capped state via four sustained bad windows.
        controller.onBqrSample(MAC, bqrFallbackBad(), 10_000L);  // baseline
        for (int i = 1; i <= 4; i++) {
            controller.onBqrSample(MAC, bqrFallbackBad(), 10_000L + i * 6_000L);
        }
        assertEquals(500, controller.snapshot(MAC, 34_000L).ceilingKbps);

        // The user picks 音质优先 again: the stack-confirmed 900 config clears the cap and arms
        // a fresh fast-fail window, so the failed attempt is clamped within seconds.
        controller.setPeerCeilingKbps(MAC, 900, 40_000L, "codec_confirmed");
        assertEquals(900, controller.snapshot(MAC, 40_000L).ceilingKbps);
        controller.onQueueSample(MAC, 40, 45, 41_000L);
        controller.onQueueSample(MAC, 40, 45, 44_000L);
        assertEquals(500, controller.snapshot(MAC, 44_000L).ceilingKbps);
        assertTrue(recorder.events.contains("500:bqr_fallback_triggered_queue"));
    }

    @Test
    public void peerCeilingArrivesBeforeActivationPublishes900OnFirstActivation() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.setPeerCeilingKbps(MAC, 900, 100L, "codec_confirmed");

        controller.activate(MAC, 200L);
        assertEquals("900:device_active", recorder.events.get(0));
        assertEquals(900, controller.snapshot(MAC, 200L).ceilingKbps);
    }

    @Test
    public void unsupportedBoundaryIsNotReportedAsLearnedLock() {
        LhdcLinkHealthController controller = new LhdcLinkHealthController(null);
        controller.activate(MAC, 100L);
        controller.setPeerCeilingKbps(MAC, 900, 200L, "codec_confirmed");

        LhdcLinkHealthController.Snapshot snapshot = controller.snapshot(MAC, 200L);
        assertEquals(900, snapshot.peerCeilingKbps);
        assertFalse(snapshot.boundary900To1000Supported);
        assertFalse(snapshot.boundary900To1000Locked);
        assertEquals("stable", snapshot.probePhase);
    }

    @Test
    public void switchingDevicesDoesNotLeakPeerCeiling() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        controller.setPeerCeilingKbps(MAC, 900, 200L, "codec_confirmed");

        String other = "AA:BB:CC:DD:EE:02";
        controller.activate(other, 300L);
        LhdcLinkHealthController.Snapshot otherSnapshot = controller.snapshot(other, 300L);
        assertEquals(1000, otherSnapshot.ceilingKbps);
        assertEquals(0, otherSnapshot.peerCeilingKbps);
        assertFalse(otherSnapshot.boundary900To1000Supported);

        // The 900 capability stays with its own device and returns on re-activation.
        controller.activate(MAC, 400L);
        assertEquals(900, controller.snapshot(MAC, 400L).ceilingKbps);
        assertEquals(900, controller.snapshot(MAC, 400L).peerCeilingKbps);
    }

    @Test
    public void unknownPeerCapabilityDoesNotMeanSupported() {
        LhdcLinkHealthController controller = new LhdcLinkHealthController(null);
        controller.activate(MAC, 100L);

        LhdcLinkHealthController.Snapshot snapshot = controller.snapshot(MAC, 100L);
        assertEquals(0, snapshot.peerCeilingKbps);
        assertFalse(snapshot.boundary900To1000Supported);
        assertEquals(1000, snapshot.ceilingKbps);
    }

    @Test
    public void remoteChoppyReportsAreTrackedInSnapshotWindow() {
        LhdcLinkHealthController controller = new LhdcLinkHealthController(null);
        controller.activate(MAC, 100L);

        controller.onRemoteChoppyReport(MAC, 1, 200L);
        controller.onRemoteChoppyReport(MAC, 1, 400L);
        LhdcLinkHealthController.Snapshot snapshot = controller.snapshot(MAC, 500L);
        assertEquals(1, snapshot.lastRemoteChoppyLevel);
        assertEquals(2, snapshot.remoteChoppyCount5s);
        assertEquals(100L, snapshot.lastRemoteChoppyAgoMs);

        assertEquals(0,
                controller.snapshot(MAC, 5_501L).remoteChoppyCount5s);

        // A report after the 5s window starts a fresh count.
        controller.onRemoteChoppyReport(MAC, 2, 6_000L);
        LhdcLinkHealthController.Snapshot next = controller.snapshot(MAC, 6_100L);
        assertEquals(2, next.lastRemoteChoppyLevel);
        assertEquals(1, next.remoteChoppyCount5s);
        assertEquals(100L, next.lastRemoteChoppyAgoMs);
    }

    @Test
    public void peerCeiling1000RestoresDefaultProbing() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        controller.setPeerCeilingKbps(MAC, 900, 200L, "codec_confirmed");
        controller.setPeerCeilingKbps(MAC, 1000, 300L, "codec_confirmed");
        assertEquals(1000, controller.snapshot(MAC, 300L).ceilingKbps);
    }

    @Test
    public void repeatedPeerCeiling1000DoesNotClearLearnedBoundaryLock() {
        LhdcLinkHealthController controller = new LhdcLinkHealthController(null);
        controller.activate(MAC, 100L);
        controller.onGovernorEvent(MAC, LhdcLinkHealthController.EVENT_QUICK_FAILURE,
                900, 1000, 1_000L);
        controller.onGovernorEvent(MAC, LhdcLinkHealthController.EVENT_QUICK_FAILURE,
                900, 1000, 2_000L);
        assertTrue(controller.snapshot(MAC, 2_000L).boundary900To1000Locked);

        controller.setPeerCeilingKbps(MAC, 1000, 3_000L, "codec_confirmed");
        LhdcLinkHealthController.Snapshot snapshot = controller.snapshot(MAC, 3_000L);
        assertTrue(snapshot.boundary900To1000Supported);
        assertTrue(snapshot.boundary900To1000Locked);
        assertEquals(900, snapshot.ceilingKbps);
    }

    @Test
    public void peerCeiling900SurvivesStreamSessionButResetsWithDevice() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        controller.setPeerCeilingKbps(MAC, 900, 200L, "codec_confirmed");
        controller.onStreamSessionChanged(MAC, 7L, 300L);
        assertEquals(900, controller.snapshot(MAC, 300L).ceilingKbps);
        assertEquals(900, controller.snapshot(MAC, 300L).peerCeilingKbps);
        assertFalse(controller.snapshot(MAC, 300L).boundary900To1000Supported);

        controller.resetDevice(MAC, 400L, "session_reset");
        assertEquals(1000, controller.snapshot(MAC, 400L).ceilingKbps);
        assertEquals(0, controller.snapshot(MAC, 400L).peerCeilingKbps);
    }

    @Test
    public void shadowBqrNeedsTwoContinuousWindowsAndNeverChangesCeiling() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.setPeerCeilingKbps(MAC, 900, 50L, "codec_confirmed");
        controller.activate(MAC, 100L);

        controller.onBqrSample(MAC, unstableBqr(), 10_000L);
        controller.onBqrSample(MAC, unstableBqr(), 16_000L);
        LhdcLinkHealthController.Snapshot single = controller.snapshot(MAC, 16_000L);
        assertEquals(1, single.shadowUnstableWindows);
        assertEquals(900, single.ceilingKbps);
        assertTrue(recorder.shadowEvents.isEmpty());

        controller.onBqrSample(MAC, unstableBqr(), 22_000L);
        LhdcLinkHealthController.Snapshot candidate = controller.snapshot(MAC, 22_000L);
        assertEquals(0, candidate.shadowUnstableWindows);
        assertEquals(1, candidate.shadowCandidateCount);
        assertEquals(500, candidate.lastShadowCandidateKbps);
        assertEquals(900, candidate.ceilingKbps);
        assertEquals(1, recorder.events.size());
        assertTrue(recorder.stateEvents.isEmpty());
        assertEquals("900:500:1:0:1:0", recorder.shadowEvents.get(0));
    }

    @Test
    public void shadowContinuityResetsOnHealthyInvalidIdleAndSessionChange() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        controller.onStreamSessionChanged(MAC, 1L, 200L);

        controller.onBqrSample(MAC, unstableBqr(), 10_000L);
        controller.onBqrSample(MAC, unstableBqr(), 16_000L);
        controller.onBqrSample(MAC, healthyBqr(), 22_000L);
        controller.onBqrSample(MAC, unstableBqr(), 28_000L);
        assertEquals(1, controller.snapshot(MAC, 28_000L).shadowUnstableWindows);

        controller.onBqrSample(MAC, unstableBqr(), 50_000L);
        assertEquals(0, controller.snapshot(MAC, 50_000L).shadowUnstableWindows);
        controller.onBqrSample(MAC, unstableBqr(), 56_000L, false);
        assertEquals(0, controller.snapshot(MAC, 56_000L).shadowUnstableWindows);

        controller.onBqrSample(MAC, unstableBqr(), 62_000L);
        assertEquals(1, controller.snapshot(MAC, 62_000L).shadowUnstableWindows);
        controller.onStreamSessionChanged(MAC, 2L, 63_000L);
        assertEquals(0, controller.snapshot(MAC, 63_000L).shadowUnstableWindows);
        assertTrue(recorder.shadowEvents.isEmpty());
    }

    @Test
    public void shadowCandidateIsMacIsolatedAndRateLimited() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        controller.onBqrSample(MAC, unstableBqr(), 10_000L);
        controller.onBqrSample(MAC, unstableBqr(), 16_000L);
        controller.onBqrSample(MAC, unstableBqr(), 22_000L);
        assertEquals(1, recorder.shadowEvents.size());

        controller.onBqrSample(MAC, unstableBqr(), 28_000L);
        controller.onBqrSample(MAC, unstableBqr(), 34_000L);
        assertEquals(1, recorder.shadowEvents.size());
        controller.onBqrSample(MAC, unstableBqr(), 40_000L);
        controller.onBqrSample(MAC, unstableBqr(), 46_000L);
        assertEquals(2, recorder.shadowEvents.size());

        String other = "AA:BB:CC:DD:EE:02";
        controller.activate(other, 50_000L);
        controller.onBqrSample(other, unstableBqr(), 52_000L);
        controller.onBqrSample(other, unstableBqr(), 58_000L);
        controller.onBqrSample(other, unstableBqr(), 64_000L);
        assertEquals(3, recorder.shadowEvents.size());
        assertEquals(2, controller.snapshot(MAC, 64_000L).shadowCandidateCount);
        assertEquals(1, controller.snapshot(other, 64_000L).shadowCandidateCount);
    }

    @Test
    public void inFlightProbeDoesNotCreateStableShadowCandidate() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = lockedTo500(recorder);
        long probeAt = openHealthyProbe(controller);

        controller.onBqrSample(MAC, unstableBqr(), probeAt + 6_000L);
        controller.onBqrSample(MAC, unstableBqr(), probeAt + 12_000L);

        assertTrue(recorder.shadowEvents.isEmpty());
        assertEquals(0,
                controller.snapshot(MAC, probeAt + 12_000L).shadowUnstableWindows);
        assertEquals(900, controller.snapshot(MAC, probeAt + 12_000L).ceilingKbps);
    }

    @Test
    public void choppyCapabilityIsObservedPerMacAcrossReconnectOnlyWithinProcess() {
        LhdcLinkHealthController controller = new LhdcLinkHealthController(null);
        controller.activate(MAC, 100L);
        assertEquals(LhdcLinkHealthController.CHOPPY_CAPABILITY_UNKNOWN,
                controller.snapshot(MAC, 100L).choppyCapabilityState);

        controller.onRemoteChoppyReport(MAC, 1, 200L);
        assertEquals(LhdcLinkHealthController.CHOPPY_CAPABILITY_OBSERVED,
                controller.snapshot(MAC, 200L).choppyCapabilityState);
        controller.resetDevice(MAC, 300L, "a2dp_disconnected");
        assertEquals(LhdcLinkHealthController.CHOPPY_CAPABILITY_OBSERVED,
                controller.snapshot(MAC, 300L).choppyCapabilityState);
        controller.activate(MAC, 400L);
        controller.onBqrSample(MAC, unstableBqr(), 10_000L);
        controller.onBqrSample(MAC, unstableBqr(), 16_000L);
        controller.onBqrSample(MAC, unstableBqr(), 22_000L);
        assertEquals(1, controller.snapshot(MAC, 22_000L).shadowCandidateCount);
        assertEquals(LhdcLinkHealthController.CHOPPY_CAPABILITY_UNKNOWN,
                controller.snapshot("AA:BB:CC:DD:EE:02", 300L).choppyCapabilityState);

        LhdcLinkHealthController newProcessController =
                new LhdcLinkHealthController(null);
        assertEquals(LhdcLinkHealthController.CHOPPY_CAPABILITY_UNKNOWN,
                newProcessController.snapshot(MAC, 400L).choppyCapabilityState);
    }

    @Test
    public void issuedTargetCapTimesOutAndReturnsPendingTransactionOnce() {
        LhdcLinkHealthController controller = new LhdcLinkHealthController(null);
        controller.activate(MAC, 100L);

        controller.onTargetCapIssued(MAC, 500, 7, 1_000L);
        assertNull(controller.tickSwitchTransactions(MAC, 3_400L));

        LhdcLinkHealthController.PendingTransaction pending =
                controller.tickSwitchTransactions(MAC, 3_600L);
        assertNotNull(pending);
        assertEquals(500, pending.targetKbps);
        assertEquals(7, pending.requestId);
        assertEquals(1_000L, pending.sinceMs);

        assertNull(controller.tickSwitchTransactions(MAC, 3_700L));
    }

    @Test
    public void zeroRequestIdIssueIsIgnoredWithoutTransaction() {
        LhdcLinkHealthController controller = new LhdcLinkHealthController(null);
        controller.activate(MAC, 100L);

        // Same-rung or governor-unavailable writes return requestId 0; they must not open
        // a transaction that could never be confirmed (Phase N-1 review P1-1/P2-5).
        controller.onTargetCapIssued(MAC, 500, 0, 1_000L);
        assertNull(controller.tickSwitchTransactions(MAC, 10_000L));
    }

    @Test
    public void matchingTransitionConfirmationClosesTransactionBeforeTimeout() {
        LhdcLinkHealthController controller = new LhdcLinkHealthController(null);
        controller.activate(MAC, 100L);

        controller.onTargetCapIssued(MAC, 500, 7, 1_000L);
        controller.onTransitionConfirmed(MAC, 500, 7, 1_200L);
        assertNull(controller.tickSwitchTransactions(MAC, 10_000L));
    }

    @Test
    public void staleTransitionConfirmationIsIgnoredAndTransactionStillTimesOut() {
        LhdcLinkHealthController controller = new LhdcLinkHealthController(null);
        controller.activate(MAC, 100L);

        controller.onTargetCapIssued(MAC, 500, 7, 1_000L);
        controller.onTransitionConfirmed(MAC, 500, 6, 1_200L);
        LhdcLinkHealthController.PendingTransaction pending =
                controller.tickSwitchTransactions(MAC, 4_000L);
        assertNotNull(pending);
        assertEquals(7, pending.requestId);
    }

    @Test
    public void sessionResetClearsPendingTransaction() {
        LhdcLinkHealthController controller = new LhdcLinkHealthController(null);
        controller.activate(MAC, 100L);

        controller.onTargetCapIssued(MAC, 500, 7, 1_000L);
        controller.onStreamSessionChanged(MAC, 99L, 2_000L);
        assertNull(controller.tickSwitchTransactions(MAC, 10_000L));
    }

    private static long openHealthyProbe(LhdcLinkHealthController controller) {
        controller.onBqrSample(MAC, healthyBqr(), 10_000L);
        controller.onQueueSample(MAC, 0, 45, 10_100L);
        controller.onBqrSample(MAC, healthyBqr(), 16_000L);
        controller.onBqrSample(MAC, healthyBqr(), 22_000L);
        controller.onBqrSample(MAC, healthyBqr(), 28_000L);
        return 28_000L;
    }

    private static LhdcLinkHealthController.BqrSample healthyBqr() {
        return new LhdcLinkHealthController.BqrSample(
                30, 0, 90, 60, 2, -45, 10, 0, 0);
    }

    private static LhdcLinkHealthController.BqrSample borderlineWalkingBqr() {
        return new LhdcLinkHealthController.BqrSample(
                50, 0, 159, 154, 2, -50, 8, 0, 0);
    }

    private static LhdcLinkHealthController.BqrSample mildlyBadProbeBqr() {
        return new LhdcLinkHealthController.BqrSample(
                60, 0, 600, 600, 4, -55, 4, 0, 0);
    }

    private static LhdcLinkHealthController.BqrSample severeBqr() {
        return new LhdcLinkHealthController.BqrSample(
                70, 0, 900, 900, 20, -75, 0, 0, 0);
    }

    private static LhdcLinkHealthController.BqrSample unhealthyCurrentEnvironment() {
        return new LhdcLinkHealthController.BqrSample(
                59, 0, 420, 405, 10, -42, 0, 0, 0);
    }

    /** 6 s window: 200 retx -> 33.3/s, 160 noRx -> 26.7/s, both above the fallback gate. */
    private static LhdcLinkHealthController.BqrSample bqrFallbackBad() {
        return new LhdcLinkHealthController.BqrSample(
                45, 0, 200, 160, 8, -50, 0, 0, 0);
    }

    /** 6 s window: 100 retx -> 16.7/s, 80 noRx -> 13.3/s, both below the healthy gate. */
    private static LhdcLinkHealthController.BqrSample bqrFallbackHealthy() {
        return new LhdcLinkHealthController.BqrSample(
                30, 0, 100, 80, 2, -45, 10, 0, 0);
    }

    private static LhdcLinkHealthController.BqrSample unstableBqr() {
        return new LhdcLinkHealthController.BqrSample(
                59, 0, 420, 405, 10, -42, 0, 1, 0);
    }

    private static final class Recorder implements LhdcLinkHealthController.Listener {
        final List<String> events = new ArrayList<>();
        final List<String> stateEvents = new ArrayList<>();
        final List<String> shadowEvents = new ArrayList<>();
        final List<String> fallbackEvents = new ArrayList<>();
        final List<String> fallbackDetails = new ArrayList<>();

        @Override
        public void onProbeCeilingChanged(String mac, int ceilingKbps, String reason) {
            events.add(ceilingKbps + ":" + reason);
        }

        @Override
        public void onProbeStateChanged(String mac, int ceilingKbps, String reason) {
            stateEvents.add(ceilingKbps + ":" + reason);
        }

        @Override
        public void onBqrShadowCandidate(
                String mac,
                int fromKbps,
                int candidateKbps,
                long overflowCount,
                long underflowCount,
                int candidateCount,
                long streamSessionId) {
            shadowEvents.add(fromKbps + ":" + candidateKbps + ":"
                    + overflowCount + ":" + underflowCount + ":"
                    + candidateCount + ":" + streamSessionId);
        }

        @Override
        public void onBqrFallbackStateChanged(
                String mac,
                int capKbps,
                String reason,
                int badWindows,
                int healthyWindows,
                double retransmissionsPerSecond,
                double noRxPerSecond,
                int escalationLevel,
                long holdMs) {
            fallbackEvents.add(capKbps + ":" + reason + ":" + badWindows + ":" + healthyWindows);
            fallbackDetails.add(escalationLevel + ":" + holdMs);
        }
    }
}
