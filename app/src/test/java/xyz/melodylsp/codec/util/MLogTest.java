package xyz.melodylsp.codec.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MLogTest {

    @Test
    public void redactsBluetoothAddressesInsideStructuredMessages() {
        assertEquals(
                "request mac=AA:**:**:**:**:FF device=11:**:**:**:**:66",
                MLog.redactBluetoothAddresses(
                        "request mac=AA:BB:CC:DD:EE:FF device=11:22:33:44:55:66"));
    }

    @Test
    public void diagnosticRecordingGateRequiresFutureDeadline() {
        assertFalse(MLog.isRecordingActive(0L, 1_000L));
        assertFalse(MLog.isRecordingActive(1_000L, 1_000L));
        assertFalse(MLog.isRecordingActive(999L, 1_000L));
        assertTrue(MLog.isRecordingActive(1_001L, 1_000L));
    }

    @Test
    public void startupAndInjectionEventsAreStickyButTelemetryIsNot() {
        assertTrue(MLog.isStickyDiagnosticEvent("scope.host.context.ready"));
        assertTrue(MLog.isStickyDiagnosticEvent("controller.ready"));
        assertTrue(MLog.isStickyDiagnosticEvent("hires_anchored.injected"));
        assertTrue(MLog.isStickyDiagnosticEvent("codec.bt.receiver.registered"));
        assertTrue(MLog.isStickyDiagnosticEvent("lhdc.link.bqr_hooks"));
        assertTrue(MLog.isStickyDiagnosticEvent("lhdc.link.stage_d"));
        assertTrue(MLog.isStickyDiagnosticEvent("lhdc.governor.choppy_hooks"));
        assertTrue(MLog.isStickyDiagnosticEvent("lhdc.governor.queue_hooks"));
        assertFalse(MLog.isStickyDiagnosticEvent("lhdc.link.bqr_summary"));
        assertFalse(MLog.isStickyDiagnosticEvent("codec.bt.reply"));
    }

    @Test
    public void snapshotPublishesOncePerActiveRecordingDeadline() {
        assertTrue(MLog.shouldPublishDiagnosticSnapshot(20_000L, 0L, 10_000L));
        assertFalse(MLog.shouldPublishDiagnosticSnapshot(20_000L, 20_000L, 10_000L));
        assertFalse(MLog.shouldPublishDiagnosticSnapshot(10_000L, 0L, 10_000L));
        assertTrue(MLog.shouldPublishDiagnosticSnapshot(30_000L, 20_000L, 10_000L));
    }

    @Test
    public void verboseModeIsSwitchOrActiveRecording() {
        assertFalse(MLog.isVerbose(false, 0L, 1_000L));
        assertTrue(MLog.isVerbose(true, 0L, 1_000L));
        assertTrue(MLog.isVerbose(false, 2_000L, 1_000L));
        assertFalse(MLog.isVerbose(false, 1_000L, 1_000L));
    }

    @Test
    public void quietModeDropsHighFrequencyTelemetry() {
        assertEquals(MLog.SINK_NONE, MLog.eventSinks("lhdc.link.bqr", false));
        assertEquals(MLog.SINK_NONE, MLog.eventSinks("lhdc.link.bqr_ignored", false));
        assertEquals(MLog.SINK_NONE, MLog.eventSinks("lhdc.link.bqr_summary", false));
        assertEquals(MLog.SINK_NONE, MLog.eventSinks("lhdc.link.remote_choppy", false));
        assertEquals(MLog.SINK_NONE, MLog.eventSinks("codec.bt.reply", false));
        // Debug-only output wins over status-essential persistence for chatty state events.
        assertEquals(MLog.SINK_NONE, MLog.eventSinks("native.patch.state.recv", false));
        assertEquals(MLog.SINK_NONE, MLog.eventSinks("remember.snapshot.item", false));
        assertTrue(MLog.isStatusEssentialEventName("native.patch.state.recv"));
    }

    @Test
    public void quietModeKeepsStatusEventsInBothSinks() {
        assertEquals(MLog.SINK_ALL, MLog.eventSinks("lhdc.link.bqr_hooks", false));
        assertEquals(MLog.SINK_ALL, MLog.eventSinks("scope.host.start", false));
        assertEquals(MLog.SINK_ALL, MLog.eventSinks("lhdc.memory_patch", false));
        assertEquals(MLog.SINK_ALL, MLog.eventSinks("write.path", false));
        assertEquals(MLog.SINK_ALL, MLog.eventSinks("replay.outcome", false));
        assertEquals(MLog.SINK_ALL, MLog.eventSinks("diag.log.verbose", false));
        assertEquals(MLog.SINK_ALL, MLog.eventSinks("lhdc.link.queue_flush", false));
    }

    @Test
    public void quietModeSendsOtherEventsToLogcatOnly() {
        assertEquals(MLog.SINK_LOGCAT, MLog.eventSinks("module.loaded", false));
        assertEquals(MLog.SINK_LOGCAT, MLog.eventSinks("game.ll.mode", false));
    }

    @Test
    public void verboseModeRestoresFullOutput() {
        assertEquals(MLog.SINK_ALL, MLog.eventSinks("lhdc.link.bqr_ignored", true));
        assertEquals(MLog.SINK_ALL, MLog.eventSinks("module.loaded", true));
        assertEquals(MLog.SINK_ALL, MLog.plainSinks(android.util.Log.DEBUG, true));
    }

    @Test
    public void plainLogsKeepWarningsAndDropDebugWhenQuiet() {
        assertEquals(MLog.SINK_ALL, MLog.plainSinks(android.util.Log.WARN, false));
        assertEquals(MLog.SINK_ALL, MLog.plainSinks(android.util.Log.ERROR, false));
        assertEquals(MLog.SINK_LOGCAT, MLog.plainSinks(android.util.Log.INFO, false));
        assertEquals(MLog.SINK_NONE, MLog.plainSinks(android.util.Log.DEBUG, false));
    }
}
