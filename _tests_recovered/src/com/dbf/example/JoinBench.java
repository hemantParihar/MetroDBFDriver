package com.dbf.example;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/** Times a representative dimension join (run with -Ddbf.join.inMemory=off to A/B). */
public class JoinBench {
    public static void main(String[] args) throws Exception {
        String folder = args.length > 0 ? args[0] : "E:/METRO/SG20";
        String url = "jdbc:dbf:" + folder + ";charset=Cp1252";
        String sql = "SELECT SALES.ROW_ID, SALES.A_BAL, MASTER.CUST_DESC, L.QUALITY "
            + "FROM (SALES LEFT JOIN MASTER ON SALES.DEBIT=MASTER.C_HEAD) "
            + "LEFT JOIN LOTE AS L ON SALES.LOTE=L.LOTE AND SALES.LOTESN=L.LOTESN";

        System.out.println("inMemory=" + (System.getProperty("dbf.join.inMemory") == null ? "ON" : "OFF")
            + "  heap=" + (Runtime.getRuntime().maxMemory() / (1024 * 1024)) + "MB");
        for (int run = 0; run < 2; run++) {
            long t0 = System.currentTimeMillis();
            int n = 0; long sum = 0;
            try (Connection c = DriverManager.getConnection(url);
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) { n++; sum += rs.getLong("ROW_ID"); }
            }
            System.out.println("run" + run + ": " + n + " rows in "
                + (System.currentTimeMillis() - t0) + "ms (checksum=" + sum + ")");
        }
    }
}
