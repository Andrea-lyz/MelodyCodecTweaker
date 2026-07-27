package xyz.melodylsp.codec.system;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.system.OsConstants;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import xyz.melodylsp.codec.util.MLog;
import xyz.melodylsp.codec.BuildConfig;

/**
 * Experimental in-process replacement for the KSU / Magisk LHDC V5 native overlay.
 *
 * <p>ColorOS 16 changed {@code libbluetooth_jni.so} to ignore fixed LHDC V5 target bitrate
 * requests. The KSU module patches the on-disk library through a systemless mount, which works
 * but leaves a visible mount drift. This helper tries the same 4-byte patch against the already
 * mapped library inside {@code com.android.bluetooth}: scan the mapped bytes, temporarily make the
 * target page writable, write the branch instruction, verify, then restore page protection.</p>
 */
final class NativeLhdcMemoryPatch {

    private static final String LIB_NAME = "libbluetooth_jni.so";
    private static final PatternSpec[] PATTERN_SPECS = {
            new PatternSpec(
                    "branch_plus_69",
                    hex("1f0900f1a2080054e83d80529b008052"),
                    hex("1f0900f145000014e83d80529b008052"),
                    4,
                    hex("45000014")),
            new PatternSpec(
                    "branch_plus_23_op15",
                    hex("1f0900f1e2020054283d805299008052"),
                    hex("1f0900f117000014283d805299008052"),
                    4,
                    hex("17000014")),
            new PatternSpec(
                    "branch_plus_73_plc110",
                    hex("1f0900f122090054680f80529a008052"),
                    hex("1f0900f149000014680f80529a008052"),
                    4,
                    hex("49000014")),
            new PatternSpec(
                    "branch_plus_68_pjz110_1609401",
                    hex("1f0900f182080054a83e80529a008052"),
                    hex("1f0900f144000014a83e80529a008052"),
                    4,
                    hex("44000014")),
    };
    private static final int MAX_RANGE_BYTES = 64 * 1024 * 1024;
    private static final int NATIVE_PATCH_OK = 0;
    private static final int NATIVE_PATCH_ALREADY_APPLIED = 1;
    private static volatile Method cachedPeekByteArray;
    private static volatile String nativeLibraryPath;
    private static volatile String nativeLoadError;
    private static volatile boolean nativeLoadAttempted;
    private static volatile boolean nativeLoaded;
    private static volatile PatchResult lastResult;

    private NativeLhdcMemoryPatch() {
    }

    static void configureModuleContext(Context hostContext) {
        if (hostContext == null || nativeLibraryPath != null) return;
        try {
            Context moduleContext = hostContext.createPackageContext(
                    BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY);
            ApplicationInfo info = moduleContext.getApplicationInfo();
            if (info != null && info.nativeLibraryDir != null) {
                nativeLibraryPath = info.nativeLibraryDir + "/libmelody_lhdc_patch.so";
                nativeLoadAttempted = false;
                nativeLoadError = null;
            }
        } catch (Throwable t) {
            nativeLoadError = "context:" + describeThrowable(t);
        }
    }

    static synchronized PatchResult apply() {
        PatchResult result;
        try {
            result = applyUnchecked();
        } catch (Throwable t) {
            result = PatchResult.failed("exception:" + t.getClass().getSimpleName()
                    + ":" + t.getMessage());
        }
        lastResult = result;
        return result;
    }

    static PatchResult lastResult() {
        return lastResult;
    }

