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
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Stresses incremental NTX insert: inserts enough rows to force leaf and root
 * page splits, then proves the incrementally-maintained index is IDENTICAL to
 * a from-scratch recompute of all keys from the final DBF (same keys, recnos,
 * order) and that every inserted row is found by an index seek. Runs on copies;
 * originals untouched.
 */
public class NtxIncrementalTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        String srcFolder = args.length > 0 ? args[0] : "E:/METRO/sg20";
        int n = args.length > 1 ? Integer.parseInt(args[1]) : 60;

        Path dir = Files.createTempDirectory("ntx-incremental");
        Files.copy(new File(srcFolder, "MASTER.DBF").toPath(), dir.resolve("MASTER.DBF"));
        Files.copy(new File(srcFolder, "MASTER1.NTX").toPath(), dir.resolve("MASTER1.NTX"));
        String ntx = new File(dir.toFile(), "MASTER1.NTX").getPath();
        String dbf = new File(dir.toFile(), "MASTER.DBF").getPath();

        long before = count(ntx);
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/') + ";indexWrite=on";

        // Insert n rows whose keys cluster at the end of the keyspace, forcing
        // repeated splits of the right-most leaves (and eventually the root).
        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {
            for (int i = 0; i < n; i++) {
                String desc = String.format("ZZINC_%04d", i);
                st.executeUpdate("INSERT INTO MASTER (L_FLAG, CUST_DESC) VALUES ('X', '" + desc + "')");
            }
        }

        long after = count(ntx);
        check("index grew by " + n + " (" + before + " -> " + after + ")", after == before + n);

        // Expected index content: recompute every key from the final DBF.
        List<String> expected = recompute(dbf);
        List<String> actual = walkKeys(ntx);
        boolean identical = expected.equals(actual);
        check("incremental index == full recompute (" + actual.size() + " entries)", identical);
        if (!identical) {
            firstDiff(expected, actual);
        }

        // Every inserted row is findable via seek, and the indexed query returns it.
        int foundSeek = 0;
        try (NtxIndex idx = NtxIndex.open(ntx)) {
            for (int i = 0; i < n; i++) {
                String prefix = "XZZINC_" + String.format("%04d", i);
                if (idx.seekPrefix(prefix.getBytes(StandardCharsets.ISO_8859_1), 0).size() == 1) {
                    foundSeek++;
                }
            }
        }
        check("all " + n + " inserts found by seek (" + foundSeek + ")", foundSeek == n);

        try (Connection c = DriverManager.getConnection("jdbc:dbf:" + dir.toString().replace('\\', '/'));
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT COUNT(*) AS c FROM MASTER WHERE L_FLAG='X' AND UCASE(CUST_DESC) LIKE 'ZZINC_%'")) {
            check("indexed query counts all inserts", rs.next() && rs.getInt(1) == n);
        }

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
        keys.sort(NtxIncrementalTest::cmp);
        List<String> out = new ArrayList<>();
        for (byte[] k : keys) out.add(new String(k, StandardCharsets.ISO_8859_1));
        return out;
    }

    private static List<String> walkKeys(String ntx) throws Exception {
        List<String> out = new ArrayList<>();
        try (NtxIndex idx = NtxIndex.open(ntx)) {
            idx.forEach(e -> { out.add(e.keyString()); return true; });
        }
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

    private static void firstDiff(List<String> a, List<String> b) {
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            if (!a.get(i).equals(b.get(i))) {
                System.out.println("  first diff at " + i + "\n    expected=[" + a.get(i)
                    + "]\n    actual  =[" + b.get(i) + "]");
                return;
            }
        }
        System.out.println("  sizes differ: expected=" + a.size() + " actual=" + b.size());
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
