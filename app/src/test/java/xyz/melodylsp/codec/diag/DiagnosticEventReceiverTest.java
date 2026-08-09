package xyz.melodylsp.codec.diag;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
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

    @Test
    public void recordingWindowRequiresFutureDeadline() {
        assertFalse(DiagnosticEvents.isRecordingUntil(0L, 10_000L));
        assertFalse(DiagnosticEvents.isRecordingUntil(10_000L, 10_000L));
        assertTrue(DiagnosticEvents.isRecordingUntil(10_001L, 10_000L));
    }

    @Test
    public void missingStatusDistinguishesCoreConditionalAndHealthyAbsence() {
        assertEquals("等待状态", DiagnosticEvents.defaultStatus("scope.host"));
        assertEquals("本次未触发", DiagnosticEvents.defaultStatus("inject.detail"));
        assertEquals("本次未触发", DiagnosticEvents.defaultStatus("inject.onespace"));
        assertEquals("未发现", DiagnosticEvents.defaultStatus("last.warning"));
        assertEquals("未发现", DiagnosticEvents.defaultStatus("last.error"));
    }

    @Test
    public void statusEssentialEventsAreIdentified() {
        assertTrue(DiagnosticEvents.isStatusEssentialEvent(
                "evt=lhdc.memory_patch status=patched success=true"));
        assertTrue(DiagnosticEvents.isStatusEssentialEvent(
                "evt=lhdc.memory_patch.fast_switch status=unsupported"));
        assertTrue(DiagnosticEvents.isStatusEssentialEvent(
                "evt=native.patch.state.recv status=patched fast_switch=patched"));
        assertTrue(DiagnosticEvents.isStatusEssentialEvent(
                "evt=remember.snapshot.begin reason=request count=1"));
        assertFalse(DiagnosticEvents.isStatusEssentialEvent(
                "evt=codec.bt.set codec=ldac result=0"));
    }
}
