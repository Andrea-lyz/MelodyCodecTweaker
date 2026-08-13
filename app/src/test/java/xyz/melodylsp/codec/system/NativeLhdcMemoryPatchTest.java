package xyz.melodylsp.codec.system;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    @Test
    public void qualitySwitchPatternMatchesPjz110501UniquelyWhenLibraryAvailable()
            throws Exception {
        File root = findWorkspaceRoot();
        if (root == null) return;
        File lib = new File(root, "PJZ110_16.0.10.501/libbluetooth_jni.so");
        if (!lib.isFile()) return;
        byte[] image = Files.readAllBytes(lib.toPath());
        NativeLhdcMemoryPatch.CodeBlockSpec spec =
                NativeLhdcMemoryPatch.qualitySwitchSpecForTestName(
                        "lhdcv5_quality_equals_pjz110_1610501");
        assertEquals(1, countMatches(image, spec.original));
        assertEquals(0, countMatches(image, spec.patched));
    }

    @Test
    public void allQualitySwitchSpecsAreBoundedConsistentAndDistinct() {
        NativeLhdcMemoryPatch.CodeBlockSpec[] specs =
                NativeLhdcMemoryPatch.qualitySwitchSpecsForTest();
        assertEquals(6, specs.length);
        java.util.Set<String> names = new java.util.HashSet<>();
        for (int i = 0; i < specs.length; i++) {
            NativeLhdcMemoryPatch.CodeBlockSpec spec = specs[i];
            assertEquals(108, spec.original.length);
            assertEquals(spec.original.length, spec.patched.length);
            assertEquals(0x14000024, spec.safeGateInstruction);
            assertTrue("safe gate must branch forward", spec.safeGateInstruction != 0);
            assertTrue("names must be unique", names.add(spec.name));
            assertEquals("replacement tail must branch from +0x68 to equal +0x98",
                    0x30, branchOffset(readIntLe(spec.patched, 0x68)));
            assertEquals("first reject branch at +0x10 must target +0x90",
                    0x80, conditionalBranchOffset(readIntLe(spec.patched, 0x10)));
            for (int j = i + 1; j < specs.length; j++) {
                assertFalse("orig blocks must be pairwise distinct",
                        java.util.Arrays.equals(spec.original, specs[j].original));
            }
        }
    }

    @Test
    public void groupBSpecsUseX28AndStackOffset60() {
        NativeLhdcMemoryPatch.CodeBlockSpec plc =
                NativeLhdcMemoryPatch.qualitySwitchSpecForTestName(
                        "lhdcv5_quality_equals_plc110_1608300");
        NativeLhdcMemoryPatch.CodeBlockSpec rmx =
                NativeLhdcMemoryPatch.qualitySwitchSpecForTestName(
                        "lhdcv5_quality_equals_rmx6688");
        NativeLhdcMemoryPatch.CodeBlockSpec ref =
                NativeLhdcMemoryPatch.qualitySwitchSpecForTest();
        // Group B: CIE pointer register x28 (ldur w11,[x28,#9] at +0x50)
        assertEquals(0xb840938b, readIntLe(plc.patched, 0x50));
        assertEquals(0xb840938b, readIntLe(rmx.patched, 0x50));
        // Group B: stack loads shifted -0x10 vs Group A (-0x70..-0x67 -> -0x60..-0x57)
        assertEquals(0x385a03aa, readIntLe(plc.patched, 0x14));
        assertEquals(0x385a03aa, readIntLe(rmx.patched, 0x14));
        // Group A keeps x21 at +0x50; Group B differs
        assertEquals(0xb84092ab, readIntLe(ref.patched, 0x50));
        assertFalse(java.util.Arrays.equals(plc.patched, ref.patched));
    }

    @Test
    public void everyBuildSpecMatchesItsLibraryUniquelyWhenAvailable()
            throws Exception {
        File root = findWorkspaceRoot();
        if (root == null) return;
        String[][] samples = {
                {"lhdcv5_quality_equals_op15", "OnePlus 15/libbluetooth_jni_op15.so"},
                {"lhdcv5_quality_equals_pjz110_1608301",
                        "PJZ110_16.0.8.301/libbluetooth_jni.so"},
                {"lhdcv5_quality_equals_pjz110_1610501",
                        "PJZ110_16.0.10.501/libbluetooth_jni.so"},
                {"lhdcv5_quality_equals_plc110_1608300",
                        "PLC110_16.0.8.300(CN01B90P01)/libbluetooth_jni.so"},
                {"lhdcv5_quality_equals_rmx6688",
                        "realme RMX6688/libbluetooth_jni.so"},
        };
        for (String[] sample : samples) {
            File lib = new File(root, sample[1]);
            if (!lib.isFile()) continue;
            byte[] image = Files.readAllBytes(lib.toPath());
            NativeLhdcMemoryPatch.CodeBlockSpec spec =
                    NativeLhdcMemoryPatch.qualitySwitchSpecForTestName(sample[0]);
            assertEquals(sample[0] + " must match exactly once",
                    1, countMatches(image, spec.original));
            assertEquals(sample[0] + " must not be pre-patched",
                    0, countMatches(image, spec.patched));
        }
    }

    @Test
    public void semanticQualitySwitchScanFindsKnownBlocksUniquely() throws Exception {
        File root = findWorkspaceRoot();
        if (root == null) return;
        String[][] samples = {
                {"lhdcv5_quality_equals_op15",
                        "OnePlus 15/libbluetooth_jni_op15.so", "A"},
                {"lhdcv5_quality_equals_op15",
                        "OnePlus Ace 6T C16.0.7.201/libbluetooth_jni.so", "A"},
                {"lhdcv5_quality_equals_pjz110_1608301",
                        "PJZ110_16.0.8.301/libbluetooth_jni.so", "A"},
                {"lhdcv5_quality_equals_pjz110_1608301",
                        "PLK110_16.0.8.301(CN01)/libbluetooth_jni.so", "A"},
                {"lhdcv5_quality_equals_pjz110_1609401_1609402",
                        "PJZ110_16.0.9.401/libbluetooth_jni.so", "A"},
                {"lhdcv5_quality_equals_pjz110_1610501",
                        "PJZ110_16.0.10.501/libbluetooth_jni.so", "A"},
                {"lhdcv5_quality_equals_plc110_1608300",
                        "PLC110_16.0.8.300(CN01B90P01)/libbluetooth_jni.so", "B"},
                {"lhdcv5_quality_equals_rmx6688",
                        "realme RMX6688/libbluetooth_jni.so", "B"},
        };
        for (String[] sample : samples) {
            File lib = new File(root, sample[1]);
            if (!lib.isFile()) continue;
            byte[] image = Files.readAllBytes(lib.toPath());
            java.util.List<NativeLhdcMemoryPatch.QualitySwitchMatch> hits =
                    NativeLhdcMemoryPatch.scanQualitySwitchImage(image);
            assertEquals(sample[0] + " must match semantically exactly once in " + sample[1],
                    1, hits.size());
            assertEquals(sample[0] + " group in " + sample[1],
                    "B".equals(sample[2]), hits.get(0).groupB);
            NativeLhdcMemoryPatch.CodeBlockSpec spec =
                    NativeLhdcMemoryPatch.qualitySwitchSpecForTestName(sample[0]);
            // Byte equality holds for these evidence builds only; a future OTA would match
            // semantically with different bytes and is covered by the uniqueness assertion.
            byte[] found = java.util.Arrays.copyOfRange(
                    image, hits.get(0).offset, hits.get(0).offset + spec.original.length);
            assertTrue(sample[0] + " found bytes must equal spec original in " + sample[1],
                    java.util.Arrays.equals(spec.original, found));
        }
    }

    @Test
    public void semanticQualitySwitchScanRejectsMutatedShapes() throws Exception {
        File root = findWorkspaceRoot();
        if (root == null) return;
        File lib = new File(root, "PJZ110_16.0.10.501/libbluetooth_jni.so");
        if (!lib.isFile()) return;
        byte[] image = Files.readAllBytes(lib.toPath());
        java.util.List<NativeLhdcMemoryPatch.QualitySwitchMatch> hits =
                NativeLhdcMemoryPatch.scanQualitySwitchImage(image);
        assertEquals(1, hits.size());
        int offset = hits.get(0).offset;
        // Patched-style tail delta (+0x28 -> +0x30) must no longer match the original shape.
        byte[] tailMutated = image.clone();
        writeIntLe(tailMutated, offset + 0x68, 0x0c000014);
        assertEquals(0, NativeLhdcMemoryPatch.scanQualitySwitchImage(tailMutated).size());
        // Wrong entry constant must be rejected.
        byte[] entryMutated = image.clone();
        writeIntLe(entryMutated, offset, 0x52800028); // mov w8, #1
        assertEquals(0, NativeLhdcMemoryPatch.scanQualitySwitchImage(entryMutated).size());
    }

    private static void writeIntLe(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }

    private static NativeLhdcMemoryPatch.PatternSpec findSpec(String name) {
        for (NativeLhdcMemoryPatch.PatternSpec spec : NativeLhdcMemoryPatch.patternsForTest()) {
            if (spec.name.equals(name)) return spec;
        }
        throw new AssertionError("missing pattern spec " + name);
    }

    private static File findWorkspaceRoot() {
        // Local runs through the ASCII junction (E:\melody-lsp-link) cannot walk up to the
        // real workspace root; point the tests at the research folder explicitly instead.
        String override = System.getenv("MELODY_NATIVE_RESEARCH_DIR");
        if (override != null && !override.isEmpty()) {
            File candidate = new File(override);
            if (candidate.isDirectory()) return candidate;
        }
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

    private static int conditionalBranchOffset(int instruction) {
        int imm19 = (instruction >> 5) & 0x7ffff;
        if ((imm19 & 0x40000) != 0) imm19 |= ~0x7ffff;
        return imm19 * 4;
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
        assertEquals(400, NativeLhdcMemoryPatch.normalizeProbeCeilingKbps(400));
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

    @Test
    public void preNativePatchBuildLineIsDetectedFromDisplay() {
        // 非金标（< 16.0.7.x）：LHDC V5 原生可用，短路补丁流程
        assertTrue(NativeLhdcMemoryPatch.isPreNativePatchBuild("PJD110_16.0.3.500(CN01)"));
        assertTrue(NativeLhdcMemoryPatch.isPreNativePatchBuild("OP5929L1_16.0.3.500(CN01)"));
        assertTrue(NativeLhdcMemoryPatch.isPreNativePatchBuild("PJD110_16.0.6.999(CN01)"));
        assertTrue(NativeLhdcMemoryPatch.isPreNativePatchBuild("PJD110_15.0.1.100(CN01)"));
        // 金标线起（>= 16.0.7.0）：继续 pattern 扫描（已适配与未适配的 OTA 都要真实上报）
        assertFalse(NativeLhdcMemoryPatch.isPreNativePatchBuild("OP15_16.0.7.201(CN01)"));
        assertFalse(NativeLhdcMemoryPatch.isPreNativePatchBuild("RMX6688_16.0.7.500(CN01)"));
        assertFalse(NativeLhdcMemoryPatch.isPreNativePatchBuild("PJZ110_16.0.8.301(CN01)"));
        assertFalse(NativeLhdcMemoryPatch.isPreNativePatchBuild("PJZ110_16.0.9.401(CN01)"));
        assertFalse(NativeLhdcMemoryPatch.isPreNativePatchBuild("PJZ110_16.0.10.501(CN01)"));
        // 无法解析版本线时不拦截：交给 pattern 扫描决定（不猜测）
        assertFalse(NativeLhdcMemoryPatch.isPreNativePatchBuild("BP2A.250605.015"));
        assertFalse(NativeLhdcMemoryPatch.isPreNativePatchBuild(""));
        assertFalse(NativeLhdcMemoryPatch.isPreNativePatchBuild(null));
    }

    @Test
    public void preNativePatchVersionLineParsesOnlyOplusShapedSegments() {
        int[] v = NativeLhdcMemoryPatch.parseColorOsVersionLine("PJD110_16.0.3.500(CN01)");
        assertEquals(16, v[0]);
        assertEquals(0, v[1]);
        assertEquals(3, v[2]);
        assertEquals(500, v[3]);
        assertEquals(null, NativeLhdcMemoryPatch.parseColorOsVersionLine("BP2A.250605.015"));
        assertEquals(null, NativeLhdcMemoryPatch.parseColorOsVersionLine(null));
    }
}
