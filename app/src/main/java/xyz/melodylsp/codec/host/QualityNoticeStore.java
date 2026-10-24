package xyz.melodylsp.codec.host;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Versioned, per-headset notice receipts shared by Melody's host processes. */
final class QualityNoticeStore {
    private final File directory;
    private final Set<String> shown = new HashSet<>();

    QualityNoticeStore(File directory) {
        this.directory = directory;
    }

    /** A live reservation prevents two host surfaces/processes from showing the same notice. */
    synchronized Reservation reserve(String mac) throws IOException {
        if (mac == null) return null;
        String key = mac.trim().toUpperCase(Locale.ROOT);
        if (!key.matches("[0-9A-F]{2}(:[0-9A-F]{2}){5}")) return null;
        key = key.replace(":", "");
        if (shown.contains(key)) return null;
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("notice_directory_unavailable");
        }
        RandomAccessFile file = new RandomAccessFile(new File(directory, "v1-" + key), "rw");
        FileLock lock = null;
        boolean retained = false;
        try {
            try {
                lock = file.getChannel().tryLock();
            } catch (OverlappingFileLockException ignored) {
                return null;
            }
            if (lock == null) return null;
            if (file.length() > 0 && file.read() == 1) {
                shown.add(key);
                return null;
            }
            Reservation reservation = new Reservation(key, file, lock);
            retained = true;
            return reservation;
        } finally {
            if (!retained) {
                try {
                    if (lock != null) lock.release();
                } finally {
                    file.close();
                }
            }
        }
    }

    final class Reservation implements AutoCloseable {
        private final String key;
        private final RandomAccessFile file;
        private final FileLock lock;
        private boolean closed;

        private Reservation(String key, RandomAccessFile file, FileLock lock) {
            this.key = key;
            this.file = file;
            this.lock = lock;
        }

        /** Called only after the actual dialog has passed its first layout validation. */
        synchronized void markShown() throws IOException {
            if (closed) return;
            try {
                file.seek(0);
                file.write(1);
                file.getChannel().force(true);
                synchronized (QualityNoticeStore.this) {
                    shown.add(key);
                }
            } finally {
                close();
            }
        }

        /** No receipt is written when showing failed or the window closed before drawing. */
        @Override
        public synchronized void close() throws IOException {
            if (closed) return;
            closed = true;
            try {
                lock.release();
            } finally {
                file.close();
            }
        }
    }
}
