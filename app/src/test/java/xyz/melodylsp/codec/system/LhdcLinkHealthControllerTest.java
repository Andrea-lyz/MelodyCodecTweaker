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
    public void bqrFallbackStepsDownOneRungAtATimeOnADevice() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        // A device: peer ceiling stays at the default 1000.

        // First step: 1000 -> 900 after four sustained bad windows.
        controller.onBqrSample(MAC, bqrFallbackBad(), 10_000L);  // baseline
        for (int i = 1; i <= 4; i++) {
            controller.onBqrSample(MAC, bqrFallbackBad(), 10_000L + i * 6_000L);
        }
        assertEquals(900, controller.snapshot(MAC, 34_000L).ceilingKbps);
        assertTrue(recorder.events.contains("900:bqr_fallback_triggered"));

        // Second step needs fresh evidence: four more bad windows while capped -> 500.
        // The first bad window after the downgrade sits in the 10 s dead zone and is
        // frozen; the following four accumulate.
        controller.onBqrSample(MAC, bqrFallbackBad(), 40_000L);   // dead zone
        for (int i = 0; i < 4; i++) {
            controller.onBqrSample(MAC, bqrFallbackBad(), 46_000L + i * 6_000L);  // 46..64
        }
        assertEquals(500, controller.snapshot(MAC, 64_000L).ceilingKbps);
        assertTrue(recorder.events.contains("500:bqr_fallback_triggered"));
    }

    @Test
    public void downgradeDeadZoneFreezesRecoveryEvidence() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        controller.setPeerCeilingKbps(MAC, 900, 200L, "codec_confirmed");

        controller.onBqrSample(MAC, bqrFallbackBad(), 10_000L);  // baseline
        for (int i = 1; i <= 4; i++) {
            controller.onBqrSample(MAC, bqrFallbackBad(), 10_000L + i * 6_000L);  // 16..34
        }
        assertEquals(500, controller.snapshot(MAC, 34_000L).ceilingKbps);

        // Healthy windows inside the 10 s dead zone must not start the recovery streak.
        controller.onBqrSample(MAC, bqrFallbackHealthy(), 40_000L);  // 6 s after trigger
        controller.onBqrSample(MAC, bqrFallbackHealthy(), 44_000L);  // dead zone ends 44 s
        controller.onBqrSample(MAC, bqrFallbackHealthy(), 50_000L);  // streak starts here
        for (int i = 1; i <= 6; i++) {
            controller.onBqrSample(MAC, bqrFallbackHealthy(), 56_000L + i * 6_000L);  // 62..92
        }
        // Windows 44/50 are frozen or first; recovery needs 6 counted (62..92) + 60 s hold
        // from 34 s (expires 94 s) -> still capped at 92 s.
        assertEquals(500, controller.snapshot(MAC, 92_000L).ceilingKbps);
        controller.onBqrSample(MAC, bqrFallbackHealthy(), 98_000L);
        assertEquals(900, controller.snapshot(MAC, 98_000L).ceilingKbps);
        assertTrue(recorder.skippedBqrWindows.contains("dead_zone"));
    }

    @Test
    public void fastRecoveryTierMoves400To500() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        controller.setBqrFallbackCapKbpsForTest(MAC, 400, 10_000L);
        assertEquals(400, controller.snapshot(MAC, 10_000L).ceilingKbps);

        // Fast channel: 5 windows at relaxed evidence (retx<=40/noRx<=25) + 30 s hold.
        controller.onBqrSample(MAC, healthyBqr(), 20_000L);  // baseline
        for (int i = 1; i <= 5; i++) {
            controller.onBqrSample(MAC, healthyBqr(), 26_000L + i * 6_000L);  // 32..56
        }
        assertEquals(500, controller.snapshot(MAC, 56_000L).ceilingKbps);
        assertTrue(recorder.events.contains("500:bqr_fallback_recovered"));
    }

    @Test
    public void fastRecoveryTierCountsMidBandWindows() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        controller.setBqrFallbackCapKbpsForTest(MAC, 400, 10_000L);

        // The relaxed 400 tier counts mid-band windows (26/23) that the <24/<21 evidence
        // would reject (review P2-3c).
        controller.onBqrSample(MAC, healthyBqr(), 20_000L);  // baseline
        for (int i = 1; i <= 5; i++) {
            controller.onBqrSample(MAC, midBandBqr(), 26_000L + i * 6_000L);  // 32..56
        }
        assertEquals(500, controller.snapshot(MAC, 56_000L).ceilingKbps);
    }

    @Test
    public void bqrFallbackFloorRetriggerIsIdempotent() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        controller.setPeerCeilingKbps(MAC, 900, 200L, "codec_confirmed");

        controller.onBqrSample(MAC, bqrFallbackBad(), 10_000L);  // baseline
        for (int i = 1; i <= 4; i++) {
            controller.onBqrSample(MAC, bqrFallbackBad(), 10_000L + i * 6_000L);
        }
        assertEquals(500, controller.snapshot(MAC, 34_000L).ceilingKbps);  // 900 -> 500

        // At the 500 floor another four bad windows re-trigger but the rung stays 500.
        controller.onBqrSample(MAC, bqrFallbackBad(), 44_000L);   // dead zone ends 44 s
        for (int i = 0; i < 4; i++) {
            controller.onBqrSample(MAC, bqrFallbackBad(), 46_000L + i * 6_000L);  // 46..64
        }
        assertEquals(500, controller.snapshot(MAC, 64_000L).ceilingKbps);
    }

    @Test
    public void strictTierCountsMidBandWindows() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        controller.setBqrFallbackCapKbpsForTest(MAC, 900, 10_000L);
        assertEquals(900, controller.snapshot(MAC, 10_000L).ceilingKbps);

        // X3 regression (feedback 231816): the strict <24/<21 evidence never reached 8
        // consecutive windows in the 900 tier; mid-band windows (26/23) now count and the
        // 120 s hold from the cap start completes at 130 s.
        controller.onBqrSample(MAC, healthyBqr(), 20_000L);  // baseline
        for (int i = 1; i <= 8; i++) {
            controller.onBqrSample(MAC, midBandBqr(), 26_000L + i * 6_000L);  // 32..74
        }
        assertEquals(900, controller.snapshot(MAC, 74_000L).ceilingKbps);
        for (int i = 9; i <= 20; i++) {
            controller.onBqrSample(MAC, midBandBqr(), 26_000L + i * 6_000L);  // 80..146
        }
        assertEquals(1000, controller.snapshot(MAC, 146_000L).ceilingKbps);
        // The test hook bypasses the publish path, so the ceiling value never changed from
        // the activation-time 1000 and the recovery lands in the state channel.
        assertTrue(recorder.stateEvents.contains("1000:bqr_fallback_recovered"));
    }

    @Test
    public void strictTierKeepsStreakAcrossOneSidedHotWindows() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        controller.setBqrFallbackCapKbpsForTest(MAC, 900, 10_000L);
        assertEquals(900, controller.snapshot(MAC, 10_000L).ceilingKbps);

        // Decision 45 (feedback 011139): one-sided hot windows (retx>=30 alone with clean
        // noRx) are neutral for the strict tier — they keep the streak without counting.
        // The headset's 900-tier retx band (26-42) straddles the 30 gate while noRx stays
        // clean, so the old reset-on-one-sided made 900->1000 unreachable.
        controller.onBqrSample(MAC, healthyBqr(), 20_000L);  // baseline
        controller.onBqrSample(MAC, strictOneSidedRetxBqr(), 26_000L);  // neutral
        for (int i = 1; i <= 6; i++) {
            controller.onBqrSample(MAC, midBandBqr(), 32_000L + i * 6_000L);  // 38..68
        }
        controller.onBqrSample(MAC, strictOneSidedRetxBqr(), 74_000L);  // neutral
        for (int i = 1; i <= 3; i++) {
            controller.onBqrSample(MAC, midBandBqr(), 80_000L + i * 6_000L);  // 86..98
        }
        // 9 counted windows (38..68 + 86..98); hold (10s trigger + 120s) not yet elapsed.
        assertEquals(900, controller.snapshot(MAC, 98_000L).ceilingKbps);
        for (int i = 1; i <= 6; i++) {
            controller.onBqrSample(MAC, midBandBqr(), 104_000L + i * 6_000L);  // 110..134
        }
        // 134s window: hold elapsed and the streak survived the one-sided windows.
        assertEquals(1000, controller.snapshot(MAC, 134_000L).ceilingKbps);
        assertTrue(recorder.fallbackEvents.contains("0:recovered:0:14"));
    }

    @Test
    public void strictTierEscalatesHoldAfterQuickReTrigger() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);

        // First trigger at 34s: 1000 -> 900, level 0 -> 120s strict hold.
        controller.onBqrSample(MAC, bqrFallbackBad(), 10_000L);  // baseline
        for (int i = 1; i <= 4; i++) {
            controller.onBqrSample(MAC, bqrFallbackBad(), 10_000L + i * 6_000L);  // 16..34
        }
        assertEquals(900, controller.snapshot(MAC, 34_000L).ceilingKbps);
        assertEquals("0:120000", recorder.fallbackDetails.get(recorder.fallbackDetails.size() - 1));

        // Recover at 154s (120s hold from 34s; dead zone until 44s).
        for (int i = 0; i <= 20; i++) {
            controller.onBqrSample(MAC, midBandBqr(), 44_000L + i * 6_000L);  // 44..164
        }
        assertEquals(1000, controller.snapshot(MAC, 154_000L).ceilingKbps);

        // Re-trigger at 188s, 34s after recovery -> escalation level 1 (240s strict hold).
        for (int i = 0; i < 4; i++) {
            controller.onBqrSample(MAC, bqrFallbackBad(), 170_000L + i * 6_000L);  // 170..188
        }
        assertEquals(900, controller.snapshot(MAC, 188_000L).ceilingKbps);
        assertEquals("1:240000", recorder.fallbackDetails.get(recorder.fallbackDetails.size() - 1));

        // 194s window is inside the 10s dead zone; recovery evidence starts at 200s.
        controller.onBqrSample(MAC, midBandBqr(), 194_000L);  // dead zone skip
        for (int i = 0; i <= 16; i++) {
            controller.onBqrSample(MAC, midBandBqr(), 200_000L + i * 6_000L);  // 200..296
        }
        // 17 windows by 296s are not enough: the 240s hold blocks until 428s.
        assertEquals(900, controller.snapshot(MAC, 296_000L).ceilingKbps);
        for (int i = 17; i <= 39; i++) {
            controller.onBqrSample(MAC, midBandBqr(), 200_000L + i * 6_000L);  // 302..434
        }
        assertEquals(1000, controller.snapshot(MAC, 434_000L).ceilingKbps);
    }

    @Test
    public void midTierCountsRelaxedNoRxButKeepsRetxAndOneSidedHotGates() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        controller.setBqrFallbackCapKbpsForTest(MAC, 500, 10_000L);
        assertEquals(500, controller.snapshot(MAC, 10_000L).ceilingKbps);

        // Decision 44 (feedback 233639): X3-family noRx 21-25 windows now count for the
        // 500->900 tier; retx stays strict <24 and a one-sided hot noRx >= 25 resets.
        controller.onBqrSample(MAC, healthyBqr(), 20_000L);  // baseline
        for (int i = 1; i <= 6; i++) {
            controller.onBqrSample(MAC, midTierRelaxedBqr(), 26_000L + i * 6_000L);  // 32..62
        }
        assertEquals(500, controller.snapshot(MAC, 62_000L).ceilingKbps);  // hold not elapsed
        for (int i = 7; i <= 9; i++) {
            controller.onBqrSample(MAC, midTierRelaxedBqr(), 26_000L + i * 6_000L);  // 68..80
        }
        assertEquals(900, controller.snapshot(MAC, 80_000L).ceilingKbps);  // hold done at 70s
        // 74s window: 8 consecutive relaxed windows (32..74), hold (10s trigger + 60s) elapsed.
        assertTrue(recorder.fallbackEvents.contains("900:recovered:0:8"));

        // One-sided hot noRx (23/26) resets the streak even after the hold.
        controller.setBqrFallbackCapKbpsForTest(MAC, 500, 100_000L);
        controller.onBqrSample(MAC, healthyBqr(), 110_000L);  // baseline
        for (int i = 1; i <= 6; i++) {
            controller.onBqrSample(MAC, midTierRelaxedBqr(), 116_000L + i * 6_000L);  // 122..152
        }
        controller.onBqrSample(MAC, oneSidedNoRxBqr(), 158_000L);  // resets streak
        assertEquals(500, controller.snapshot(MAC, 164_000L).ceilingKbps);
        for (int i = 1; i <= 6; i++) {
            controller.onBqrSample(MAC, midTierRelaxedBqr(), 164_000L + i * 6_000L);  // 170..200
        }
        assertEquals(900, controller.snapshot(MAC, 200_000L).ceilingKbps);  // hold from 100s

        // retx >= 24 still resets for the mid tier (strict <24 gate kept): the X3
        // mid-band 26/23 sample counts for the strict 900 tier but not for 500->900.
        controller.setBqrFallbackCapKbpsForTest(MAC, 500, 220_000L);
        controller.onBqrSample(MAC, healthyBqr(), 230_000L);  // baseline
        for (int i = 1; i <= 10; i++) {
            controller.onBqrSample(MAC, midBandBqr(), 236_000L + i * 6_000L);  // 242..296
        }
        assertEquals(500, controller.snapshot(MAC, 296_000L).ceilingKbps);
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

    @Test
    public void startGuardSuppressesBadWindowAccumulation() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        controller.setPeerCeilingKbps(MAC, 900, 200L, "codec_confirmed");

        // Guard runs until 15.1 s: the 13 s window is the first valid one and falls inside,
        // so it must not accumulate.
        controller.onBqrSample(MAC, bqrFallbackBad(), 10_000L);  // baseline, interval 0
        controller.onBqrSample(MAC, bqrFallbackBad(), 13_000L);  // inside guard -> skipped
        controller.onBqrSample(MAC, bqrFallbackBad(), 19_000L);  // +1
        controller.onBqrSample(MAC, bqrFallbackBad(), 25_000L);  // +2
        controller.onBqrSample(MAC, bqrFallbackBad(), 31_000L);  // +3, no trigger yet
        assertEquals(900, controller.snapshot(MAC, 31_000L).ceilingKbps);
        assertTrue(recorder.skippedBqrWindows.contains("start_guard"));

        controller.onBqrSample(MAC, bqrFallbackBad(), 37_000L);  // +4 -> clamp
        assertEquals(500, controller.snapshot(MAC, 37_000L).ceilingKbps);
        assertTrue(recorder.fallbackEvents.contains("500:triggered:4:0"));
    }

    @Test
    public void illegalBqrFieldsAreSkippedAndDoNotCount() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        controller.setPeerCeilingKbps(MAC, 900, 200L, "codec_confirmed");

        controller.onBqrSample(MAC, bqrFallbackBad(), 10_000L);  // baseline
        controller.onBqrSample(MAC, bqrFallbackBad(), 16_000L);  // +1
        controller.onBqrSample(MAC, illegalBqr(), 22_000L);      // skipped (retx = -1)
        controller.onBqrSample(MAC, bqrFallbackBad(), 28_000L);  // +2
        controller.onBqrSample(MAC, bqrFallbackBad(), 34_000L);  // +3, no trigger
        assertEquals(900, controller.snapshot(MAC, 34_000L).ceilingKbps);
        assertTrue(recorder.skippedBqrWindows.contains("illegal_fields"));

        controller.onBqrSample(MAC, bqrFallbackBad(), 40_000L);  // +4 -> clamp
        assertEquals(500, controller.snapshot(MAC, 40_000L).ceilingKbps);
        assertTrue(recorder.fallbackEvents.contains("500:triggered:4:0"));
    }

    @Test
    public void postSwitchGuardSuppressesChoppyCongestion() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        controller.setPeerCeilingKbps(MAC, 900, 200L, "codec_confirmed");
        controller.onBqrSample(MAC, bqrFallbackBad(), 10_000L);  // baseline
        for (int i = 1; i <= 4; i++) {
            controller.onBqrSample(MAC, bqrFallbackBad(), 10_000L + i * 6_000L);
        }
        assertEquals(500, controller.snapshot(MAC, 34_000L).ceilingKbps);  // switch at 34 s

        // Choppy inside the 10 s POST_SWITCH_GUARD (until 44 s) is recorded, not integrated:
        // lastCongestionMs stays at the session start.
        controller.onRemoteChoppyReport(MAC, 1, 36_000L);
        assertTrue(controller.snapshot(MAC, 36_100L).lastCongestionAgoMs > 30_000L);

        // After the guard expires the same signal integrates normally.
        controller.onRemoteChoppyReport(MAC, 1, 45_000L);
        assertTrue(controller.snapshot(MAC, 45_100L).lastCongestionAgoMs < 1_000L);
    }

    @Test
    public void resumeReArmsStartGuard() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        controller.setPeerCeilingKbps(MAC, 900, 200L, "codec_confirmed");

        controller.onBqrSample(MAC, bqrFallbackBad(), 10_000L);  // baseline
        controller.onBqrSample(MAC, bqrFallbackBad(), 13_000L);  // inside initial guard
        controller.onBqrSample(MAC, bqrFallbackBad(), 16_000L, false);  // stream idle
        controller.onBqrSample(MAC, bqrFallbackBad(), 17_000L, true);   // resume edge -> guard to 32 s
        controller.onBqrSample(MAC, bqrFallbackBad(), 20_000L);  // suppressed
        controller.onBqrSample(MAC, bqrFallbackBad(), 26_000L);  // suppressed
        controller.onBqrSample(MAC, bqrFallbackBad(), 32_000L);  // +1 (guard ended 32 s)
        controller.onBqrSample(MAC, bqrFallbackBad(), 38_000L);  // +2
        assertEquals(900, controller.snapshot(MAC, 38_000L).ceilingKbps);

        controller.onBqrSample(MAC, bqrFallbackBad(), 44_000L);  // +3
        controller.onBqrSample(MAC, bqrFallbackBad(), 50_000L);  // +4 -> clamp
        assertEquals(500, controller.snapshot(MAC, 50_000L).ceilingKbps);
    }

    @Test
    public void suspectCombinationIsLoggedButNeverDecides() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        controller.setPeerCeilingKbps(MAC, 900, 200L, "codec_confirmed");
        controller.onQueueSample(MAC, 5, 45, 9_500L);  // low queue

        // First valid window with retx=110/s and noRx=0: physically contradictory, log only.
        controller.onBqrSample(MAC, bqrFallbackBad(), 10_000L);  // baseline
        controller.onBqrSample(MAC, suspectBqr(), 16_000L);
        assertEquals(900, controller.snapshot(MAC, 16_000L).ceilingKbps);
        assertTrue(recorder.skippedBqrWindows.contains("suspect_invalid"));
        assertTrue(recorder.fallbackEvents.isEmpty());
    }

    @Test
    public void startGuardSuppressesChoppyCongestion() {
        LhdcLinkHealthController controller = new LhdcLinkHealthController(null);
        controller.activate(MAC, 100L);

        // START_GUARD runs until 15.1 s: choppy inside it is recorded but not integrated.
        controller.onRemoteChoppyReport(MAC, 1, 5_000L);
        assertTrue(controller.snapshot(MAC, 5_100L).lastCongestionAgoMs > 4_000L);

        controller.onRemoteChoppyReport(MAC, 1, 16_000L);
        assertTrue(controller.snapshot(MAC, 16_100L).lastCongestionAgoMs < 1_000L);
    }

    @Test
    public void startGuardDoesNotBlockHealthyWindowCounting() {
        LhdcLinkHealthController controller = new LhdcLinkHealthController(null);
        controller.activate(MAC, 100L);

        controller.onBqrSample(MAC, healthyBqr(), 10_000L);  // baseline, interval 0
        controller.onBqrSample(MAC, healthyBqr(), 13_000L);  // inside guard but healthy
        assertEquals(1, controller.snapshot(MAC, 13_000L).healthyBqrWindows);
    }

    @Test
    public void queueResumeEdgeReArmsStartGuard() {
        LhdcLinkHealthController controller = new LhdcLinkHealthController(null);
        controller.activate(MAC, 100L);
        controller.setPeerCeilingKbps(MAC, 900, 200L, "codec_confirmed");

        controller.onBqrSample(MAC, bqrFallbackBad(), 10_000L);  // baseline
        controller.onBqrSample(MAC, bqrFallbackBad(), 13_000L);  // inside initial guard
        controller.onBqrSample(MAC, bqrFallbackBad(), 16_000L, false);  // stream idle
        controller.onQueueSample(MAC, 0, 45, 17_000L);  // resume edge -> guard to 32 s
        controller.onBqrSample(MAC, bqrFallbackBad(), 20_000L);  // suppressed
        controller.onBqrSample(MAC, bqrFallbackBad(), 26_000L);  // suppressed
        controller.onBqrSample(MAC, bqrFallbackBad(), 32_000L);  // +1 (guard ended 32 s)
        controller.onBqrSample(MAC, bqrFallbackBad(), 38_000L);  // +2
        assertEquals(900, controller.snapshot(MAC, 38_000L).ceilingKbps);

        controller.onBqrSample(MAC, bqrFallbackBad(), 44_000L);  // +3
        controller.onBqrSample(MAC, bqrFallbackBad(), 50_000L);  // +4 -> clamp
        assertEquals(500, controller.snapshot(MAC, 50_000L).ceilingKbps);
    }

    @Test
    public void suspectWindowDoesNotResetBadStreak() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        controller.setPeerCeilingKbps(MAC, 900, 200L, "codec_confirmed");

        controller.onBqrSample(MAC, bqrFallbackBad(), 10_000L);  // baseline
        controller.onBqrSample(MAC, bqrFallbackBad(), 16_000L);  // +1
        controller.onBqrSample(MAC, suspectBqr(), 22_000L);      // suspect: skip, keep streak
        controller.onBqrSample(MAC, bqrFallbackBad(), 28_000L);  // +2
        controller.onBqrSample(MAC, bqrFallbackBad(), 34_000L);  // +3
        assertEquals(900, controller.snapshot(MAC, 34_000L).ceilingKbps);
        assertTrue(recorder.skippedBqrWindows.contains("suspect_invalid"));

        controller.onBqrSample(MAC, bqrFallbackBad(), 40_000L);  // +4 -> clamp
        assertEquals(500, controller.snapshot(MAC, 40_000L).ceilingKbps);
        assertTrue(recorder.fallbackEvents.contains("500:triggered:4:0"));
    }

    @Test
    public void suspectCombinationIsLoggedEvenWhenPeerCeilingAtOrBelowCap() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        controller.setPeerCeilingKbps(MAC, 500, 200L, "codec_confirmed");
        controller.onQueueSample(MAC, 5, 45, 9_500L);

        controller.onBqrSample(MAC, bqrFallbackBad(), 10_000L);  // baseline
        controller.onBqrSample(MAC, suspectBqr(), 16_000L);
        assertEquals(500, controller.snapshot(MAC, 16_000L).ceilingKbps);
        assertTrue(recorder.skippedBqrWindows.contains("suspect_invalid"));
    }

    @Test
    public void leakyBucketFillsAndDowngradesOneRung() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);

        // Two deduped choppy events 8 s apart: 10 -> (10 - 4) + 10 = 16 >= 15 -> 1000 -> 900.
        controller.onRemoteChoppyReport(MAC, 1, 16_000L);
        assertEquals(1000, controller.snapshot(MAC, 16_000L).ceilingKbps);
        controller.onRemoteChoppyReport(MAC, 1, 24_000L);
        assertEquals(900, controller.snapshot(MAC, 24_000L).ceilingKbps);
        assertTrue(recorder.events.contains("900:leaky_bucket_triggered"));
    }

    @Test
    public void leakyBucketDecaysBelowThresholdWithoutTrigger() {
        LhdcLinkHealthController controller = new LhdcLinkHealthController(null);
        controller.activate(MAC, 100L);

        controller.onRemoteChoppyReport(MAC, 1, 16_000L);  // +10
        controller.onRemoteChoppyReport(MAC, 1, 40_000L);  // 24 s decay -> 0, then +10 < 15
        assertEquals(1000, controller.snapshot(MAC, 40_000L).ceilingKbps);
    }

    @Test
    public void leakyBucketOnPeerCappedDeviceTargets500() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        controller.setPeerCeilingKbps(MAC, 900, 200L, "codec_confirmed");

        controller.onRemoteChoppyReport(MAC, 1, 16_000L);
        controller.onRemoteChoppyReport(MAC, 1, 24_000L);
        assertEquals(500, controller.snapshot(MAC, 24_000L).ceilingKbps);
        assertTrue(recorder.events.contains("500:leaky_bucket_triggered"));
    }

    @Test
    public void leakyBucketRecoversAfterHealthyWindowsAndHold() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);

        controller.onRemoteChoppyReport(MAC, 1, 16_000L);
        controller.onRemoteChoppyReport(MAC, 1, 24_000L);
        assertEquals(900, controller.snapshot(MAC, 24_000L).ceilingKbps);

        // 7 healthy windows (36..78 s): 6 complete by 66 s but the 60 s hold from the
        // trigger (24 s) has not elapsed, so the cap stays.
        for (int i = 1; i <= 7; i++) {
            controller.onBqrSample(MAC, healthyBqr(), 30_000L + i * 6_000L);
        }
        assertEquals(900, controller.snapshot(MAC, 78_000L).ceilingKbps);

        // The 84 s window satisfies both the hold and the healthy-window count.
        for (int i = 8; i <= 9; i++) {
            controller.onBqrSample(MAC, healthyBqr(), 30_000L + i * 6_000L);
        }
        assertEquals(1000, controller.snapshot(MAC, 84_000L).ceilingKbps);
        assertTrue(recorder.events.contains("1000:leaky_bucket_recovered"));
    }

    @Test
    public void leap8sShadowFiresButKeepsCeiling() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);

        controller.onBqrSample(MAC, bqrFallbackBad(), 10_000L);   // baseline
        controller.onBqrSample(MAC, bqrFallbackBad(), 16_000L);   // bad, no choppy yet
        controller.onRemoteChoppyReport(MAC, 1, 17_000L);         // choppy within 8 s window
        controller.onQueueSample(MAC, 45, 45, 21_500L);           // high-queue segment starts
        controller.onQueueSample(MAC, 45, 45, 21_700L);
        controller.onQueueSample(MAC, 45, 45, 21_900L);           // >= 300 ms accumulated
        controller.onBqrSample(MAC, bqrFallbackBad(), 22_000L);   // aligned -> shadow fires

        assertTrue(recorder.shadowTriggers.contains("leap_8s:1000:500"));
        assertEquals(1000, controller.snapshot(MAC, 22_000L).ceilingKbps);
    }

    @Test
    public void disasterNoRxShadowFiresWithSnapshotButKeepsCeiling() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);

        controller.onBqrSample(MAC, healthyBqr(), 10_000L);       // baseline
        controller.onQueueSample(MAC, 45, 45, 15_000L);           // critical queue
        controller.onQueueSample(MAC, 45, 45, 15_300L);           // >= 300 ms
        controller.onBqrSample(MAC, disasterNoRxBqr(), 16_000L);  // noRx = 110/s

        assertTrue(recorder.shadowTriggers.contains("disaster_noRx:1000:400"));
        assertFalse(recorder.shadowSnapshots.isEmpty());
        assertTrue(recorder.shadowSnapshots.get(0).startsWith("r110.0/n110.0@"));
        assertEquals(1000, controller.snapshot(MAC, 16_000L).ceilingKbps);
    }

    @Test
    public void disasterRetxShadowFiresWhenNoRxLow() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);

        controller.onBqrSample(MAC, healthyBqr(), 10_000L);
        controller.onQueueSample(MAC, 45, 45, 15_000L);
        controller.onQueueSample(MAC, 45, 45, 15_300L);
        controller.onBqrSample(MAC, disasterRetxBqr(), 16_000L);   // retx = 110/s, noRx = 20/s

        assertTrue(recorder.shadowTriggers.contains("disaster_retx:1000:500"));
        assertEquals(1000, controller.snapshot(MAC, 16_000L).ceilingKbps);
    }

    @Test
    public void resetDeviceClearsPendingTransaction() {
        LhdcLinkHealthController controller = new LhdcLinkHealthController(null);
        controller.activate(MAC, 100L);
        controller.onTargetCapIssued(MAC, 500, 7, 1_000L);
        controller.resetDevice(MAC, 2_000L, "policy_adaptive");
        assertNull(controller.tickSwitchTransactions(MAC, 10_000L));
    }

    @Test
    public void suspendedPseudoHighWindowDoesNotTriggerDisasterShadow() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);

        // Decision 37 anchor sample: retx 111.8/s with streaming=false (suspended
        // accumulation) plus a critical queue must not hit the shadow sentinel.
        controller.onBqrSample(MAC, healthyBqr(), 10_000L);
        controller.onQueueSample(MAC, 45, 45, 15_000L);
        controller.onQueueSample(MAC, 45, 45, 15_300L);
        controller.onBqrSample(MAC, disasterNoRxBqr(), 16_000L, false);

        assertTrue(recorder.shadowTriggers.isEmpty());
        assertEquals(1000, controller.snapshot(MAC, 16_000L).ceilingKbps);
    }

    @Test
    public void congestionEventsDoNotResetCriticalQueueTimer() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);

        controller.onBqrSample(MAC, healthyBqr(), 10_000L);
        controller.onQueueSample(MAC, 41, 45, 15_000L);   // 91% critical, not full
        controller.onCongestion(MAC, 15_100L);            // soft congestion in between
        controller.onBqrSample(MAC, disasterNoRxBqr(), 15_300L);  // 300 ms later

        // With the pre-Phase-3 noteCongestion reset the timer would be wiped at 15.1 s and
        // this shadow could not fire.
        assertTrue(recorder.shadowTriggers.contains("disaster_noRx:1000:400"));
    }

    @Test
    public void leakyBucketDoesNotIntegrateDuringStartGuard() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);

        // Two choppy events inside the 15 s guard would fill 20 >= 15 if integrated.
        controller.onRemoteChoppyReport(MAC, 1, 5_000L);
        controller.onRemoteChoppyReport(MAC, 1, 10_000L);
        controller.onRemoteChoppyReport(MAC, 1, 16_000L);  // outside guard: +10 only
        assertEquals(1000, controller.snapshot(MAC, 16_000L).ceilingKbps);
        assertFalse(recorder.events.contains("900:leaky_bucket_triggered"));
    }

    @Test
    public void leakyBucketDoesNotIntegrateWhileAnyCapActive() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);

        controller.onRemoteChoppyReport(MAC, 1, 16_000L);
        controller.onRemoteChoppyReport(MAC, 1, 24_000L);  // trigger -> 900
        assertEquals(900, controller.snapshot(MAC, 24_000L).ceilingKbps);

        // While the cap is active the bucket must not keep integrating (review P2-3):
        // a burst during the cap must not re-trigger immediately after recovery.
        controller.onRemoteChoppyReport(MAC, 1, 30_000L);
        controller.onRemoteChoppyReport(MAC, 1, 31_000L);
        for (int i = 1; i <= 9; i++) {
            controller.onBqrSample(MAC, healthyBqr(), 36_000L + i * 6_000L);
        }
        // Recovery happens at 84 s (hold from 24 s); only one trigger event in total.
        assertEquals(1000, controller.snapshot(MAC, 90_000L).ceilingKbps);
        assertEquals(1, recorder.events.stream()
                .filter(e -> e.endsWith("leaky_bucket_triggered")).count());
    }

    @Test
    public void disasterSnapshotRingRollsPastFourWindows() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);

        controller.onBqrSample(MAC, healthyBqr(), 10_000L);  // baseline
        controller.onBqrSample(MAC, healthyBqr(), 16_000L);
        controller.onBqrSample(MAC, healthyBqr(), 22_000L);
        controller.onBqrSample(MAC, healthyBqr(), 28_000L);
        controller.onBqrSample(MAC, healthyBqr(), 34_000L);  // ring now full (16..34)
        controller.onQueueSample(MAC, 45, 45, 35_000L);
        controller.onQueueSample(MAC, 45, 45, 35_300L);
        controller.onBqrSample(MAC, disasterNoRxBqr(), 40_000L);  // rolls past slot 0

        assertTrue(recorder.shadowTriggers.contains("disaster_noRx:1000:400"));
        String snapshot = recorder.shadowSnapshots.get(0);
        assertEquals(3, snapshot.chars().filter(c -> c == ';').count());
        assertTrue(snapshot.startsWith("r15.0/n10.0@22000"));
        assertTrue(snapshot.endsWith("r110.0/n110.0@40000"));
    }

    @Test
    public void leapDoesNotFireWhenChoppyOutside8sWindow() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);

        controller.onBqrSample(MAC, bqrFallbackBad(), 10_000L);   // baseline
        controller.onRemoteChoppyReport(MAC, 1, 13_000L);         // 9 s before the bad window
        controller.onQueueSample(MAC, 45, 45, 21_500L);
        controller.onQueueSample(MAC, 45, 45, 21_700L);
        controller.onQueueSample(MAC, 45, 45, 21_900L);
        controller.onBqrSample(MAC, bqrFallbackBad(), 22_000L);

        assertFalse(recorder.shadowTriggers.contains("leap_8s:1000:500"));
    }

    @Test
    public void leapDoesNotFireWhenQueueDrained() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);

        controller.onBqrSample(MAC, bqrFallbackBad(), 10_000L);
        controller.onRemoteChoppyReport(MAC, 1, 17_000L);
        controller.onQueueSample(MAC, 45, 45, 21_500L);
        controller.onQueueSample(MAC, 45, 45, 21_700L);
        controller.onQueueSample(MAC, 45, 45, 21_900L);   // >= 300 ms accumulated
        controller.onQueueSample(MAC, 0, 45, 22_000L);    // drained -> accumulation cleared
        controller.onBqrSample(MAC, bqrFallbackBad(), 22_500L);

        assertFalse(recorder.shadowTriggers.contains("leap_8s:1000:500"));
    }

    @Test
    public void disasterShadowRespectsCooldownAndPeerCap() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);

        controller.onBqrSample(MAC, healthyBqr(), 10_000L);
        controller.onQueueSample(MAC, 45, 45, 15_000L);
        controller.onQueueSample(MAC, 45, 45, 15_300L);
        controller.onBqrSample(MAC, disasterNoRxBqr(), 16_000L);  // fires
        controller.onBqrSample(MAC, disasterNoRxBqr(), 22_000L);  // 6 s later: cooldown
        assertEquals(1, recorder.shadowTriggers.stream()
                .filter(t -> t.startsWith("disaster_noRx")).count());

        // A peer-capped device never sees the 1000-tier disaster candidate.
        Recorder cappedRecorder = new Recorder();
        LhdcLinkHealthController capped = new LhdcLinkHealthController(cappedRecorder);
        capped.activate(MAC, 100L);
        capped.setPeerCeilingKbps(MAC, 900, 200L, "codec_confirmed");
        capped.onBqrSample(MAC, healthyBqr(), 10_000L);
        capped.onQueueSample(MAC, 45, 45, 15_000L);
        capped.onQueueSample(MAC, 45, 45, 15_300L);
        capped.onBqrSample(MAC, disasterNoRxBqr(), 16_000L);
        assertTrue(cappedRecorder.shadowTriggers.isEmpty());
    }

    @Test
    public void codecWriteClearsLeakyCapAndBucket() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);

        controller.onRemoteChoppyReport(MAC, 1, 16_000L);
        controller.onRemoteChoppyReport(MAC, 1, 24_000L);  // trigger -> 900
        assertEquals(900, controller.snapshot(MAC, 24_000L).ceilingKbps);

        controller.setPeerCeilingKbps(MAC, 1000, 30_000L, "codec_confirmed");
        assertEquals(1000, controller.snapshot(MAC, 30_000L).ceilingKbps);

        // One choppy after the user write starts from a clean bucket (no re-trigger).
        controller.onRemoteChoppyReport(MAC, 1, 32_000L);
        assertEquals(1000, controller.snapshot(MAC, 32_000L).ceilingKbps);
        assertEquals(1, recorder.events.stream()
                .filter(e -> e.endsWith("leaky_bucket_triggered")).count());
    }

    @Test
    public void leakyBucketRecoversDespiteHighAfhUsage() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);

        controller.onRemoteChoppyReport(MAC, 1, 16_000L);
        controller.onRemoteChoppyReport(MAC, 1, 24_000L);  // trigger -> 900
        assertEquals(900, controller.snapshot(MAC, 24_000L).ceilingKbps);

        // X3 regression (feedback 205714): windows with unusedAfh 51-54 must still count as
        // recovery evidence — the old strictlyHealthy gate (AFH<=49) stranded the rung.
        for (int i = 1; i <= 9; i++) {
            controller.onBqrSample(MAC, healthyBqrHighAfh(), 36_000L + i * 6_000L);
        }
        assertEquals(1000, controller.snapshot(MAC, 84_000L).ceilingKbps);
        assertTrue(recorder.events.contains("1000:leaky_bucket_recovered"));
    }

    @Test
    public void leakyRecoveryRequiresStreamingAndLegalWindows() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);

        controller.onRemoteChoppyReport(MAC, 1, 16_000L);
        controller.onRemoteChoppyReport(MAC, 1, 24_000L);  // trigger -> 900
        assertEquals(900, controller.snapshot(MAC, 24_000L).ceilingKbps);

        // A suspended (streaming=false) healthy-rate window at hold expiry must not count:
        // without the gate the streak reaches 6 at 84 s and recovers immediately.
        controller.onBqrSample(MAC, healthyBqr(), 48_000L);              // baseline
        for (int i = 1; i <= 5; i++) {
            controller.onBqrSample(MAC, healthyBqr(), 54_000L + i * 6_000L);  // 60..84
        }
        controller.onBqrSample(MAC, healthyBqr(), 90_000L, false);       // hold expired, idle
        assertEquals(900, controller.snapshot(MAC, 90_000L).ceilingKbps);
        controller.onBqrSample(MAC, healthyBqr(), 96_000L);              // 6th counted window
        assertEquals(1000, controller.snapshot(MAC, 96_000L).ceilingKbps);
    }

    @Test
    public void leakyRecoveryIgnoresIllegalWindows() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);

        controller.onRemoteChoppyReport(MAC, 1, 16_000L);
        controller.onRemoteChoppyReport(MAC, 1, 24_000L);  // trigger -> 900

        // A window with negative counters is numerically healthy (negative rates < 24/21)
        // but must not count: without the legalWindow gate the streak reaches 6 at 84 s.
        controller.onBqrSample(MAC, healthyBqr(), 48_000L);        // baseline
        for (int i = 1; i <= 5; i++) {
            controller.onBqrSample(MAC, healthyBqr(), 54_000L + i * 6_000L);  // 60..84
        }
        controller.onBqrSample(MAC, negativeBqr(), 90_000L);       // illegal at hold expiry
        assertEquals(900, controller.snapshot(MAC, 90_000L).ceilingKbps);
        controller.onBqrSample(MAC, healthyBqr(), 96_000L);
        assertEquals(1000, controller.snapshot(MAC, 96_000L).ceilingKbps);
    }

    @Test
    public void leakyRecoveryCountsMidBandWindows() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);

        controller.onRemoteChoppyReport(MAC, 1, 16_000L);
        controller.onRemoteChoppyReport(MAC, 1, 24_000L);  // trigger -> 900

        controller.onBqrSample(MAC, healthyBqr(), 36_000L);        // baseline
        for (int i = 1; i <= 6; i++) {
            controller.onBqrSample(MAC, healthyBqr(), 42_000L + i * 6_000L);  // 48..78
        }
        // X3 mid-band window (26/23) at hold expiry counts: the strict <24/<21 gate would
        // have ignored it and deferred recovery (feedback 213744).
        controller.onBqrSample(MAC, midBandBqr(), 84_000L);
        assertEquals(1000, controller.snapshot(MAC, 84_000L).ceilingKbps);
        assertTrue(recorder.events.contains("1000:leaky_bucket_recovered"));
    }

    @Test
    public void leakyRecoveryBadThresholdBoundaryStrictlyBelow() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);

        controller.onRemoteChoppyReport(MAC, 1, 16_000L);
        controller.onRemoteChoppyReport(MAC, 1, 24_000L);  // trigger -> 900

        controller.onBqrSample(MAC, healthyBqr(), 36_000L);        // baseline
        for (int i = 1; i <= 6; i++) {
            controller.onBqrSample(MAC, healthyBqr(), 42_000L + i * 6_000L);  // 48..78
        }
        // Exactly 30.0/25.0 is the bad-window boundary: the strict < complement must not
        // count it, so the streak resets at 84 s and recovery waits for 96..126.
        controller.onBqrSample(MAC, boundaryBqr(), 84_000L);
        assertEquals(900, controller.snapshot(MAC, 90_000L).ceilingKbps);
        for (int i = 1; i <= 6; i++) {
            controller.onBqrSample(MAC, healthyBqr(), 90_000L + i * 6_000L);  // 96..126
        }
        assertEquals(1000, controller.snapshot(MAC, 126_000L).ceilingKbps);
    }

    @Test
    public void leakyRetriggerBlockedWithinDeadZoneAfterRecovery() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);

        controller.onRemoteChoppyReport(MAC, 1, 16_000L);
        controller.onRemoteChoppyReport(MAC, 1, 24_000L);  // trigger -> 900
        controller.onBqrSample(MAC, healthyBqr(), 36_000L);        // baseline
        for (int i = 1; i <= 6; i++) {
            controller.onBqrSample(MAC, healthyBqr(), 42_000L + i * 6_000L);  // 48..78
        }
        controller.onBqrSample(MAC, healthyBqr(), 84_000L);       // hold expired -> recovery
        assertEquals(1000, controller.snapshot(MAC, 84_000L).ceilingKbps);

        // A choppy pair 16 s after recovery (outside POST_SWITCH_GUARD) fills the bucket
        // but the 60 s re-trigger dead zone keeps the rung.
        controller.onRemoteChoppyReport(MAC, 1, 100_000L);
        controller.onRemoteChoppyReport(MAC, 1, 101_000L);
        assertEquals(1000, controller.snapshot(MAC, 101_000L).ceilingKbps);
        assertEquals(1, recorder.events.stream()
                .filter(e -> e.endsWith("leaky_bucket_triggered")).count());

        // After the dead zone expires the same pair triggers again.
        controller.onRemoteChoppyReport(MAC, 1, 150_000L);
        controller.onRemoteChoppyReport(MAC, 1, 151_000L);
        assertEquals(900, controller.snapshot(MAC, 151_000L).ceilingKbps);
    }

    @Test
    public void leakyRecoverySingleSidedHotWindowResetsStreak() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);

        controller.onRemoteChoppyReport(MAC, 1, 16_000L);
        controller.onRemoteChoppyReport(MAC, 1, 24_000L);  // trigger -> 900
        controller.onBqrSample(MAC, healthyBqr(), 36_000L);        // baseline
        for (int i = 1; i <= 6; i++) {
            controller.onBqrSample(MAC, healthyBqr(), 42_000L + i * 6_000L);  // 48..78
        }
        // retx=32/noRx=10 is NOT a bad window (noRx<25) but must still reset the leaky
        // streak: recovery evidence is the conservative AND sub-complement (review P2-1).
        controller.onBqrSample(MAC, oneSidedBqr(), 84_000L);
        assertEquals(900, controller.snapshot(MAC, 90_000L).ceilingKbps);
        for (int i = 1; i <= 6; i++) {
            controller.onBqrSample(MAC, healthyBqr(), 90_000L + i * 6_000L);  // 96..126
        }
        assertEquals(1000, controller.snapshot(MAC, 126_000L).ceilingKbps);
    }

    @Test
    public void bqrRecoveryBoundaryWindowsStrictlyBelowThresholds() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        controller.setPeerCeilingKbps(MAC, 900, 200L, "codec_confirmed");

        controller.onBqrSample(MAC, bqrFallbackBad(), 10_000L);   // baseline
        for (int i = 0; i < 4; i++) {
            controller.onBqrSample(MAC, bqrFallbackBad(), 16_000L + i * 6_000L);  // 16..34
        }
        assertEquals(500, controller.snapshot(MAC, 34_000L).ceilingKbps);

        // 6 deep-healthy windows plus an exactly-24.0/21.0 window at hold expiry: the
        // strict < gate must reset, deferring recovery to the following six windows.
        controller.onBqrSample(MAC, healthyBqr(), 40_000L);        // baseline
        for (int i = 1; i <= 6; i++) {
            controller.onBqrSample(MAC, healthyBqr(), 46_000L + i * 6_000L);  // 52..82
        }
        controller.onBqrSample(MAC, strictBoundaryBqr(), 88_000L);
        assertEquals(500, controller.snapshot(MAC, 94_000L).ceilingKbps);
        for (int i = 1; i <= 6; i++) {
            controller.onBqrSample(MAC, healthyBqr(), 94_000L + i * 6_000L);  // 100..130
        }
        assertEquals(900, controller.snapshot(MAC, 130_000L).ceilingKbps);
    }

    @Test
    public void leakyRecoveryKeepsBqrCapWhenPresent() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);

        controller.onRemoteChoppyReport(MAC, 1, 16_000L);
        controller.onRemoteChoppyReport(MAC, 1, 24_000L);  // leaky -> 900
        controller.onBqrSample(MAC, bqrFallbackBad(), 30_000L);  // baseline
        for (int i = 0; i < 4; i++) {
            controller.onBqrSample(MAC, bqrFallbackBad(), 36_000L + i * 6_000L);  // 36..54
        }
        assertEquals(500, controller.snapshot(MAC, 54_000L).ceilingKbps);  // bqr cap

        // Leaky recovers at 96 s (6 healthy 66..96 + hold from 24 s) while the bqr 500 cap
        // stays in force until its own recovery at 114 s (hold from 54 s).
        for (int i = 0; i < 8; i++) {
            controller.onBqrSample(MAC, healthyBqr(), 66_000L + i * 6_000L);  // 66..108
        }
        assertEquals(500, controller.snapshot(MAC, 108_000L).ceilingKbps);  // leaky done, bqr holds
        assertTrue(recorder.stateEvents.contains("500:leaky_bucket_recovered"));
        for (int i = 8; i < 10; i++) {
            controller.onBqrSample(MAC, healthyBqr(), 66_000L + i * 6_000L);  // 114..120
        }
        // Phase N-4 tiered recovery: 500 recovers one rung to 900 at 114 s; the strict
        // 900->1000 tier (8 windows + 120 s hold from the 114 s partial recovery) completes
        // at 234 s.
        assertEquals(900, controller.snapshot(MAC, 120_000L).ceilingKbps);
        for (int i = 10; i < 30; i++) {
            controller.onBqrSample(MAC, healthyBqr(), 66_000L + i * 6_000L);  // 126..240
        }
        assertEquals(1000, controller.snapshot(MAC, 240_000L).ceilingKbps);
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

    /** Illegal fields must never drive downgrade bookkeeping (Phase N-2 valid gate). */
    private static LhdcLinkHealthController.BqrSample illegalBqr() {
        return new LhdcLinkHealthController.BqrSample(
                30, 0, -1, 160, 8, -50, 0, 0, 0);
    }

    /** 6 s window: 660 retx -> 110/s with zero noRx: the BQR_SUSPECT_INVALID combo. */
    private static LhdcLinkHealthController.BqrSample suspectBqr() {
        return new LhdcLinkHealthController.BqrSample(
                50, 0, 660, 0, 0, -45, 0, 0, 0);
    }

    /** 6 s window: 660 retx / 660 noRx -> 110/s each: the disaster noRx branch. */
    private static LhdcLinkHealthController.BqrSample disasterNoRxBqr() {
        return new LhdcLinkHealthController.BqrSample(
                50, 0, 660, 660, 0, -45, 0, 0, 0);
    }

    /** 6 s window: 660 retx -> 110/s with noRx 20/s: the disaster retx branch. */
    private static LhdcLinkHealthController.BqrSample disasterRetxBqr() {
        return new LhdcLinkHealthController.BqrSample(
                50, 0, 660, 120, 0, -45, 0, 0, 0);
    }

    /** retx 15/s, noRx 10/s with unusedAfh=54: healthy by rate but not by the AFH<=49 gate. */
    private static LhdcLinkHealthController.BqrSample healthyBqrHighAfh() {
        return new LhdcLinkHealthController.BqrSample(
                54, 0, 90, 60, 2, -45, 10, 0, 0);
    }

    /** 6 s window: exactly 30.0/25.0 — the bad-window boundary, strict `<` must NOT count. */
    private static LhdcLinkHealthController.BqrSample boundaryBqr() {
        return new LhdcLinkHealthController.BqrSample(
                30, 0, 180, 150, 0, -45, 10, 0, 0);
    }

    /** 6 s window: 26.0/23.0 — the X3 mid-band sample that must count as recovery evidence. */
    private static LhdcLinkHealthController.BqrSample midBandBqr() {
        return new LhdcLinkHealthController.BqrSample(
                30, 0, 156, 138, 0, -45, 10, 0, 0);
    }

    /** 6 s window: retx 23.0/noRx 23.0 — decision 44 relaxed band for the 500->900 tier. */
    private static LhdcLinkHealthController.BqrSample midTierRelaxedBqr() {
        return new LhdcLinkHealthController.BqrSample(
                30, 0, 138, 138, 0, -45, 10, 0, 0);
    }

    /** 6 s window: retx 23.0/noRx 26.0 — one-sided hot noRx: never recovery evidence. */
    private static LhdcLinkHealthController.BqrSample oneSidedNoRxBqr() {
        return new LhdcLinkHealthController.BqrSample(
                30, 0, 138, 156, 0, -45, 10, 0, 0);
    }

    /** 6 s window: retx 33.0/noRx 23.0 — one-sided hot retx: neutral for the strict tier. */
    private static LhdcLinkHealthController.BqrSample strictOneSidedRetxBqr() {
        return new LhdcLinkHealthController.BqrSample(
                30, 0, 198, 138, 0, -45, 10, 0, 0);
    }

    /** 6 s window: retx=32/noRx=10 — single-sided hot: not a bad window, not recovery. */
    private static LhdcLinkHealthController.BqrSample oneSidedBqr() {
        return new LhdcLinkHealthController.BqrSample(
                30, 0, 192, 60, 0, -45, 10, 0, 0);
    }

    /** 6 s window: exactly 24.0/21.0 — the BQR recovery boundary, strict < must NOT count. */
    private static LhdcLinkHealthController.BqrSample strictBoundaryBqr() {
        return new LhdcLinkHealthController.BqrSample(
                30, 0, 144, 126, 0, -45, 10, 0, 0);
    }

    /** Negative counters: numerically healthy rates but illegal fields (legalWindow gate). */
    private static LhdcLinkHealthController.BqrSample negativeBqr() {
        return new LhdcLinkHealthController.BqrSample(
                30, 0, -1, -1, 0, -45, 10, 0, 0);
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
        final List<String> skippedBqrWindows = new ArrayList<>();
        final List<String> shadowTriggers = new ArrayList<>();
        final List<String> shadowSnapshots = new ArrayList<>();

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

        @Override
        public void onBqrWindowSkipped(
                String mac,
                String reason,
                double retransmissionsPerSecond,
                double noRxPerSecond,
                long nowMs) {
            skippedBqrWindows.add(reason);
        }

        @Override
        public void onShadowTrigger(
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
            shadowTriggers.add(kind + ":" + fromKbps + ":" + toKbps);
            if (snapshot != null && !snapshot.isEmpty()) {
                shadowSnapshots.add(snapshot);
            }
        }
    }
}
