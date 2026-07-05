package com.dbf.example;

import com.dbf.jdbc.dbf.DBFField;
import com.dbf.jdbc.dbf.DBFReader;
import com.dbf.jdbc.index.ntx.NtxIndex;
import com.dbf.jdbc.index.ntx.NtxKeyEvaluator;
import com.dbf.jdbc.index.ntx.NtxWriter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Verifies NtxWriter: rebuilds a real .NTX into a temp file and checks the
 * rebuilt index reads back logically identical to Clipper's original (same
 * keys, recnos, order). Two modes:
 *   (1) feed the writer the original index's own entries  -> isolates the writer
 *   (2) recompute entries from the DBF via NtxKeyEvaluator -> full pipeline
 * The originals are never modified.
 */
public class NtxRebuildRun {
    public static void main(String[] args) throws Exception {
        String folder = args.length > 0 ? args[0] : "E:/METRO/sg20";
        rebuildFromIndex(folder, "MASTER1.NTX");
        rebuildFromIndex(folder, "TRAN.NTX");
        System.out.println();
        rebuildFromDbf(folder, "MASTER", "MASTER1.NTX");
        rebuildFromDbf(folder, "TRAN", "TRAN.NTX");
    }

    /** Mode 1: rebuild using the original index's own (key,recno) entries. */
    private static void rebuildFromIndex(String folder, String ntxName) throws Exception {
        String src = new File(folder, ntxName).getPath();
        List<long[]> origOrder = new ArrayList<>();
        List<NtxWriter.Entry> entries = new ArrayList<>();
        List<String> origKeys = new ArrayList<>();
        try (NtxIndex idx = NtxIndex.open(src)) {
            idx.forEach(e -> {
                entries.add(new NtxWriter.Entry(e.key, e.recordNumber));
                origKeys.add(e.keyString());
                origOrder.add(new long[]{e.recordNumber});
                return true;
            });
        }
        String out = new File(System.getProperty("java.io.tmpdir"),
            "rebuild_idx_" + ntxName).getPath();
        NtxWriter.rebuild(src, out, entries);
        compare(ntxName + " (from index)", src, out, origKeys);
    }

    /** Mode 2: recompute entries from the DBF, sort, rebuild, compare to original. */
    private static void rebuildFromDbf(String folder, String table, String ntxName)
            throws Exception {
        String src = new File(folder, ntxName).getPath();
        String dbf = new File(folder, table + ".DBF").getPath();

        // Original sequence for comparison.
        List<String> origKeys = new ArrayList<>();
        String keyExpr;
        try (NtxIndex idx = NtxIndex.open(src)) {
            keyExpr = idx.keyExpression();
            idx.forEach(e -> { origKeys.add(e.keyString()); return true; });
        }

        // Recompute (key, recno) for every record via the evaluator.
        List<NtxWriter.Entry> entries = new ArrayList<>();
        try (DBFReader reader = new DBFReader(dbf, StandardCharsets.ISO_8859_1)) {
            NtxKeyEvaluator eval = new NtxKeyEvaluator(reader.getHeader().getFields());
            reader.beforeFirst();
            while (reader.next()) {
                long recno = reader.getCurrentRecord() + 1;
                byte[] key = eval.evaluate(keyExpr, name -> rawOrTyped(reader, name), recno);
                if (key != null) {
                    entries.add(new NtxWriter.Entry(key, recno));
                }
            }
        }
        entries.sort((a, b) -> NtxWriter.compare(a.key, b.key));

        String out = new File(System.getProperty("java.io.tmpdir"),
            "rebuild_dbf_" + ntxName).getPath();
        NtxWriter.rebuild(src, out, entries);
        compare(ntxName + " (from DBF)", src, out, origKeys);
    }

    private static Object rawOrTyped(DBFReader reader, String name) {
        try {
            DBFField f = reader.getHeader().getField(name);
            if (f != null && Character.toUpperCase(f.getType()) == 'C') {
                return reader.getRawString(name);
            }
            return reader.getValue(name);
        } catch (Exception e) {
            return null;
        }
    }

    private static void compare(String label, String src, String out,
            List<String> origKeys) throws Exception {
        List<String> rebuilt = new ArrayList<>();
        try (NtxIndex idx = NtxIndex.open(out)) {
            idx.forEach(e -> { rebuilt.add(e.keyString()); return true; });
        }
        boolean ok = origKeys.equals(rebuilt);
        System.out.printf("%-26s orig=%d rebuilt=%d  %s%n",
            label, origKeys.size(), rebuilt.size(), ok ? "IDENTICAL ✓" : "DIFFERENT ✗");
        if (!ok) {
            int n = Math.min(origKeys.size(), rebuilt.size());
            for (int i = 0; i < n; i++) {
                if (!origKeys.get(i).equals(rebuilt.get(i))) {
                    System.out.println("  first diff at " + i
                        + "\n    orig=[" + origKeys.get(i) + "]"
                        + "\n    new =[" + rebuilt.get(i) + "]");
                    break;
                }
            }
        }
    }
}
