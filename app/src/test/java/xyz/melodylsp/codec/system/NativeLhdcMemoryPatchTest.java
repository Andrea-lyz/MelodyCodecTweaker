package xyz.melodylsp.codec.system;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;

import org.junit.Test;

public final class NativeLhdcMemoryPatchTest {

    @Test
    public void debugBuildIdentifiesFixed1000AbEvidenceMode() {
        assertEquals("bqr_fallback_ab", NativeLhdcMemoryPatch.governorModeForDiagnostics());
    }

    @Test
    public void observeProbeResultDescriptionsAreStable() {
        assertEquals("unique_owner", NativeLhdcMemoryPatch.describeObserveProbeResult(1));
        assertEquals("zero_owners", NativeLhdcMemoryPatch.describeObserveProbeResult(-5));
        assertEquals("ambiguous_multiple_owners",
                NativeLhdcMemoryPatch.describeObserveProbeResult(-6));
        assertEquals("encoder_not_loaded",
                NativeLhdcMemoryPatch.describeObserveProbeResult(-3));
    }

    @Test
    public void patternSpecsAreSelfConsistent() {
        for (NativeLhdcMemoryPatch.PatternSpec spec : NativeLhdcMemoryPatch.patternsForTest()) {
            assertEquals(spec.name, 16, spec.original.length);
            assertEquals(spec.name, 16, spec.patched.length);
            assertEquals(spec.name, 4, spec.patchDelta);
            assertEquals(spec.name, 4, spec.patchBytes.length);
            for (int i = 0; i < 16; i++) {
                if (i >= spec.patchDelta && i < spec.patchDelta + 4) {
                    assertEquals(spec.name, spec.patchBytes[i - spec.patchDelta], spec.patched[i]);
                } else {
                    assertEquals(spec.name, spec.original[i], spec.patched[i]);
                }
            }
        }
    }

    @Test
    public void patternSpecsHaveUniqueNamesAndDisjointSignatures() {
        NativeLhdcMemoryPatch.PatternSpec[] specs = NativeLhdcMemoryPatch.patternsForTest();
        for (int i = 0; i < specs.length; i++) {
            for (int j = i + 1; j < specs.length; j++) {
                assertTrue(specs[i].name + " duplicates " + specs[j].name,
                        !specs[i].name.equals(specs[j].name));
                assertTrue(specs[i].name + " overlaps " + specs[j].name,
                        !java.util.Arrays.equals(specs[i].original, specs[j].original));
            }
        }
    }

