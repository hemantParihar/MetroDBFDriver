package com.dbf.example;

import java.io.ByteArrayOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.List;

import com.dbf.jdbc.dbf.DBFField;
import com.dbf.jdbc.dbf.DBFReader;

/** Per-record comparison of TR_WT: raw .DBT text vs what DBFReader returns. */
public class MemoReadDiag {
    public static void main(String[] args) throws Exception {
        String folder = args.length > 0 ? args[0] : "E:/METRO/SG20";
        Charset cs = Charset.forName("Cp1252");
        String dbf = folder + "/SALES.DBF";
        String dbt = folder + "/SALES.DBT";

        try (DBFReader reader = new DBFReader(dbf, cs);
             RandomAccessFile mem = new RandomAccessFile(dbt, "r")) {

            List<DBFField> fields = reader.getHeader().getFields();
            int trwtIdx = -1;
            for (int i = 0; i < fields.size(); i++) {
                if (fields.get(i).getName().equalsIgnoreCase("TR_WT")) { trwtIdx = i; break; }
            }
            DBFField trwt = fields.get(trwtIdx);
            System.out.println("TR_WT idx=" + trwtIdx + " offset=" + trwt.getOffset()
                + " len=" + trwt.getLength() + " dbtBlocks=" + (mem.length() / 512));

            reader.beforeFirst();
            int shown = 0, mismatches = 0, scanned = 0;
            while (reader.next() && shown < 20) {
                if (reader.isDeleted()) continue;
                scanned++;
                byte[] rec = reader.getCurrentRecordRaw();
                // 10-byte ASCII block number (record byte 0 is the delete flag).
                String blkStr = new String(rec, 1 + trwt.getOffset(), 10,
                    java.nio.charset.StandardCharsets.US_ASCII).trim();
                if (blkStr.isEmpty() || !blkStr.matches("\\d+")) continue;
                int block = Integer.parseInt(blkStr);

                String jdbc = (String) reader.getValue(trwtIdx);
                String raw = rawDbt(mem, block);

                boolean match = (raw == null ? jdbc == null : raw.equals(jdbc));
                if (!match) mismatches++;
                if (shown < 20) {
                    System.out.printf("blk=%-5d jdbcLen=%-4s rawLen=%-4s %s%n",
                        block,
                        jdbc == null ? "null" : jdbc.length(),
                        raw == null ? "null" : raw.length(),
                        match ? "OK  jdbc=[" + trunc(jdbc) + "]"
                              : "MISMATCH\n     jdbc=[" + jdbc + "]\n     raw =[" + raw + "]");
                    shown++;
                }
            }
            System.out.println("\nscanned(non-deleted)=" + scanned
                + " shown=" + shown + " mismatches=" + mismatches);
        }
    }

    /** Independent dBASE III read: from block*512 until the first 0x1A. */
    private static String rawDbt(RandomAccessFile mem, int block) throws Exception {
        long off = (long) block * 512;
        if (off >= mem.length()) return "";
        mem.seek(off);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[512];
        long remaining = mem.length() - off;
        while (remaining > 0) {
            int n = mem.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (n <= 0) break;
            for (int i = 0; i < n; i++) {
                if (buf[i] == 0x1A) return new String(out.toByteArray(), Charset.forName("Cp1252"));
                out.write(buf[i]);
            }
            remaining -= n;
        }
        return new String(out.toByteArray(), Charset.forName("Cp1252"));
    }

    private static String trunc(String s) {
        if (s == null) return "null";
        s = s.replace("\r", "\\r").replace("\n", "\\n");
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }
}
