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

    @Test
    public void statusDecouplingCoversScopeHookAndResultEvents() {
        assertTrue(DiagnosticEvents.isStatusEssentialEvent("evt=scope.host.start"));
        assertTrue(DiagnosticEvents.isStatusEssentialEvent(
                "evt=scope.system.context.ready"));
        assertTrue(DiagnosticEvents.isStatusEssentialEvent(
                "evt=scope.wirelesssettings.start"));
        assertTrue(DiagnosticEvents.isStatusEssentialEvent("evt=controller.ready"));
        assertTrue(DiagnosticEvents.isStatusEssentialEvent(
                "evt=preference.fragment.hooked class=androidx.preference.g"));
        assertTrue(DiagnosticEvents.isStatusEssentialEvent(
                "evt=detailmain.activity.hooked"));
        assertTrue(DiagnosticEvents.isStatusEssentialEvent("evt=onespace.injected"));
        assertTrue(DiagnosticEvents.isStatusEssentialEvent(
                "evt=codec.bt.receiver.registered"));
        assertTrue(DiagnosticEvents.isStatusEssentialEvent("evt=codec.updated.hooks"));
        assertTrue(DiagnosticEvents.isStatusEssentialEvent(
                "evt=lhdc.governor.queue_hooks count=2"));
        assertTrue(DiagnosticEvents.isStatusEssentialEvent(
                "evt=remember.write mac=AA:BB:CC:DD:EE:01"));
        assertTrue(DiagnosticEvents.isStatusEssentialEvent(
                "evt=replay.dispatch attempt=0"));
        assertTrue(DiagnosticEvents.isStatusEssentialEvent(
                "evt=write.done success=true"));
        assertTrue(DiagnosticEvents.isStatusEssentialEvent(
                "evt=dexkit.native.loaded"));
        // 高频活性事件仍受录制门控
        assertFalse(DiagnosticEvents.isStatusEssentialEvent(
                "evt=codec.bt.reply ok=true"));
        assertFalse(DiagnosticEvents.isStatusEssentialEvent(
                "evt=le.bt.a2dp.reconnect attempt=3"));
        assertFalse(DiagnosticEvents.isStatusEssentialEvent(
                "evt=lhdc.link.bqr_summary retx=8"));
        assertFalse(DiagnosticEvents.isStatusEssentialEvent(
                "evt=lhdc.link.remote_choppy level=1"));
        assertFalse(DiagnosticEvents.isStatusEssentialEvent(
                "evt=lhdc.link.probe_ceiling ceiling=900"));
    }
}
