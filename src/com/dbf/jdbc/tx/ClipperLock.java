package com.dbf.jdbc.tx;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Exclusive write lock taken the way Clipper's DBFNTX RDD does it: an OS
 * byte-range lock on the {@code .dbf} file itself, over the Clipper FLOCK region
 * {@code [base, base+flockSize)} (see {@link LockScheme}). Because that region
 * is where Clipper places its header/record/file locks, this lock mutually
 * excludes a running Clipper/xBase application (e.g. METRO) editing the same
 * table — making concurrent writes safe across the two.
 *
 * <p>The locked region sits ~1 GB beyond any real data, so it never overlaps the
 * driver's own reads/writes at the start of the file. As with {@link TableLock},
 * a per-path {@link ReentrantLock} serializes threads inside this JVM (Java
 * would otherwise throw {@code OverlappingFileLockException} for a second
 * in-process lock on the same region), and the OS lock serializes across
 * processes.
 */
public final class ClipperLock implements WriteLock {

    private static final ConcurrentHashMap<String, ReentrantLock> JVM_LOCKS =
        new ConcurrentHashMap<>();

    private final ReentrantLock jvmLock;
    private final RandomAccessFile dbf;
    private final java.nio.channels.FileLock osLock;

    private ClipperLock(ReentrantLock jvmLock, RandomAccessFile dbf,
            java.nio.channels.FileLock osLock) {
        this.jvmLock = jvmLock;
        this.dbf = dbf;
        this.osLock = osLock;
    }

    /** Acquires the Clipper FLOCK for {@code dbfPath} under {@code scheme} (blocks until granted). */
    public static ClipperLock acquire(String dbfPath, LockScheme scheme) throws IOException {
        File file = new File(dbfPath);
        if (!file.exists()) {
            // Never create the .dbf here (that would leave a stray empty table);
            // the write itself will report the missing table.
            throw new FileNotFoundException("Cannot lock missing table file: " + dbfPath);
        }
        String key = keyFor(file);
        ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(key, k -> new ReentrantLock());
        jvmLock.lock();
        RandomAccessFile dbf = null;
        try {
            dbf = new RandomAccessFile(file, "rw");
            FileChannel channel = dbf.getChannel();
            // Exclusive lock over the Clipper FLOCK region; blocks until granted.
            java.nio.channels.FileLock osLock =
                channel.lock(scheme.base(), scheme.flockSize(), false);
            return new ClipperLock(jvmLock, dbf, osLock);
        } catch (IOException | RuntimeException e) {
            if (dbf != null) {
                try { dbf.close(); } catch (IOException ignore) { }
            }
            jvmLock.unlock();
            throw e;
        }
    }

    @Override
    public void close() {
        try {
            osLock.release();
        } catch (IOException ignore) {
            // best-effort release
        } finally {
            try { dbf.close(); } catch (IOException ignore) { }
            jvmLock.unlock();
        }
    }

    private static String keyFor(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }
}
