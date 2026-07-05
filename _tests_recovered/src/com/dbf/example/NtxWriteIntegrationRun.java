package com.dbf.example;

import com.dbf.jdbc.index.ntx.NtxIndex;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * End-to-end test of NTX write maintenance. Copies MASTER.DBF + MASTER1.NTX to
 * a temp folder (originals never touched), inserts a row through JDBC with
 * indexWrite on, and verifies the .NTX gained exactly that entry, stayed a
 * valid sorted B-tree, and is found via an index seek. Also confirms that with
 * indexWrite OFF the index is left unchanged.
 */
public class NtxWriteIntegrationRun {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        String srcFolder = args.length > 0 ? args[0] : "E:/METRO/sg20";

        // ---- indexWrite ON ----
        Path dir = Files.createTempDirectory("ntx-write-on");
        copy(srcFolder, dir, "MASTER.DBF");
        copy(srcFolder, dir, "MASTER1.NTX");
        String ntx = new File(dir.toFile(), "MASTER1.NTX").getPath();

        long before = countEntries(ntx);
        boolean sortedBefore = isSorted(ntx);
        check("baseline index is sorted", sortedBefore);

        String marker = "ZZZ_INDEXTEST_ROW";
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/') + ";indexWrite=on";
        int newRecno;
        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO MASTER (L_FLAG, CUST_DESC) VALUES ('X', '" + marker + "')");
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) AS c FROM MASTER")) {
                rs.next();
                newRecno = rs.getInt(1); // appended row = last record (no deletes here)
            }
        }

        long after = countEntries(ntx);
        check("index grew by exactly 1 (" + before + " -> " + after + ")", after == before + 1);
        check("index still sorted after insert", isSorted(ntx));

        List<Long> hits = seek(ntx, "X" + marker);
        check("inserted row found via index seek (hits=" + hits.size() + ")", hits.size() == 1);

        // The query path (index read) returns the new row.
        try (Connection c = DriverManager.getConnection("jdbc:dbf:" + dir.toString().replace('\\', '/'));
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT CUST_DESC FROM MASTER WHERE L_FLAG='X' AND UCASE(CUST_DESC) LIKE '"
                 + marker + "%'")) {
            check("new row returned by indexed query",
                rs.next() && marker.equals(rs.getString(1).trim()));
        }

        // ---- indexWrite OFF (default) ----
        Path dir2 = Files.createTempDirectory("ntx-write-off");
        copy(srcFolder, dir2, "MASTER.DBF");
        copy(srcFolder, dir2, "MASTER1.NTX");
        String ntx2 = new File(dir2.toFile(), "MASTER1.NTX").getPath();
        long before2 = countEntries(ntx2);
        try (Connection c = DriverManager.getConnection(
                 "jdbc:dbf:" + dir2.toString().replace('\\', '/')); // indexWrite defaults off
             Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO MASTER (L_FLAG, CUST_DESC) VALUES ('X', '" + marker + "')");
        }
        check("indexWrite OFF leaves index unchanged",
            countEntries(ntx2) == before2);

        deleteTree(dir);
        deleteTree(dir2);

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static long countEntries(String ntxPath) throws Exception {
        long[] n = {0};
        try (NtxIndex idx = NtxIndex.open(ntxPath)) {
            idx.forEach(e -> { n[0]++; return true; });
        }
        return n[0];
    }

    private static boolean isSorted(String ntxPath) throws Exception {
        boolean[] ok = {true};
        byte[][] prev = {null};
        try (NtxIndex idx = NtxIndex.open(ntxPath)) {
            idx.forEach(e -> {
                if (prev[0] != null && compare(prev[0], e.key) > 0) {
                    ok[0] = false;
                    return false;
                }
                prev[0] = e.key;
                return true;
            });
        }
        return ok[0];
    }

    private static List<Long> seek(String ntxPath, String prefix) throws Exception {
        try (NtxIndex idx = NtxIndex.open(ntxPath)) {
            return idx.seekPrefix(prefix.getBytes(StandardCharsets.ISO_8859_1), 0);
        }
    }

    private static int compare(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = a[i] & 0xFF, y = b[i] & 0xFF;
            if (x != y) return x < y ? -1 : 1;
        }
        return Integer.compare(a.length, b.length);
    }

    private static void copy(String srcFolder, Path destDir, String name) throws Exception {
        Files.copy(new File(srcFolder, name).toPath(),
            destDir.resolve(name));
    }

    private static void deleteTree(Path dir) {
        File[] files = dir.toFile().listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }
        dir.toFile().delete();
    }

    private static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("PASS: " + name); }
        else { failed++; System.out.println("FAIL: " + name); }
    }
}
