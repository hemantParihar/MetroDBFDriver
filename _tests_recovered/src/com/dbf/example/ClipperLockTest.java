package com.dbf.example;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import com.dbf.jdbc.tx.LockScheme;
import com.dbf.jdbc.tx.WriteLock;

/**
 * Verifies the Clipper (DBFNTX) byte-range write lock:
 *  - default scheme writes create NO side-car .lck file;
 *  - the lock is held over the Clipper FLOCK region [1e9, 2e9) of the .dbf
 *    (proven by an overlapping-lock attempt failing, a non-overlapping one
 *    succeeding) and is released on close.
 */
public class ClipperLockTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("dbf-clip");
        String folder = dir.toString().replace('\\', '/');
        String url = "jdbc:dbf:" + folder; // default scheme = clipper

        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE master (C_HEAD NUMERIC(6), NAME CHAR(10))");
            st.executeUpdate("INSERT INTO master (C_HEAD,NAME) VALUES (1,'A')");
            st.executeUpdate("UPDATE master SET NAME='B' WHERE C_HEAD=1");
        }
        File[] lcks = dir.toFile().listFiles((d, n) -> n.toLowerCase().endsWith(".lck"));
        check("clipper scheme creates no .lck side-car", lcks == null || lcks.length == 0);

        String dbfPath = folder + "/master.dbf";
        long base = LockScheme.CLIPPER.base();          // 1,000,000,000
        long size = LockScheme.CLIPPER.flockSize();      // 1,000,000,000

        WriteLock lock = LockScheme.CLIPPER.acquire(dbfPath);
        try (RandomAccessFile raf = new RandomAccessFile(dbfPath, "rw")) {
            FileChannel ch = raf.getChannel();

            // Overlapping region is already locked by this JVM -> Java reports it.
            boolean overlapDetected = false;
            try {
                FileLock l = ch.tryLock(base, size, false);
                if (l == null) { overlapDetected = true; } // OS reported contention
                else { l.release(); }
            } catch (OverlappingFileLockException e) {
                overlapDetected = true;
            }
            check("Clipper FLOCK region [1e9,2e9) is locked while held", overlapDetected);

            // The real data lives near offset 0 and must NOT be locked.
            FileLock dataLock = ch.tryLock(0L, 4096L, false);
            check("data region (offset 0) is NOT locked", dataLock != null);
            if (dataLock != null) dataLock.release();
        } finally {
            lock.close();
        }

        // After release the region is free again.
        try (RandomAccessFile raf = new RandomAccessFile(dbfPath, "rw")) {
            FileLock l = raf.getChannel().tryLock(base, size, false);
            check("region is free after lock.close()", l != null);
            if (l != null) l.release();
        }

        check("CLIPPER base/size match Harbour DB_DBFLOCK_CLIPPER",
            base == 1_000_000_000L && size == 1_000_000_000L);
        check("CLIPPER2 base/size match Harbour DB_DBFLOCK_CLIPPER2",
            LockScheme.CLIPPER2.base() == 4_000_000_000L
            && LockScheme.CLIPPER2.flockSize() == 294_967_295L);

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("PASS: " + name); }
        else { failed++; System.out.println("FAIL: " + name); }
    }
}
