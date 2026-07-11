package xyz.melodylsp.codec.diag;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public final class DiagnosticEventReceiverTest {

    @After
    public void reset() {
        DiagnosticEventReceiver.resetRateLimitsForTest();
    }

    @Test
    public void rateLimitCapsEachUidAndResetsAfterWindow() {
        int uid = 10_123;
        for (int i = 0; i < 180; i++) {
            assertTrue(DiagnosticEventReceiver.allowEvent(uid, 1_000L + i));
        }
        assertFalse(DiagnosticEventReceiver.allowEvent(uid, 2_000L));
        assertTrue(DiagnosticEventReceiver.allowEvent(uid, 61_000L));
    }

    @Test
    public void rateLimitIsIndependentPerUid() {
        for (int i = 0; i < 180; i++) {
            assertTrue(DiagnosticEventReceiver.allowEvent(10_001, 1_000L));
        }
        assertFalse(DiagnosticEventReceiver.allowEvent(10_001, 1_000L));
        assertTrue(DiagnosticEventReceiver.allowEvent(10_002, 1_000L));
    }
}
