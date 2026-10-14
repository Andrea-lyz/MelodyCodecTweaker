package xyz.melodylsp.codec.diag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * 实时状态探测的映射契约：能力键归属、补丁状态到状态行的语义、以及事件旁路喂入。
 */
public final class StatusProbeTest {

    @Test
    public void patchStatusMapsToRowStates() {
        assertEquals("ok", StatusProbe.mapPatchStatus("patched"));
        assertEquals("ok", StatusProbe.mapPatchStatus("already_patched"));
        assertEquals("native", StatusProbe.mapPatchStatus("not_required"));
        assertEquals("pending", StatusProbe.mapPatchStatus("pending"));
        assertEquals("failed", StatusProbe.mapPatchStatus("failed"));
        assertEquals("attention", StatusProbe.mapPatchStatus("unsupported"));
        assertEquals("未识别", StatusProbe.mapPatchStatus("未识别"));
    }

    @Test
    public void capabilityKeysBelongToOneScopeAndActivityRowsStayEventDriven() {
        assertEquals(StatusProbe.SCOPE_HOST, StatusProbe.scopeForKey("hook.host"));
        assertEquals(StatusProbe.SCOPE_HOST, StatusProbe.scopeForKey("inject.onespace"));
        assertEquals(StatusProbe.SCOPE_BLUETOOTH, StatusProbe.scopeForKey("native.patch.fast_switch"));
        assertEquals(StatusProbe.SCOPE_BLUETOOTH, StatusProbe.scopeForKey("lhdc.link.bqr"));
        assertEquals(StatusProbe.SCOPE_WIRELESS_SETTINGS, StatusProbe.scopeForKey("bridge.le.ws"));
        // 活动类状态行没有实时来源，保持事件驱动
        assertNull(StatusProbe.scopeForKey("codec.write"));
        assertNull(StatusProbe.scopeForKey("remember.replay"));
        assertNull(StatusProbe.scopeForKey(null));
    }

    @Test
    public void existingEventsFeedTheLiveRegistry() {
        StatusProbe.observe("scope.host.context.ready");
        assertEquals("loaded", StatusProbe.liveState("scope.host"));

        StatusProbe.observe("lhdc.link.bqr_hooks", "count", 3);
        assertEquals("hooked", StatusProbe.liveState("lhdc.link.bqr"));
        // 计数为 0 说明 hook 没装上，实时表必须报异常而不是继续显示 ok
        StatusProbe.observe("lhdc.link.bqr_hooks", "count", 0);
        assertEquals("attention", StatusProbe.liveState("lhdc.link.bqr"));

        StatusProbe.observe("lhdc.memory_patch.fast_switch",
                "status", "not_required", "detail", "native_equality_dispatch_17");
        assertEquals("native", StatusProbe.liveState("native.patch.fast_switch"));

        StatusProbe.observe("lhdc.memory_patch", "status", "patched", "detail", "pattern=branch");
        assertEquals("ok", StatusProbe.liveState("native.patch.bitrate"));

        // 与实时表无关的事件不应污染任何键
        StatusProbe.observe("codec.bt.reply", "ok", true);
        assertNull(StatusProbe.liveState("codec.bt.reply"));
    }
}

