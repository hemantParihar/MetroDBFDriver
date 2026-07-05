package com.dbf.jdbc.tx;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Exclusive write lock for one DBF table, safe both across threads in this JVM
 * and across separate OS processes (e.g. several users running the app).
 *
 * <p>Two layers: a per-path {@link ReentrantLock} serializes threads inside this
 * JVM (an OS file lock is held per-JVM, so two threads can't both take it), and
 * a {@link java.nio.channels.FileLock} on a side-car {@code <table>.lck} file
 * serializes across processes. The lock file is used (rather than the .dbf
 * itself) so the exclusive lock never conflicts with the driver's own read/write
 * handles on the data file.
 *
 * <p>Acquire with {@link #acquire(String)} and release with {@link #close()}.
 * Acquisition blocks until the lock is granted.
 */
public final class TableLock implements WriteLock {

    private static final ConcurrentHashMap<String, ReentrantLock> JVM_LOCKS =
        new ConcurrentHashMap<>();

    private final ReentrantLock jvmLock;
    private final RandomAccessFile lockFile;
    private final java.nio.channels.FileLock osLock;

    private TableLock(ReentrantLock jvmLock, RandomAccessFile lockFile,
            java.nio.channels.FileLock osLock) {
        this.jvmLock = jvmLock;
        this.lockFile = lockFile;
        this.osLock = osLock;
    }

    /** Acquires the exclusive write lock for {@code dbfPath} (blocks until granted). */
    public static TableLock acquire(String dbfPath) throws IOException {
        String key = keyFor(dbfPath);
        ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(key, k -> new ReentrantLock());
        jvmLock.lock();
        RandomAccessFile lockFile = null;
        try {
            lockFile = new RandomAccessFile(dbfPath + ".lck", "rw");
            FileChannel channel = lockFile.getChannel();
            java.nio.channels.FileLock osLock = channel.lock(); // exclusive, cross-process
            return new TableLock(jvmLock, lockFile, osLock);
        } catch (IOException | RuntimeException e) {
            if (lockFile != null) {
                try { lockFile.close(); } catch (IOException ignore) { }
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
            // releasing best-effort
        } finally {
            try { lockFile.close(); } catch (IOException ignore) { }
            jvmLock.unlock();
        }
    }

    private static String keyFor(String dbfPath) {
        try {
            return new File(dbfPath).getCanonicalPath();
        } catch (IOException e) {
            return new File(dbfPath).getAbsolutePath();
        }
    }
}
