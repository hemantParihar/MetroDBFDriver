package com.dbf.example;

import com.dbf.jdbc.dbf.DBFField;
import com.dbf.jdbc.dbf.DBFReader;
import com.dbf.jdbc.index.ntx.NtxIndex;
import com.dbf.jdbc.index.ntx.NtxKeyEvaluator;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

/**
 * Verifies that a batched PreparedStatement INSERT maintains the index ONCE for
 * the whole batch: after executeBatch the .NTX has grown by exactly the batch
 * size, equals a full recompute, and every row is found by seek. On copies.
 */
public class NtxBatchTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        String srcFolder = args.length > 0 ? args[0] : "E:/METRO/sg20";
        int n = args.length > 1 ? Integer.parseInt(args[1]) : 100;

        Path dir = Files.createTempDirectory("ntx-batch");
        Files.copy(new File(srcFolder, "MASTER.DBF").toPath(), dir.resolve("MASTER.DBF"));
        Files.copy(new File(srcFolder, "MASTER1.NTX").toPath(), dir.resolve("MASTER1.NTX"));
        String ntx = new File(dir.toFile(), "MASTER1.NTX").getPath();
        String dbf = new File(dir.toFile(), "MASTER.DBF").getPath();
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/') + ";indexWrite=on";

        long before = count(ntx);

        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO MASTER (L_FLAG, CUST_DESC) VALUES (?, ?)")) {
            for (int i = 0; i < n; i++) {
                ps.setString(1, "X");
                ps.setString(2, String.format("ZZBATCH_%04d", i));
                ps.addBatch();
            }
            int[] r = ps.executeBatch();
            check("executeBatch reported " + n + " rows", r.length == n);
        }

        check("index grew by " + n + " (" + before + " -> " + count(ntx) + ")",
            count(ntx) == before + n);
        check("batch index == full recompute", recompute(dbf).equals(walkKeys(ntx)));

        int found = 0;
        try (NtxIndex idx = NtxIndex.open(ntx)) {
            for (int i = 0; i < n; i++) {
                if (idx.seekPrefix(("XZZBATCH_" + String.format("%04d", i))
                        .getBytes(StandardCharsets.ISO_8859_1), 0).size() == 1) {
                    found++;
                }
            }
        }
        check("all " + n + " batch rows found by seek (" + found + ")", found == n);

        deleteTree(dir);
        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static List<String> recompute(String dbf) throws Exception {
        List<byte[]> keys = new ArrayList<>();
        try (DBFReader reader = new DBFReader(dbf, StandardCharsets.ISO_8859_1)) {
            NtxKeyEvaluator eval = new NtxKeyEvaluator(reader.getHeader().getFields());
            String keyExpr = "L_FLAG+upper(cust_desc)+STR(RECN(),9)";
            reader.beforeFirst();
            while (reader.next()) {
                long recno = reader.getCurrentRecord() + 1;
                byte[] k = eval.evaluate(keyExpr, name -> rawOrTyped(reader, name), recno);
                if (k != null) keys.add(k);
            }
        }
        keys.sort(NtxBatchTest::cmp);
        List<String> out = new ArrayList<>();
        for (byte[] k : keys) out.add(new String(k, StandardCharsets.ISO_8859_1));
        return out;
    }

    private static List<String> walkKeys(String ntx) throws Exception {
        List<String> out = new ArrayList<>();
        try (NtxIndex idx = NtxIndex.open(ntx)) { idx.forEach(e -> { out.add(e.keyString()); return true; }); }
        return out;
    }

    private static Object rawOrTyped(DBFReader reader, String name) {
        try {
            DBFField f = reader.getHeader().getField(name);
            if (f != null && Character.toUpperCase(f.getType()) == 'C') return reader.getRawString(name);
            return reader.getValue(name);
        } catch (Exception e) { return null; }
    }

    private static int cmp(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = a[i] & 0xFF, y = b[i] & 0xFF;
            if (x != y) return x < y ? -1 : 1;
        }
        return Integer.compare(a.length, b.length);
    }

    private static long count(String ntx) throws Exception {
        long[] n = {0};
        try (NtxIndex idx = NtxIndex.open(ntx)) { idx.forEach(e -> { n[0]++; return true; }); }
        return n[0];
    }

    private static void deleteTree(Path dir) {
        File[] fs = dir.toFile().listFiles();
        if (fs != null) for (File f : fs) f.delete();
        dir.toFile().delete();
    }

    private static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("PASS: " + name); }
        else { failed++; System.out.println("FAIL: " + name); }
    }
}
