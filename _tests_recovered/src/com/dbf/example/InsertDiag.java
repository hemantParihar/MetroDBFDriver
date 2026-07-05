package com.dbf.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import com.dbf.jdbc.dbf.DBFReader;

/** Dumps SALES schema, then reproduces the user's INSERT on a COPY of SG20 SALES. */
public class InsertDiag {
    public static void main(String[] args) throws Exception {
        String srcFolder = "E:/METRO/SG20";

        // 1. Schema dump from the real (read-only) file.
        System.out.println("=== SALES schema (SG20) ===");
        int fieldCount;
        try (DBFReader r = new DBFReader(srcFolder + "/SALES.DBF", java.nio.charset.Charset.forName("Cp1252"))) {
            var fields = r.getHeader().getFields();
            fieldCount = fields.size();
            System.out.println("recordCount=" + r.getHeader().getRecordCount() + " fields=" + fieldCount);
            for (var f : fields) {
                System.out.printf("  %-12s %c len=%d dec=%d%s%n",
                    f.getName(), f.getType(), f.getLength(), f.getDecimalCount(),
                    f.getType() == 'M' ? "   <-- MEMO" : "");
            }
        }

        // 2. Copy SALES.DBF + SALES.DBT to a temp folder.
        Path tmp = Files.createTempDirectory("dbf-insertdiag");
        Files.copy(Paths.get(srcFolder, "SALES.DBF"), tmp.resolve("SALES.DBF"), StandardCopyOption.REPLACE_EXISTING);
        if (Files.exists(Paths.get(srcFolder, "SALES.DBT"))) {
            Files.copy(Paths.get(srcFolder, "SALES.DBT"), tmp.resolve("SALES.DBT"), StandardCopyOption.REPLACE_EXISTING);
        }
        String url = "jdbc:dbf:" + tmp.toString().replace('\\', '/') + ";charset=Cp1252";

        String insert = "INSERT INTO sales (`ENTERY`,`A_BAL`,`LOTE`,`LOTESN`,`ST`,`MT`,`ITEM`,`I_R`,`V_TYPE`,`V_NO`,`TYPE`,`D_DATE`,`DEBIT`,`AGENT`,`TR_CODE1`,`TR_CODE2`,`TR_CODE3`,`TR_CODE4`,`BY_HAND`,`DALAL_OPT`,`PARTY_N`,`TIN_NO`,`TRANS_N`,`GR_NO`,`LR_NO`,`TR_FORM`,`IRN_NO`,`ACK_NO`,`PLACEFM`,`PLACETO`,`TCS_AMT`,`TCS_RATE`,`TCS_TAX`,`TDS_PUR_V`,`TDS_PUR_A`,`TDS_PUR_R`,`TDS_PUR_T`,`PROD_INV`,`QT`,`WT_LIST`,`TR_WT`,`PACK_WT`,`PACK_SO`,`PACK_NET`,`PACK_LES`,`WT`,`L_RATE`,`L_ADD`,`RATE`,`BARDAN`,`NET`,`COMM_RATE`,`COMM`,`DL_RATE`,`S_COMM`,`WT_RATE`,`S_CHARG`,`S_TAX1`,`S_TAX1SC`,`LABOUR`,`IGST_AMT`,`CGST_AMT`,`SGST_AMT`,`CESS_AMT`,`CESS_SC`,`RCM_OPT`,`ITC_SLB`,`S_TAX`,`C_DESCO`,`L_D`,`ROW_ID`,`WT_LOSS`,`ST_SN`,`SR_CHAR`,`TDS_ONCF`,`TDS_ON`,`TDS_RATE`,`TDS_AMT`,`TDS_SCR`,`TDS_SCA`,`TDS_EDR`,`TDS_EDA`,`URD_M_R`,`URD_M_A`,`URD_PYT`,`URD_PYTC`,`URD_PYTB`,`RETU_AMT`,`RETU_TAX`,`URD_S_R`,`URD_S_A`,`PAY_AMT`) "
            + "VALUES(1.00,5035.06,12.00,1.00,16.00,3.00,0.00,'','IN',1129.00,'A',#2025-10-10#,41695.00,0.00,0.00,0.00,0.00,0.00,'','','','','','','','','','','','',0.00,0.00,0.00,0.00,0.00,0.00,0.00,'',1.00,'12+23+23+45+46','',0.00,0.00,149.00,0.00,149.00,0.00,0.00,3167.64,0.00,4719.78,0.00,0.00,0.00,0.00,0.00,0.00,75.52,0.00,0.00,0.00,119.88,119.88,0.00,0.00,'','',239.76,0.00,0.00,35692,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0)";

        // Count columns vs values for the user's statement.
        int cols = countCols(insert);
        int vals = countVals(insert);
        System.out.println("\nINSERT column-list count=" + cols + "  value count=" + vals
            + "  (table fields=" + fieldCount + ")");

        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {
            long before = count(st);
            System.out.println("rows before insert = " + before);
            try {
                int n = st.executeUpdate(insert);
                System.out.println("executeUpdate returned = " + n + " (no exception)");
            } catch (Throwable e) {
                System.out.println("INSERT threw: " + e);
            }
            long after = count(st);
            System.out.println("rows after insert  = " + after + "  (delta=" + (after - before) + ")");
            try (ResultSet rs = st.executeQuery("SELECT V_NO, ENTERY, A_BAL FROM SALES WHERE V_NO=1129")) {
                int found = 0;
                while (rs.next()) found++;
                System.out.println("rows with V_NO=1129 after = " + found);
            }
        }
    }

    private static long count(Statement st) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM SALES")) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private static int countCols(String sql) {
        int a = sql.indexOf('('), b = sql.indexOf(')');
        return sql.substring(a + 1, b).split(",").length;
    }

    private static int countVals(String sql) {
        int v = sql.toUpperCase().indexOf("VALUES");
        int a = sql.indexOf('(', v), b = sql.lastIndexOf(')');
        return sql.substring(a + 1, b).split(",").length;
    }
}
