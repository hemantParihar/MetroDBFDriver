package com.dbf.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verifies multi-writer safety: many threads, each with its OWN connection
 * (simulating separate users), insert into the same table at once. With file
 * locking the writes serialize, so every row survives and the file stays valid.
 */
public class ConcurrencyTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("dbf-concurrency-test");
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');

        try (Connection setup = DriverManager.getConnection(url);
             Statement st = setup.createStatement()) {
            st.executeUpdate("CREATE TABLE c (TID NUMERIC(4), SEQ NUMERIC(6))");
        }

        int threads = 6;
        int perThread = 50;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            new Thread(() -> {
                try (Connection c = DriverManager.getConnection(url);
                     Statement s = c.createStatement()) {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        s.executeUpdate("INSERT INTO c (TID, SEQ) VALUES (" + tid + ", " + i + ")");
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    System.out.println("thread " + tid + " error: " + e.getMessage());
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown();
        done.await();

        check("no thread errored", errors.get() == 0);

        try (Connection c = DriverManager.getConnection(url);
             Statement s = c.createStatement()) {
            long total = count(s, "SELECT COUNT(*) AS c FROM c");
            check("all rows survived: " + total + " == " + (threads * perThread),
                total == (long) threads * perThread);
            // Each thread's rows are all present (no lost updates).
            boolean allPresent = true;
            for (int t = 0; t < threads; t++) {
                if (count(s, "SELECT COUNT(*) AS c FROM c WHERE TID=" + t) != perThread) {
                    allPresent = false;
                    break;
                }
            }
            check("every thread's " + perThread + " rows present", allPresent);
            // File still structurally valid: full scan completes with the right count.
            long scanned = 0;
            try (ResultSet rs = s.executeQuery("SELECT TID FROM c")) {
                while (rs.next()) scanned++;
            }
            check("full scan intact (" + scanned + ")", scanned == (long) threads * perThread);
        }

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static long count(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) { rs.next(); return rs.getLong(1); }
    }

    private static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("PASS: " + name); }
        else { failed++; System.out.println("FAIL: " + name); }
    }
}
