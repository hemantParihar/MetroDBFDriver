package com.dbf.example;

import com.dbf.jdbc.dbf.DBFReader;
import com.dbf.jdbc.index.ntx.NtxIndex;
import com.dbf.jdbc.index.ntx.NtxKeyEvaluator;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * Validates NtxKeyEvaluator against real Clipper indexes: for each stored
 * (key, recno) it re-evaluates the key expression from record `recno` and
 * checks the bytes match exactly. An index that matches 100% is safe to write;
 * one that does not must never be maintained by the driver.
 */
public class NtxKeyValidateRun {
    public static void main(String[] args) throws Exception {
        String folder = args.length > 0 ? args[0] : "E:/METRO/sg20";
        String table = args.length > 1 ? args[1] : "MASTER";
        int sampleCap = args.length > 2 ? Integer.parseInt(args[2]) : 3000;

        String dbfPath = new File(folder, table + ".DBF").getPath();
        try (DBFReader reader = new DBFReader(dbfPath, StandardCharsets.ISO_8859_1)) {
            NtxKeyEvaluator evaluator = new NtxKeyEvaluator(reader.getHeader().getFields());

            File[] ntx = new File(folder).listFiles((d, n) -> n.toLowerCase().endsWith(".ntx"));
            if (ntx == null) return;

            for (File f : ntx) {
                try (NtxIndex idx = NtxIndex.open(f.getPath())) {
                    String keyExpr = idx.keyExpression();
                    if (!evaluator.canEvaluate(keyExpr, 1)) {
                        continue; // unsupported / not this table's index
                    }
                    int[] stat = {0, 0}; // matched, mismatched
                    String[] firstBad = {null};
                    idx.forEach(e -> {
                        if (stat[0] + stat[1] >= sampleCap) return false;
                        try {
                            reader.absolute((int) e.recordNumber);
                            byte[] got = evaluator.evaluate(keyExpr,
                                name -> safeGet(reader, name), e.recordNumber);
                            String want = new String(e.key, StandardCharsets.ISO_8859_1);
                            String have = got == null ? null
                                : new String(got, StandardCharsets.ISO_8859_1);
                            if (want.equals(have)) {
                                stat[0]++;
                            } else {
                                stat[1]++;
                                if (firstBad[0] == null) {
                                    firstBad[0] = "recno=" + e.recordNumber
                                        + "\n      want=[" + want + "]"
                                        + "\n      have=[" + have + "]";
                                }
                            }
                        } catch (Exception ex) {
                            stat[1]++;
                        }
                        return true;
                    });
                    String verdict = stat[1] == 0 ? "OK (writable)" : "MISMATCH";
                    System.out.printf("%-14s %-9s matched=%d mismatched=%d  key=%s%n",
                        f.getName(), verdict, stat[0], stat[1], keyExpr);
                    if (firstBad[0] != null) {
                        System.out.println("      " + firstBad[0]);
                    }
                }
            }
        }
    }

    // Char fields must be raw (untrimmed, fixed width) to reproduce key bytes;
    // numeric/date fields use the decoded typed value.
    private static Object safeGet(DBFReader reader, String name) {
        try {
            com.dbf.jdbc.dbf.DBFField f = reader.getHeader().getField(name);
            if (f != null && Character.toUpperCase(f.getType()) == 'C') {
                return reader.getRawString(name);
            }
            return reader.getValue(name);
        } catch (Exception e) {
            return null;
        }
    }
}
