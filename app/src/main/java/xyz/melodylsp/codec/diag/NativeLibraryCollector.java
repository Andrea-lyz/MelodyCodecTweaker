package xyz.melodylsp.codec.diag;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Copies the two ColorOS-fixed vendor LHDC libraries into the feedback ZIP.
 *
 * <p>Only ColorOS is targeted and both libraries live at fixed read-only system paths, so the
 * collector is intentionally a plain root copy plus a cheap local sanity check (non-empty,
 * 64-bit AArch64 ELF, SHA-256). No /proc mapping discovery, no inode comparison, no build-id
 * parsing.</p>
 */
public final class NativeLibraryCollector {

    public static final String LIB_BLUETOOTH_JNI = "libbluetooth_jni.so";
    public static final String LIB_LHDC_ENC = "liblhdcv5BT_enc.so";

    private static final String PATH_BLUETOOTH_JNI = "/system/lib64/" + LIB_BLUETOOTH_JNI;
    private static final String PATH_LHDC_ENC = "/system/lib64/" + LIB_LHDC_ENC;
    private static final long MAX_SINGLE_BYTES = 64L * 1024L * 1024L;
    private static final long ROOT_TIMEOUT_MS = 15_000L;
    private static final int ELF_MAGIC = 0x464C457F; // 0x7f 'E' 'L' 'F'
    private static final int ELFCLASS64 = 2;
    private static final int EM_AARCH64 = 183;

    private NativeLibraryCollector() {
    }

    public static final class CollectedLibrary {
        public final String basename;
        public final String sourcePath;
        public final File tempFile;
        public final long size;
        public final String sha256;

        CollectedLibrary(String basename, String sourcePath, File tempFile,
                         long size, String sha256) {
            this.basename = basename;
            this.sourcePath = sourcePath;
            this.tempFile = tempFile;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    public static final class CollectionResult {
        public final Map<String, CollectedLibrary> libraries = new LinkedHashMap<>();
        public final List<String> errors = new ArrayList<>();

        public boolean succeeded() {
            return errors.isEmpty()
                    && libraries.containsKey(LIB_BLUETOOTH_JNI)
                    && libraries.containsKey(LIB_LHDC_ENC);
        }
    }

    /** Copies both fixed libraries to the app cache and sanity-checks them. */
    public static CollectionResult collect(Context context) {
        CollectionResult result = new CollectionResult();
        File cacheDir = context == null ? null : context.getCacheDir();
        if (cacheDir == null) {
            result.errors.add("no cache dir");
            return result;
        }
        collectOne(LIB_BLUETOOTH_JNI, PATH_BLUETOOTH_JNI, cacheDir, result);
        collectOne(LIB_LHDC_ENC, PATH_LHDC_ENC, cacheDir, result);
        if (!result.errors.isEmpty()) {
            for (CollectedLibrary lib : result.libraries.values()) {
                deleteQuietly(lib.tempFile);
            }
            result.libraries.clear();
        }
        return result;
    }

    private static void collectOne(
            String basename,
            String sourcePath,
            File cacheDir,
            CollectionResult result) {
        File temp = new File(cacheDir, "native-" + basename + ".tmp");
        try {
            if (!FeedbackCollector.runRootBinary(
                    "cat '" + sourcePath + "'", temp, ROOT_TIMEOUT_MS)) {
                result.errors.add(basename + " 复制失败：" + sourcePath);
                return;
            }
            byte[] bytes = readAll(temp);
            if (bytes.length == 0) {
                result.errors.add(basename + " 为空");
                return;
            }
            if (bytes.length > MAX_SINGLE_BYTES) {
                result.errors.add(basename + " 超过大小上限");
                return;
            }
            byte[] header = bytes.length >= 64
                    ? java.util.Arrays.copyOf(bytes, 64) : bytes;
            if (!isElf64Aarch64(header)) {
                result.errors.add(basename + " 不是 64 位 AArch64 ELF");
                return;
            }
            result.libraries.put(basename,
                    new CollectedLibrary(basename, sourcePath, temp,
                            bytes.length, sha256(bytes)));
        } catch (Throwable t) {
            result.errors.add(basename + " 读取失败：" + t);
        } finally {
            if (!result.libraries.containsKey(basename)) deleteQuietly(temp);
        }
    }

    static boolean isElf64Aarch64(byte[] header) {
        if (header == null || header.length < 20) return false;
        int magic = (header[0] & 0xFF)
                | ((header[1] & 0xFF) << 8)
                | ((header[2] & 0xFF) << 16)
                | ((header[3] & 0xFF) << 24);
        if (magic != ELF_MAGIC) return false;
        if ((header[4] & 0xFF) != ELFCLASS64) return false;
        int machine = (header[18] & 0xFF) | ((header[19] & 0xFF) << 8);
        return machine == EM_AARCH64;
    }

    private static byte[] readAll(File file) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
            }
            return hex.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static void deleteQuietly(File file) {
        if (file == null) return;
        try {
            if (file.exists() && !file.delete()) file.deleteOnExit();
        } catch (Throwable ignored) {
        }
    }
}
