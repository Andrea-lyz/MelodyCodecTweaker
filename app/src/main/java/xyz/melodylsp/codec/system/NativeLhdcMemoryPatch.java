package xyz.melodylsp.codec.system;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.system.Os;
import android.system.OsConstants;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Field;
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
    };
    private static final int MAX_RANGE_BYTES = 64 * 1024 * 1024;
    private static volatile Method cachedStaticMprotect;
    private static volatile Object cachedLibcoreOs;
    private static volatile Method cachedLibcoreMprotect;
    private static volatile Method cachedPeekByteArray;
    private static volatile Method cachedPokeByteArray;
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

    static PatchResult apply() {
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
            return PatchResult.unsupported(patchedCount, originalCount);
        }

        long patchAddress = original.address + original.spec.patchDelta;
        MapRange patchRange = findRange(ranges, patchAddress);
        if (patchRange == null) {
            return PatchResult.failed("patch_address_outside_mapping");
        }

        int pageSize = pageSize();
        long pageStart = alignDown(patchAddress, pageSize);
        long pageEnd = alignUp(patchAddress + original.spec.patchBytes.length, pageSize);
        long protectLength = pageEnd - pageStart;

        boolean protectionChanged = false;
        try {
            makeWritable(pageStart, protectLength, patchRange);
            protectionChanged = true;
            writeMemory(patchAddress, original.spec.patchBytes);
        } finally {
            if (protectionChanged) {
                restoreProtection(pageStart, protectLength, patchRange);
            }
        }

        byte[] verify = readMemory(original.address, original.spec.patched.length);
        if (!equalsBytes(verify, original.spec.patched)) {
            return PatchResult.failed("verify_failed");
        }
        return PatchResult.patched(patchAddress, patchedCount, originalCount, original.spec.name);
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

    private static void writeMemory(long address, byte[] bytes) throws Exception {
        Method poke = cachedPokeByteArray;
        if (poke == null) {
            poke = Class.forName("libcore.io.Memory").getDeclaredMethod(
                    "pokeByteArray", long.class, byte[].class, int.class, int.class);
            poke.setAccessible(true);
            cachedPokeByteArray = poke;
        }
        try {
            poke.invoke(null, address, bytes, 0, bytes.length);
        } catch (Throwable t) {
            throw new Exception("pokeByteArray failed: " + describeThrowable(unwrapReflection(t)),
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

    private static void restoreProtection(long pageStart, long length, MapRange range) {
        try {
            mprotect(pageStart, length, range.protectionFlags());
        } catch (Throwable t) {
            MLog.w("lhdc memory patch restore protection failed", t);
        }
    }

    private static void makeWritable(long pageStart, long length, MapRange range) throws Exception {
        int withExec = OsConstants.PROT_READ | OsConstants.PROT_WRITE
                | (range.executable ? OsConstants.PROT_EXEC : 0);
        try {
            mprotect(pageStart, length, withExec);
        } catch (Throwable first) {
            if (!range.executable) throw first;
            MLog.w("lhdc memory patch RWX mprotect failed; retrying RW only: "
                    + first.getClass().getSimpleName() + ":" + first.getMessage());
            mprotect(pageStart, length, OsConstants.PROT_READ | OsConstants.PROT_WRITE);
        }
    }

    private static void mprotect(long address, long length, int prot) throws Exception {
        Throwable nativeError = null;
        try {
            if (ensureNativeLoaded()) {
                int rc = nativeMprotect(address, length, prot);
                if (rc == 0) return;
                nativeError = new Exception("native rc=" + rc);
            } else {
                nativeError = new Exception("native load failed: " + nativeLoadError);
            }
        } catch (Throwable t) {
            nativeError = unwrapReflection(t);
        }

        Throwable staticError = null;
        try {
            Method m = cachedStaticMprotect;
            if (m == null) {
                m = Class.forName("android.system.Os").getDeclaredMethod(
                        "mprotect", long.class, long.class, int.class);
                m.setAccessible(true);
                cachedStaticMprotect = m;
            }
            m.invoke(null, address, length, prot);
            return;
        } catch (Throwable t) {
            staticError = unwrapReflection(t);
        }

        try {
            Object os = cachedLibcoreOs;
            Method m = cachedLibcoreMprotect;
            if (os == null || m == null) {
                Class<?> libcore = Class.forName("libcore.io.Libcore");
                Field osField = libcore.getDeclaredField("os");
                osField.setAccessible(true);
                os = osField.get(null);
                m = os.getClass().getMethod("mprotect", long.class, long.class, int.class);
                m.setAccessible(true);
                cachedLibcoreOs = os;
                cachedLibcoreMprotect = m;
            }
            m.invoke(os, address, length, prot);
        } catch (Throwable t) {
            Throwable libcoreError = unwrapReflection(t);
            Exception combined = new Exception("mprotect unavailable; static="
                    + describeThrowable(staticError) + " libcore="
                    + describeThrowable(libcoreError) + " native="
                    + describeThrowable(nativeError));
            combined.addSuppressed(nativeError);
            combined.addSuppressed(staticError);
            combined.addSuppressed(libcoreError);
            throw combined;
        }
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

    private static native int nativeMprotect(long address, long length, int prot);

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

    private static int pageSize() {
        try {
            return (int) Os.sysconf(OsConstants._SC_PAGESIZE);
        } catch (Throwable ignored) {
            return 4096;
        }
    }

    private static long alignDown(long value, long alignment) {
        return value & ~(alignment - 1L);
    }

    private static long alignUp(long value, long alignment) {
        return (value + alignment - 1L) & ~(alignment - 1L);
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
