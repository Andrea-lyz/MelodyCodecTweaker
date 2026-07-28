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
    public void recoveryRequiresThreeHealthyBqrWindowsAndQuietQueue() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = lockedTo500(recorder);
        controller.onBqrSample(MAC, healthyBqr(), 10_000L);
        controller.onQueueSample(MAC, 0, 45, 10_100L);
        controller.onBqrSample(MAC, healthyBqr(), 16_000L);
        controller.onBqrSample(MAC, healthyBqr(), 22_000L);
        // A busy queue sample just before the third healthy window closes
        // the quiet window so the probe cannot open.
        controller.onQueueSample(MAC, 30, 45, 27_500L);
        controller.onBqrSample(MAC, healthyBqr(), 28_000L);
        assertEquals(500, controller.snapshot(MAC, 40_000L).ceilingKbps);

        // Re-arm a low queue well past the quiet window and the probe opens.
        controller.onQueueSample(MAC, 0, 45, 50_100L);
        controller.onQueueSample(MAC, 0, 45, 70_100L);
        LhdcLinkHealthController.Snapshot snapshot =
                controller.snapshot(MAC, 70_100L);
        assertEquals(900, snapshot.ceilingKbps);
        assertFalse(snapshot.boundary500To900Locked);
        assertEquals("900:healthy_recovery_probe",
                recorder.events.get(recorder.events.size() - 1));
    }

    @Test
    public void currentDeviceBqrValuesDoNotPassHealthyGate() {
        LhdcLinkHealthController controller = lockedTo500(null);
        controller.onBqrSample(MAC, unhealthyCurrentEnvironment(), 10_000L);
        controller.onQueueSample(MAC, 0, 45, 10_100L);
        for (int i = 1; i <= 5; i++) {
            controller.onBqrSample(MAC, unhealthyCurrentEnvironment(),
                    10_000L + i * 6_000L);
        }
        controller.onQueueSample(MAC, 0, 45, 50_000L);
        LhdcLinkHealthController.Snapshot snapshot =
                controller.snapshot(MAC, 50_000L);
        assertEquals(500, snapshot.ceilingKbps);
        assertEquals(0, snapshot.healthyBqrWindows);
        assertEquals(20, snapshot.usableAfhChannels);
        assertTrue(snapshot.retransmissionsPerSecond > 60.0);
        assertTrue(snapshot.noRxPerSecond > 60.0);
    }

    @Test
    public void failedRecoveryProbeEscalatesRequiredHealthEvidence() {
        LhdcLinkHealthController controller = lockedTo500(null);
        long probeAt = openHealthyProbe(controller);

        controller.onGovernorEvent(MAC, LhdcLinkHealthController.EVENT_QUICK_FAILURE,
                500, 900, probeAt + 2_000L);
        LhdcLinkHealthController.Snapshot snapshot =
                controller.snapshot(MAC, probeAt + 2_000L);
        assertEquals(500, snapshot.ceilingKbps);
        assertTrue(snapshot.boundary500To900Locked);
        assertEquals(4, snapshot.requiredHealthyBqrWindows);
        assertEquals(30_000L, snapshot.requiredQuietMs);
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
        assertEquals(3, snapshot.requiredHealthyBqrWindows);
        assertEquals(15_000L, snapshot.requiredQuietMs);
    }

    @Test
    public void stableRecoveryClearsBoundaryLearning() {
        LhdcLinkHealthController controller = lockedTo500(null);
        long probeAt = openHealthyProbe(controller);

        controller.onGovernorEvent(MAC, LhdcLinkHealthController.EVENT_UPGRADE_STABLE,
                500, 900, probeAt + 60_000L);
        LhdcLinkHealthController.Snapshot snapshot =
                controller.snapshot(MAC, probeAt + 60_000L);
        assertEquals(1000, snapshot.ceilingKbps);
        assertFalse(snapshot.boundary500To900Locked);
        assertEquals(0, snapshot.requiredHealthyBqrWindows);
        assertEquals(0L, snapshot.requiredQuietMs);
    }

    @Test
    public void ordinaryReconnectRestoresPerMacCeiling() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = lockedTo500(recorder);
        controller.activate("AA:BB:CC:DD:EE:02", 3_000L);
        assertEquals(1000,
                controller.snapshot("AA:BB:CC:DD:EE:02", 3_000L).ceilingKbps);

        controller.activate(MAC, 4_000L);
        assertEquals(500, controller.snapshot(MAC, 4_000L).ceilingKbps);
        assertEquals("500:device_active", recorder.events.get(recorder.events.size() - 1));
    }

    @Test
    public void reconnectClearsPersistedLocksForSameMac() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = lockedTo500(recorder);
        assertEquals(500, controller.snapshot(MAC, 2_000L).ceilingKbps);

        // Same MAC re-attaches after a >5s streaming gap (e.g. Bluetooth
        // process stayed up but the link dropped and came back). The lock
        // should NOT survive the re-attach.
        controller.activate(MAC, 10_000L);
        LhdcLinkHealthController.Snapshot snapshot =
                controller.snapshot(MAC, 10_000L);
        assertEquals(1000, snapshot.ceilingKbps);
        assertFalse(snapshot.boundary500To900Locked);
        assertEquals("1000:device_active",
                recorder.events.get(recorder.events.size() - 1));
    }

    @Test
    public void backToBackActivateOnSameMacPreservesLock() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = lockedTo500(recorder);
        // Within the MIN_RECONNECT_GAP window, re-attaching the same MAC
        // does not reset the lock — short A2DP bounces should not flush
        // the learning state.
        controller.activate(MAC, 4_000L);
        LhdcLinkHealthController.Snapshot snapshot =
                controller.snapshot(MAC, 4_000L);
        assertEquals(500, snapshot.ceilingKbps);
        assertTrue(snapshot.boundary500To900Locked);
    }

    @Test
    public void reconnectRecoversImmediatelyWithBqrSamples() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = lockedTo500(recorder);
        controller.activate(MAC, 10_000L);
        assertEquals(1000, controller.snapshot(MAC, 10_000L).ceilingKbps);

        // A couple of healthy BQR samples right after re-attach should be
        // sufficient to keep the boundary unlocked.
        controller.onBqrSample(MAC, healthyBqr(), 20_000L);
        controller.onQueueSample(MAC, 0, 45, 20_100L);
        controller.onBqrSample(MAC, healthyBqr(), 26_000L);
        LhdcLinkHealthController.Snapshot snapshot =
                controller.snapshot(MAC, 26_000L);
        assertEquals(1000, snapshot.ceilingKbps);
        assertFalse(snapshot.boundary500To900Locked);
    }

    @Test
    public void probeCooldownPreventsBackToBackProbes() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = lockedTo500(recorder);
        long firstProbeAt = openHealthyProbe(controller);
        assertEquals(900, controller.snapshot(MAC, firstProbeAt).ceilingKbps);

        // Cancel the probe right away by toggling the device to idle. The
        // cooldown should now prevent another probe from opening for at least
        // 10 seconds, even when the queue is quiet.
        controller.onBqrSample(MAC, healthyBqr(), firstProbeAt + 6_000L, false);
        assertEquals(500, controller.snapshot(MAC, firstProbeAt + 6_000L).ceilingKbps);

        // Re-establish health quickly. Without the cooldown, the next healthy
        // BQR after going streaming would immediately reopen a probe.
        controller.onBqrSample(MAC, healthyBqr(), firstProbeAt + 7_000L, true);
        controller.onQueueSample(MAC, 0, 45, firstProbeAt + 7_100L);
        assertEquals(500,
                controller.snapshot(MAC, firstProbeAt + 7_100L).ceilingKbps);

        // After the cooldown elapses, the next healthy samples + quiet queue
        // should reopen the probe.
        controller.onBqrSample(MAC, healthyBqr(), firstProbeAt + 16_000L, true);
        controller.onQueueSample(MAC, 0, 45, firstProbeAt + 16_100L);
        controller.onBqrSample(MAC, healthyBqr(), firstProbeAt + 22_000L, true);
        controller.onBqrSample(MAC, healthyBqr(), firstProbeAt + 28_000L, true);
        controller.onQueueSample(MAC, 0, 45, firstProbeAt + 31_000L);
        assertEquals(900,
                controller.snapshot(MAC, firstProbeAt + 31_000L).ceilingKbps);
    }

    @Test
    public void lockDecaysAfterTenMinutesOfHealth() {
        Recorder recorder = new Recorder();
        LhdcLinkHealthController controller = lockedTo500(recorder);

        // Escalate evidenceTier by failing a recovery probe.
        long probeAt = openHealthyProbe(controller);
        controller.onGovernorEvent(MAC, LhdcLinkHealthController.EVENT_QUICK_FAILURE,
                500, 900, probeAt + 2_000L);
        LhdcLinkHealthController.Snapshot afterFail =
                controller.snapshot(MAC, probeAt + 2_000L);
        assertEquals(4, afterFail.requiredHealthyBqrWindows);
        assertEquals(30_000L, afterFail.requiredQuietMs);

        // The decay check runs whenever a BQR arrives inside the valid
        // 3-15s interval. Land two samples straddling the 10-minute boundary
        // so the second one sees >LOCK_DECAY_MS of health.
        long anchor = probeAt + 2_000L + 10 * 60_000L;
        controller.onBqrSample(MAC, healthyBqr(), anchor);
        controller.onBqrSample(MAC, healthyBqr(), anchor + 6_000L);
        LhdcLinkHealthController.Snapshot snapshot =
                controller.snapshot(MAC, anchor + 6_000L);
        assertEquals(1000, snapshot.ceilingKbps);
        assertFalse(snapshot.boundary500To900Locked);
        assertEquals(0, snapshot.requiredHealthyBqrWindows);
        assertEquals(0L, snapshot.requiredQuietMs);
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

    private static long openHealthyProbe(LhdcLinkHealthController controller) {
        controller.onBqrSample(MAC, healthyBqr(), 10_000L);
        controller.onQueueSample(MAC, 0, 45, 10_100L);
        controller.onBqrSample(MAC, healthyBqr(), 16_000L);
        controller.onBqrSample(MAC, healthyBqr(), 22_000L);
        // Busy sample resets the quiet clock, then low samples straddle the
        // 15s quiet window so the third healthy window eventually opens the
        // probe.
        controller.onQueueSample(MAC, 30, 45, 27_500L);
        controller.onBqrSample(MAC, healthyBqr(), 28_000L);
        controller.onQueueSample(MAC, 0, 45, 70_101L);
        controller.onQueueSample(MAC, 0, 45, 90_101L);
        return 90_101L;
    }

    private static LhdcLinkHealthController.BqrSample healthyBqr() {
        return new LhdcLinkHealthController.BqrSample(
                30, 0, 90, 60, 2, -45, 10, 0, 0);
    }

    private static LhdcLinkHealthController.BqrSample unhealthyCurrentEnvironment() {
        return new LhdcLinkHealthController.BqrSample(
                59, 0, 420, 405, 10, -42, 0, 0, 0);
    }

    private static final class Recorder implements LhdcLinkHealthController.Listener {
        final List<String> events = new ArrayList<>();

        @Override
        public void onProbeCeilingChanged(String mac, int ceilingKbps, String reason) {
            events.add(ceilingKbps + ":" + reason);
        }
    }
}
