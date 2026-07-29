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
}
