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
}