    /**
     * When the real-device libraries are checked in under native_research/, each known pattern
     * must remain unambiguous inside its own library (at most one original or patched occurrence).
     */
    @Test
    public void knownPatternsStayUniqueInProvidedLibraries() throws Exception {
        File root = findWorkspaceRoot();
        if (root == null) return; // not running from a checkout that contains native_research
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) return;
        for (NativeLhdcMemoryPatch.PatternSpec spec : NativeLhdcMemoryPatch.patternsForTest()) {
            for (File dir : dirs) {
                File lib = new File(dir, "libbluetooth_jni.so");
                if (!lib.isFile()) continue;
                byte[] image = Files.readAllBytes(lib.toPath());
                int original = countMatches(image, spec.original);
                int patched = countMatches(image, spec.patched);
                String where = dir.getName() + "/" + spec.name;
                assertTrue(where + " matched ambiguously: original=" + original
                        + " patched=" + patched, original <= 1 && patched <= 1);
                assertTrue(where + " matched both original and patched",
                        !(original > 0 && patched > 0));
            }
        }
    }

    @Test
    public void rmx6688PatternIsPresentAndUniqueWhenLibraryAvailable() throws Exception {
        File root = findWorkspaceRoot();
        if (root == null) return;
        File lib = new File(root, "realme RMX6688/libbluetooth_jni.so");
        if (!lib.isFile()) return;
        byte[] image = Files.readAllBytes(lib.toPath());
        NativeLhdcMemoryPatch.PatternSpec spec = findSpec("branch_plus_27_rmx6688");
        assertEquals(1, countMatches(image, spec.original));
        assertEquals(0, countMatches(image, spec.patched));
    }

    @Test
    public void qualitySwitchBlockIsBoundedAndTargetsOriginalRestartPath() {
        NativeLhdcMemoryPatch.CodeBlockSpec spec =
                NativeLhdcMemoryPatch.qualitySwitchSpecForTest();
        assertEquals(108, spec.original.length);
        assertEquals(spec.original.length, spec.patched.length);
        assertEquals(0x14000024, spec.safeGateInstruction);
        assertEquals("safe gate must branch from block +0x00 to restart +0x90",
                0x90, branchOffset(spec.safeGateInstruction));
        assertEquals("replacement tail must branch from +0x68 to equal +0x98",
                0x30, branchOffset(readIntLe(spec.patched, 0x68)));
        assertTrue("original and replacement blocks must differ",
                !java.util.Arrays.equals(spec.original, spec.patched));
    }

    @Test
    public void qualitySwitchPatternMatchesPjz110401UniquelyWhenLibraryAvailable()
            throws Exception {
        File root = findWorkspaceRoot();
        if (root == null) return;
        File lib = new File(root, "PJZ110_16.0.9.401/libbluetooth_jni.so");
        if (!lib.isFile()) return;
        byte[] image = Files.readAllBytes(lib.toPath());
        NativeLhdcMemoryPatch.CodeBlockSpec spec =
                NativeLhdcMemoryPatch.qualitySwitchSpecForTest();
        assertEquals(1, countMatches(image, spec.original));
        assertEquals(0, countMatches(image, spec.patched));
    }

    private static NativeLhdcMemoryPatch.PatternSpec findSpec(String name) {
        for (NativeLhdcMemoryPatch.PatternSpec spec : NativeLhdcMemoryPatch.patternsForTest()) {
            if (spec.name.equals(name)) return spec;
        }
        throw new AssertionError("missing pattern spec " + name);
    }

    private static File findWorkspaceRoot() {
        File dir = new File(System.getProperty("user.dir", "."));
        for (int i = 0; i < 6 && dir != null; i++) {
            File candidate = new File(dir, "native_research");
            if (candidate.isDirectory()) return candidate;
            dir = dir.getParentFile();
        }
        return null;
    }

    private static int countMatches(byte[] haystack, byte[] needle) {
        int count = 0;
        int from = 0;
        while (from <= haystack.length - needle.length) {
            int index = indexOf(haystack, needle, from);
            if (index < 0) break;
            count++;
            from = index + needle.length;
        }
        return count;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        for (int i = Math.max(0, from); i <= haystack.length - needle.length; i++) {
            boolean ok = true;
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    ok = false;
                    break;
                }
            }
            if (ok) return i;
        }
        return -1;
    }

    private static int readIntLe(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | ((bytes[offset + 3] & 0xff) << 24);
    }

    private static int branchOffset(int instruction) {
        int immediate = instruction & 0x03ffffff;
        if ((immediate & 0x02000000) != 0) immediate |= 0xfc000000;
        return immediate * 4;
    }

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

    @Test
    public void probeCeilingNormalizationIsFixed() {
        assertEquals(1000, NativeLhdcMemoryPatch.normalizeProbeCeilingKbps(0));
        assertEquals(1000, NativeLhdcMemoryPatch.normalizeProbeCeilingKbps(-1));
        assertEquals(500, NativeLhdcMemoryPatch.normalizeProbeCeilingKbps(400));
        assertEquals(500, NativeLhdcMemoryPatch.normalizeProbeCeilingKbps(500));
        assertEquals(900, NativeLhdcMemoryPatch.normalizeProbeCeilingKbps(600));
        assertEquals(900, NativeLhdcMemoryPatch.normalizeProbeCeilingKbps(900));
        assertEquals(1000, NativeLhdcMemoryPatch.normalizeProbeCeilingKbps(901));
        assertEquals(1000, NativeLhdcMemoryPatch.normalizeProbeCeilingKbps(1200));
    }

    @Test
    public void desiredProbeCeilingCacheSurvivesInstallPendingAndSwitchesBack() {
        try {
            assertEquals(1000, NativeLhdcMemoryPatch.currentDesiredGovernorProbeCeilingKbps());

            int previous = NativeLhdcMemoryPatch.cacheDesiredGovernorProbeCeilingKbps(900);
            assertEquals(1000, previous);
            assertEquals(900, NativeLhdcMemoryPatch.currentDesiredGovernorProbeCeilingKbps());

            // Re-setting the same ceiling is idempotent.
            NativeLhdcMemoryPatch.cacheDesiredGovernorProbeCeilingKbps(900);
            assertEquals(900, NativeLhdcMemoryPatch.currentDesiredGovernorProbeCeilingKbps());

            // A confirmed-1000 device replays 1000 instead of lingering at 900.
            NativeLhdcMemoryPatch.cacheDesiredGovernorProbeCeilingKbps(1000);
            assertEquals(1000, NativeLhdcMemoryPatch.currentDesiredGovernorProbeCeilingKbps());

            // Out-of-ladder values normalize to the nearest supported rung.
            NativeLhdcMemoryPatch.cacheDesiredGovernorProbeCeilingKbps(650);
            assertEquals(900, NativeLhdcMemoryPatch.currentDesiredGovernorProbeCeilingKbps());
        } finally {
            NativeLhdcMemoryPatch.cacheDesiredGovernorProbeCeilingKbps(1000);
        }
    }

    @Test
    public void nativeVerificationStatesRemainDiagnostic() {
        assertEquals("none", NativeLhdcMemoryPatch.verificationTextForNativeState(0));
        assertEquals("pending", NativeLhdcMemoryPatch.verificationTextForNativeState(1));
        assertEquals("getter_confirmed",
                NativeLhdcMemoryPatch.verificationTextForNativeState(2));
        assertEquals("getter_unavailable",
                NativeLhdcMemoryPatch.verificationTextForNativeState(3));
        assertEquals("getter_read_failed",
                NativeLhdcMemoryPatch.verificationTextForNativeState(4));
        assertEquals("getter_mismatch",
                NativeLhdcMemoryPatch.verificationTextForNativeState(5));
        assertEquals("setter_only",
                NativeLhdcMemoryPatch.verificationTextForNativeState(6));
        assertEquals("unknown", NativeLhdcMemoryPatch.verificationTextForNativeState(99));
    }
}
