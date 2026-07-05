package com.dbf.example;

import com.dbf.jdbc.dbf.DBFReader;
import com.dbf.jdbc.index.ntx.NtxIndex;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Verifies the NTX reader against real Clipper index files. Dumps the header
 * and the first keys, then seeks the prefix for the user's MASTER search
 * (L_FLAG='X' + UPPER(CUST_DESC) LIKE 'CA%' -> key prefix "XCA") and resolves
 * each hit against MASTER.DBF, so we can eyeball that the index returns the
 * same rows a full scan would.
 */
public class NtxDump {
    public static void main(String[] args) throws Exception {
        String folder = args.length > 0 ? args[0] : "E:/METRO/sg20";
        String ntxPath = folder + "/MASTER1.NTX";
        String dbfPath = folder + "/MASTER.DBF";
        String prefix = args.length > 1 ? args[1] : "XCA";

        try (NtxIndex idx = NtxIndex.open(ntxPath)) {
            System.out.println("signature : 0x" + Integer.toHexString(idx.signature()));
            System.out.println("rootPage  : " + idx.rootPage());
            System.out.println("keyLength : " + idx.keyLength());
            System.out.println("keyExpr   : " + idx.keyExpression());
            System.out.println();

            System.out.println("First 15 keys in index order:");
            int[] shown = {0};
            idx.forEach(e -> {
                if (shown[0]++ < 15) {
                    System.out.println("  recno=" + e.recordNumber + "  key=[" + e.keyString() + "]");
                    return true;
                }
                return false;
            });
            System.out.println("  ... total visited above capped at 15");
            System.out.println();

            byte[] pfx = prefix.getBytes(StandardCharsets.ISO_8859_1);
            long t0 = System.currentTimeMillis();
            List<Long> recnos = idx.seekPrefix(pfx, 50);
            long seekMs = System.currentTimeMillis() - t0;
            System.out.println("seekPrefix(\"" + prefix + "\") -> " + recnos.size()
                + " hits in " + seekMs + "ms: " + recnos);
            System.out.println();

            System.out.println("Resolved against MASTER.DBF:");
            try (DBFReader reader = new DBFReader(dbfPath, StandardCharsets.ISO_8859_1)) {
                for (long recno : recnos) {
                    reader.absolute((int) recno);
                    Object flag = reader.getValue("L_FLAG");
                    Object desc = reader.getValue("CUST_DESC");
                    System.out.println("  recno=" + recno
                        + "  L_FLAG=[" + flag + "]  CUST_DESC=[" + desc + "]");
                }
            }
        }
    }
}
