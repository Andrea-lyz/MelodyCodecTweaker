package xyz.melodylsp.codec.system;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class NativeLhdcMemoryPatchTest {

    @Test
    public void nativePatchErrorsRemainDiagnostic() {
        assertEquals("native_patch_invalid_argument",
                NativeLhdcMemoryPatch.describeNativePatchResult(-1001));
        assertEquals("native_patch_unsupported_architecture",
                NativeLhdcMemoryPatch.describeNativePatchResult(-1002));
        assertEquals("native_patch_instruction_changed",
                NativeLhdcMemoryPatch.describeNativePatchResult(-1003));
        assertEquals("native_patch_make_writable_failed:errno=13",
                NativeLhdcMemoryPatch.describeNativePatchResult(-2013));
        assertEquals("native_patch_restore_failed_before_write:errno=1",
                NativeLhdcMemoryPatch.describeNativePatchResult(-3001));
        assertEquals("native_patch_verify_failed_rolled_back",
                NativeLhdcMemoryPatch.describeNativePatchResult(-4001));
        assertEquals("native_patch_restore_failed_rolled_back:errno=13",
                NativeLhdcMemoryPatch.describeNativePatchResult(-5013));
        assertEquals("native_patch_restore_failed_permissions_dirty:errno=13",
                NativeLhdcMemoryPatch.describeNativePatchResult(-6013));
        assertEquals("native_patch_rollback_verify_failed:errno=0",
                NativeLhdcMemoryPatch.describeNativePatchResult(-7000));
    }
}
