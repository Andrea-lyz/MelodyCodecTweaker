package xyz.melodylsp.codec.diag;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DiagnosticEventsTest {

    @Test
    public void notRequiredPatchStatusRendersOkNotAttention() {
        assertEquals("ok", DiagnosticEvents.stateFromPatchStatus("not_required"));
        assertEquals("ok", DiagnosticEvents.stateFromPatchStatus("patched"));
        assertEquals("ok", DiagnosticEvents.stateFromPatchStatus("already_patched"));
        assertEquals("attention", DiagnosticEvents.stateFromPatchStatus("unsupported"));
    }

    @Test
    public void notRequiredPatchMessagesRenderOkDespiteUnsupportedSubstrings() {
        // 原生支持（无需补丁）的消息里同时含 unsupported=false 与 success 关键字时，
        // 必须优先命中 ok，不能落到 attention（红叉）。
        String memoryPatch = "[mod=2.4.0 host=?] evt=lhdc.memory_patch "
                + "status=not_required detail=native_lhdc_v5_available "
                + "addr=0x0 patched=0 original=0 success=true";
        assertEquals("ok", DiagnosticEvents.stateFromMessage(memoryPatch));
        String recv = "[mod=2.4.0 host=?] evt=native.patch.state.recv "
                + "status=not_required patched=0 original=0 fast_switch=not_required "
                + "fast_switch_patched=0 fast_switch_original=0 fast_switch_spec= "
                + "bitrateKbps=0 unsupported=false";
        assertEquals("ok", DiagnosticEvents.stateFromMessage(recv));
        // 真正的未适配（unsupported）仍保持红叉语义
        String unsupportedRecv = "[mod=2.4.0 host=?] evt=native.patch.state.recv "
                + "status=unsupported patched=0 original=0 unsupported=true";
        assertEquals("attention", DiagnosticEvents.stateFromMessage(unsupportedRecv));
    }
}