    private static PatchResult applyUnchecked() throws Exception {
        List<MapRange> ranges = readLibraryMaps();
        if (ranges.isEmpty()) {
            return PatchResult.pending("library_not_mapped");
        }

        Match original = null;
        int originalCount = 0;
        int patchedCount = 0;
        String patchedSpec = "";

        for (MapRange range : ranges) {
            byte[] bytes = readRange(range);
            if (bytes == null) continue;
            for (PatternSpec spec : PATTERN_SPECS) {
                int rangeOriginalCount = countMatches(bytes, spec.original);
                int rangePatchedCount = countMatches(bytes, spec.patched);
                originalCount += rangeOriginalCount;
                patchedCount += rangePatchedCount;
                if (rangePatchedCount > 0 && patchedSpec.isEmpty()) {
                    patchedSpec = spec.name;
                }
                if (original == null) {
                    int index = indexOf(bytes, spec.original);
                    if (index >= 0) {
                        original = new Match(range.start + index, range, spec);
                    }
                }
            }
        }

        if (patchedCount == 1 && originalCount == 0) {
            return PatchResult.alreadyPatched(patchedCount, originalCount, patchedSpec);
        }
        if (originalCount != 1 || original == null) {
            SemanticScan semantic = scanSemanticGuard(ranges);
            if (semantic.patchedCount == 1 && semantic.originalCount == 0) {
                return PatchResult.alreadyPatched(
                        semantic.patchedCount,
                        semantic.originalCount,
                        "semantic_guard_v1");
            }
            if (semantic.originalCount != 1 || semantic.original == null
                    || semantic.patchedCount != 0) {
                return PatchResult.unsupported(
                        patchedCount + semantic.patchedCount,
                        originalCount + semantic.originalCount);
            }
            original = semantic.original;
            originalCount = semantic.originalCount;
            patchedCount = semantic.patchedCount;
        }

        long patchAddress = original.address + original.spec.patchDelta;
        MapRange patchRange = findRange(ranges, patchAddress);
        if (patchRange == null) {
            return PatchResult.failed("patch_address_outside_mapping");
        }
        if (!patchRange.executable) {
            return PatchResult.failed("patch_mapping_not_executable");
        }
        if ((patchAddress & 3L) != 0L
                || original.spec.patchBytes.length != Integer.BYTES
                || original.spec.patchDelta < 0
                || original.spec.patchDelta + Integer.BYTES > original.spec.original.length) {
            return PatchResult.failed("patch_instruction_not_aligned_arm64");
        }
        if (!ensureNativeLoaded()) {
            return PatchResult.failed("native_helper_unavailable:" + nativeLoadError);
        }

        int expectedInstruction = readIntLe(original.spec.original, original.spec.patchDelta);
        int replacementInstruction = readIntLe(original.spec.patchBytes, 0);
        int nativeResult;
        try {
            nativeResult = nativePatchInstruction(
                    patchAddress,
                    expectedInstruction,
                    replacementInstruction,
                    patchRange.protectionFlags());
        } catch (Throwable t) {
            return PatchResult.failed("native_patch_call_failed:" + describeThrowable(t));
        }
        if (nativeResult != NATIVE_PATCH_OK
                && nativeResult != NATIVE_PATCH_ALREADY_APPLIED) {
            return PatchResult.failed(describeNativePatchResult(nativeResult));
        }

        byte[] verify = readMemory(original.address, original.spec.patched.length);
        if (!equalsBytes(verify, original.spec.patched)) {
            return PatchResult.failed("verify_failed");
        }
        if (nativeResult == NATIVE_PATCH_ALREADY_APPLIED) {
            return PatchResult.alreadyPatched(
                    Math.max(1, patchedCount), 0, original.spec.name);
        }
        return PatchResult.patched(patchAddress, patchedCount, originalCount, original.spec.name);
    }

    /**
     * Finds the LHDC fixed-bitrate guard by ARM64 instruction semantics instead of compiler-
     * generated bytes. OPlus rebuilds this function on nearly every OTA, which changes register
     * allocation, source-line constants and branch distances even when the logic is unchanged.
     *
     * <p>The stable control-flow shape is: CBNZ and B.NE share a forward target, the latter is
     * guarded by {@code cmp wN, #0x13}, followed by {@code sub xN, xM, #7},
     * {@code cmp xN, #2}, and {@code b.hs same_target}. The forced path then selects quality mode
     * 4. We only accept a unique match across executable mappings.</p>
     */
    private static SemanticScan scanSemanticGuard(List<MapRange> ranges) {
        SemanticScan out = new SemanticScan();
        for (MapRange range : ranges) {
            if (!range.executable) continue;
            byte[] bytes = readRange(range);
            if (bytes == null) continue;
            for (int offset = 8; offset <= bytes.length - 20; offset += 4) {
                int branch = readIntLe(bytes, offset);
                boolean originalBranch = isConditionalBranch(branch, 2);
                boolean patchedBranch = isUnconditionalBranch(branch);
                if (!originalBranch && !patchedBranch) continue;

                int cmp = readIntLe(bytes, offset - 4);
                int sub = readIntLe(bytes, offset - 8);
                if (!isCmpXImmediate(cmp, 2) || !isSubXImmediate(sub, 7)) continue;
                if (registerN(cmp) != registerD(sub)) continue;

                long address = range.start + offset;
                long target = branchTarget(address, branch, originalBranch);
                if (target <= address || target - address > 0x400L) continue;
                if (!hasCmp19AndBranchTo(bytes, range.start, offset, target)) continue;
                if (!hasCbnzTo(bytes, range.start, offset, target)) continue;
                if (!hasMovWImmediate(bytes, offset + 4, 4, 4)) continue;

                if (originalBranch) {
                    int replacement = encodeUnconditionalBranch(address, target);
                    if (replacement == 0) continue;
                    PatternSpec spec = new PatternSpec(
                            "semantic_guard_v1",
                            intLe(branch),
                            intLe(replacement),
                            0,
                            intLe(replacement));
                    out.originalCount++;
                    if (out.original == null) {
                        out.original = new Match(address, range, spec);
                    }
                } else {
                    out.patchedCount++;
                }
            }
        }
        return out;
    }

