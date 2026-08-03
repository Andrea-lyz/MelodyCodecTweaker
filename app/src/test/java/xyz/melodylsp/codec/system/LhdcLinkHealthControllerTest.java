package xyz.melodylsp.codec.system;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
    public void peerCeiling1000RestoresDefaultProbing() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        controller.setPeerCeilingKbps(MAC, 900, 200L, "codec_confirmed");
        controller.setPeerCeilingKbps(MAC, 1000, 300L, "codec_confirmed");
        assertEquals(1000, controller.snapshot(MAC, 300L).ceilingKbps);
    }

    @Test
    public void peerCeiling900SurvivesStreamSessionButResetsWithDevice() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = new LhdcLinkHealthController(recorder);
        controller.activate(MAC, 100L);
        controller.setPeerCeilingKbps(MAC, 900, 200L, "codec_confirmed");
        controller.onStreamSessionChanged(MAC, 7L, 300L);
        assertEquals(900, controller.snapshot(MAC, 300L).ceilingKbps);

        controller.resetDevice(MAC, 400L, "session_reset");
        assertEquals(1000, controller.snapshot(MAC, 400L).ceilingKbps);
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

    private static final class Recorder implements LhdcLinkHealthController.Listener {
        final List<String> events = new ArrayList<>();
        final List<String> stateEvents = new ArrayList<>();

        @Override
        public void onProbeCeilingChanged(String mac, int ceilingKbps, String reason) {
            events.add(ceilingKbps + ":" + reason);
        }

        @Override
        public void onProbeStateChanged(String mac, int ceilingKbps, String reason) {
            stateEvents.add(ceilingKbps + ":" + reason);
        }
    }
}
