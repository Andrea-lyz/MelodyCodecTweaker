package xyz.melodylsp.codec.diag;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NativeLibraryCollectorTest {

    @Test
    public void elfValidationAcceptsOnly64BitAArch64() {
        byte[] junk = new byte[64];
        for (int i = 0; i < junk.length; i++) junk[i] = (byte) 'A';
        assertFalse(NativeLibraryCollector.isElf64Aarch64(junk));

        byte[] elf32 = elfHeader(1, 40); // ELFCLASS32, x86
        assertFalse(NativeLibraryCollector.isElf64Aarch64(elf32));

        byte[] elf64x86 = elfHeader(2, 62); // ELFCLASS64, x86-64
        assertFalse(NativeLibraryCollector.isElf64Aarch64(elf64x86));

        byte[] elf64arm = elfHeader(2, 183); // ELFCLASS64, AArch64
        assertTrue(NativeLibraryCollector.isElf64Aarch64(elf64arm));
    }

    @Test
    public void nullAndShortHeadersAreRejected() {
        assertFalse(NativeLibraryCollector.isElf64Aarch64(null));
        assertFalse(NativeLibraryCollector.isElf64Aarch64(new byte[4]));
    }

    private static byte[] elfHeader(int elfClass, int machine) {
        byte[] header = new byte[64];
        header[0] = 0x7f;
        header[1] = 'E';
        header[2] = 'L';
        header[3] = 'F';
        header[4] = (byte) elfClass;
        header[18] = (byte) (machine & 0xFF);
        header[19] = (byte) ((machine >> 8) & 0xFF);
        return header;
    }
}