    private static boolean hasCmp19AndBranchTo(
            byte[] bytes,
            long rangeStart,
            int branchOffset,
            long target) {
        int first = Math.max(4, branchOffset - 10 * 4);
        for (int offset = branchOffset - 4; offset >= first; offset -= 4) {
            int branch = readIntLe(bytes, offset);
            if (!isConditionalBranch(branch, 1)) continue;
            if (branchTarget(rangeStart + offset, branch, true) != target) continue;
            if (isCmpWImmediate(readIntLe(bytes, offset - 4), 0x13)) return true;
        }
        return false;
    }

    private static boolean hasCbnzTo(
            byte[] bytes,
            long rangeStart,
            int branchOffset,
            long target) {
        int first = Math.max(0, branchOffset - 14 * 4);
        for (int offset = branchOffset - 4; offset >= first; offset -= 4) {
            int instruction = readIntLe(bytes, offset);
            if (!isCbnz(instruction)) continue;
            if (branchTarget19(rangeStart + offset, instruction) == target) return true;
        }
        return false;
    }

    private static boolean hasMovWImmediate(
            byte[] bytes,
            int start,
            int instructionCount,
            int immediate) {
        int end = Math.min(bytes.length - 4, start + instructionCount * 4);
        for (int offset = start; offset <= end; offset += 4) {
            int instruction = readIntLe(bytes, offset);
            if ((instruction & 0xffe00000) == 0x52800000
                    && ((instruction >>> 5) & 0xffff) == immediate) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCmpXImmediate(int instruction, int immediate) {
        return (instruction & 0xffc0001f) == 0xf100001f
                && ((instruction >>> 10) & 0xfff) == immediate;
    }

    private static boolean isCmpWImmediate(int instruction, int immediate) {
        return (instruction & 0xffc0001f) == 0x7100001f
                && ((instruction >>> 10) & 0xfff) == immediate;
    }

    private static boolean isSubXImmediate(int instruction, int immediate) {
        return (instruction & 0xffc00000) == 0xd1000000
                && ((instruction >>> 10) & 0xfff) == immediate;
    }

    private static boolean isConditionalBranch(int instruction, int condition) {
        return (instruction & 0xff000010) == 0x54000000
                && (instruction & 0xf) == condition;
    }

    private static boolean isUnconditionalBranch(int instruction) {
        return (instruction & 0xfc000000) == 0x14000000;
    }

    private static boolean isCbnz(int instruction) {
        return (instruction & 0x7f000000) == 0x35000000;
    }

    private static int registerN(int instruction) {
        return (instruction >>> 5) & 0x1f;
    }

    private static int registerD(int instruction) {
        return instruction & 0x1f;
    }

    private static long branchTarget(long address, int instruction, boolean conditional) {
        if (conditional) return branchTarget19(address, instruction);
        int immediate = instruction & 0x03ffffff;
        if ((immediate & 0x02000000) != 0) immediate |= 0xfc000000;
        return address + ((long) immediate * 4L);
    }

    private static long branchTarget19(long address, int instruction) {
        int immediate = (instruction >>> 5) & 0x7ffff;
        if ((immediate & 0x40000) != 0) immediate |= 0xfff80000;
        return address + ((long) immediate * 4L);
    }

    private static int encodeUnconditionalBranch(long address, long target) {
        long delta = target - address;
        if ((delta & 3L) != 0) return 0;
        long immediate = delta / 4L;
        if (immediate < -(1L << 25) || immediate >= (1L << 25)) return 0;
        return 0x14000000 | ((int) immediate & 0x03ffffff);
    }

    private static int readIntLe(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | ((bytes[offset + 3] & 0xff) << 24);
    }

    private static byte[] intLe(int value) {
        return new byte[]{
                (byte) value,
                (byte) (value >>> 8),
                (byte) (value >>> 16),
                (byte) (value >>> 24)};
    }

    private static List<MapRange> readLibraryMaps() throws IOException {
        List<MapRange> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = br.readLine()) != null) {
                MapRange range = MapRange.parse(line);
                if (range == null) continue;
                if (!range.readable) continue;
                if (range.size() <= 0 || range.size() > MAX_RANGE_BYTES) continue;
                if (range.path == null || !range.path.endsWith(LIB_NAME)) continue;
                out.add(range);
            }
        }
        return out;
    }

    private static byte[] readRange(MapRange range) {
        try {
            return readMemory(range.start, (int) range.size());
        } catch (Throwable t) {
            MLog.w("lhdc memory patch read range failed: " + range.describe() + " "
                    + t.getClass().getSimpleName() + ":" + t.getMessage());
            return null;
        }
    }

    private static byte[] readMemory(long address, int length) throws Exception {
        byte[] out = new byte[length];
        Method peek = cachedPeekByteArray;
        if (peek == null) {
            peek = Class.forName("libcore.io.Memory").getDeclaredMethod(
                    "peekByteArray", long.class, byte[].class, int.class, int.class);
            peek.setAccessible(true);
            cachedPeekByteArray = peek;
        }
        try {
            peek.invoke(null, address, out, 0, length);
            return out;
        } catch (Throwable t) {
            throw new Exception("peekByteArray failed: " + describeThrowable(unwrapReflection(t)),
                    unwrapReflection(t));
        }
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

    private static int indexOf(byte[] haystack, byte[] needle) {
        return indexOf(haystack, needle, 0);
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        if (needle.length == 0) return from;
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

    private static boolean equalsBytes(byte[] actual, byte[] expected) {
        if (actual == null || actual.length != expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if (actual[i] != expected[i]) return false;
        }
        return true;
    }

    private static MapRange findRange(List<MapRange> ranges, long address) {
        for (MapRange range : ranges) {
            if (address >= range.start && address < range.end) return range;
        }
        return null;
    }

    private static synchronized boolean ensureNativeLoaded() {
        if (nativeLoaded) return true;
        if (nativeLoadAttempted) return false;
        nativeLoadAttempted = true;
        String path = nativeLibraryPath;
        Throwable pathError = null;
        try {
            if (path != null && !path.isEmpty()) {
                System.load(path);
                nativeLoaded = true;
                nativeLoadError = null;
                MLog.event("lhdc.memory_patch.native_loaded", "path", path);
                return true;
            }
        } catch (Throwable t) {
            pathError = t;
        }

        try {
            System.loadLibrary("melody_lhdc_patch");
            nativeLoaded = true;
            nativeLoadError = null;
            MLog.event("lhdc.memory_patch.native_loaded",
                    "path", path != null && !path.isEmpty() ? path + "|loadLibrary" : "loadLibrary");
            return true;
        } catch (Throwable t) {
            nativeLoaded = false;
            nativeLoadError = pathError == null
                    ? describeThrowable(t)
                    : "path=" + describeThrowable(pathError) + " loadLibrary=" + describeThrowable(t);
            return false;
        }
    }

    private static native int nativePatchInstruction(
            long address,
            int expectedInstruction,
            int replacementInstruction,
            int originalProtection);

    static String describeNativePatchResult(int result) {
        if (result == NATIVE_PATCH_OK) return "native_patch_ok";
        if (result == NATIVE_PATCH_ALREADY_APPLIED) return "native_patch_already_applied";
        if (result == -1001) return "native_patch_invalid_argument";
        if (result == -1002) return "native_patch_unsupported_architecture";
        if (result == -1003) return "native_patch_instruction_changed";
        if (result <= -2001 && result >= -2999) {
            return "native_patch_make_writable_failed:errno=" + (-2000 - result);
        }
        if (result <= -3001 && result >= -3999) {
            return "native_patch_restore_failed_before_write:errno=" + (-3000 - result);
        }
        if (result == -4001) return "native_patch_verify_failed_rolled_back";
        if (result <= -5001 && result >= -5999) {
            return "native_patch_restore_failed_rolled_back:errno=" + (-5000 - result);
        }
        if (result <= -6001 && result >= -6999) {
            return "native_patch_restore_failed_permissions_dirty:errno=" + (-6000 - result);
        }
        if (result <= -7000 && result >= -7999) {
            return "native_patch_rollback_verify_failed:errno=" + Math.max(0, -7000 - result);
        }
        return "native_patch_unknown_result:" + result;
    }

    private static Throwable unwrapReflection(Throwable t) {
        if (t instanceof InvocationTargetException
                && ((InvocationTargetException) t).getTargetException() != null) {
            return ((InvocationTargetException) t).getTargetException();
        }
        return t;
    }

    private static String describeThrowable(Throwable t) {
        if (t == null) return "none";
        return t.getClass().getSimpleName() + ":" + t.getMessage();
    }

    private static byte[] hex(String value) {
        int len = value.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) Integer.parseInt(value.substring(i, i + 2), 16);
        }
        return out;
    }

    static final class PatchResult {
        final String status;
        final String reason;
        final long address;
        final int patchedCount;
        final int originalCount;
        final boolean terminal;
        final boolean success;

        private PatchResult(
                String status,
                String reason,
                long address,
                int patchedCount,
                int originalCount,
                boolean terminal,
                boolean success) {
            this.status = status;
            this.reason = reason;
            this.address = address;
            this.patchedCount = patchedCount;
            this.originalCount = originalCount;
            this.terminal = terminal;
            this.success = success;
        }

        static PatchResult patched(
                long address,
                int patchedCount,
                int originalCount,
                String pattern) {
            return new PatchResult("patched", patternReason(pattern), address,
                    patchedCount, originalCount, true, true);
        }

        static PatchResult alreadyPatched(
                int patchedCount,
                int originalCount,
                String pattern) {
            return new PatchResult("already_patched", patternReason(pattern), 0L,
                    patchedCount, originalCount, true, true);
        }

        static PatchResult unsupported(int patchedCount, int originalCount) {
            return new PatchResult("unsupported", "", 0L, patchedCount, originalCount,
                    true, false);
        }

        static PatchResult pending(String reason) {
            return new PatchResult("pending", reason, 0L, 0, 0,
                    false, false);
        }

        static PatchResult failed(String reason) {
            return new PatchResult("failed", reason, 0L, 0, 0,
                    true, false);
        }

        String addressHex() {
            return address == 0L ? "0x0" : "0x" + Long.toHexString(address);
        }

        private static String patternReason(String pattern) {
            return pattern == null || pattern.isEmpty() ? "" : "pattern=" + pattern;
        }
    }

    private static final class Match {
        final long address;
        final MapRange range;
        final PatternSpec spec;

        Match(long address, MapRange range, PatternSpec spec) {
            this.address = address;
            this.range = range;
            this.spec = spec;
        }
    }

    private static final class SemanticScan {
        Match original;
        int originalCount;
        int patchedCount;
    }

    private static final class PatternSpec {
        final String name;
        final byte[] original;
        final byte[] patched;
        final int patchDelta;
        final byte[] patchBytes;

        PatternSpec(
                String name,
                byte[] original,
                byte[] patched,
                int patchDelta,
                byte[] patchBytes) {
            this.name = name;
            this.original = original;
            this.patched = patched;
            this.patchDelta = patchDelta;
            this.patchBytes = patchBytes;
        }
    }

    private static final class MapRange {
        final long start;
        final long end;
        final boolean readable;
        final boolean writable;
        final boolean executable;
        final String perms;
        final String path;

        private MapRange(
                long start,
                long end,
                boolean readable,
                boolean writable,
                boolean executable,
                String perms,
                String path) {
            this.start = start;
            this.end = end;
            this.readable = readable;
            this.writable = writable;
            this.executable = executable;
            this.perms = perms;
            this.path = path;
        }

        static MapRange parse(String line) {
            if (line == null) return null;
            String[] parts = line.trim().split("\\s+", 6);
            if (parts.length < 5) return null;
            String[] bounds = parts[0].split("-", 2);
            if (bounds.length != 2) return null;
            try {
                long start = Long.parseUnsignedLong(bounds[0], 16);
                long end = Long.parseUnsignedLong(bounds[1], 16);
                String perms = parts[1];
                String path = parts.length >= 6 ? parts[5] : "";
                return new MapRange(start, end,
                        perms.length() > 0 && perms.charAt(0) == 'r',
                        perms.length() > 1 && perms.charAt(1) == 'w',
                        perms.length() > 2 && perms.charAt(2) == 'x',
                        perms,
                        path);
            } catch (Throwable ignored) {
                return null;
            }
        }

        long size() {
            return end - start;
        }

        int protectionFlags() {
            int flags = 0;
            if (readable) flags |= OsConstants.PROT_READ;
            if (writable) flags |= OsConstants.PROT_WRITE;
            if (executable) flags |= OsConstants.PROT_EXEC;
            return flags;
        }

        String describe() {
            return String.format(Locale.ROOT, "0x%x-0x%x/%s/%s", start, end, perms, path);
        }
    }
}
