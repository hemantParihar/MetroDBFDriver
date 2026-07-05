package com.dbf.example;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/** Confirms grouped output (no ORDER BY) now comes back in GROUP BY key order. */
public class GroupByOrderCheck {
    public static void main(String[] args) throws Exception {
        String folder = args.length > 0 ? args[0] : "E:/METRO/PA25";
        String url = "jdbc:dbf:" + folder;
        String w = "WHERE not isnull(d_date) AND ( D_Date>=#2020-04-01# AND D_DATE<=#2025-10-10# ) "
                 + "AND ( TYPE>='A' AND TYPE<='Z' )";
        String join =
            "From (((((SALES LEFT JOIN MASTER ON SALES.DEBIT=MASTER.C_HEAD) "
          + "LEFT JOIN LOTE AS L ON l.LOTE=SALES.LOTE AND l.LOTESN=SALES.LOTESN) "
          + "LEFT JOIN MASTER AS GOODSAC ON l.CUST_CODE=GOODSAC.C_HEAD ) "
          + "LEFT JOIN AREA ON SALES.ITEM=AREA.ACODE) LEFT JOIN TAX ON SALES.ST=TAX.TAX_CODE)  "
          + "LEFT JOIN MASTER AS AGENT ON SALES.AGENT=AGENT.C_HEAD   ";

        String sql = "SELECT MAX(SALES.D_DATE) AS d, MAX(SALES.TYPE) AS t, MAX(SALES.V_NO) AS v "
            + join + w + " GROUP BY SALES.D_DATE,SALES.TYPE,SALES.V_NO";

        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            String prev = null;
            boolean ordered = true;
            int n = 0;
            while (rs.next()) {
                String key = rs.getString("d") + "|" + rs.getString("t").trim()
                    + "|" + String.format("%08d", rs.getLong("v"));
                if (prev != null && key.compareTo(prev) < 0) {
                    ordered = false;
                    System.out.println("OUT OF ORDER at row " + n + ": " + prev + " -> " + key);
                }
                if (n < 8) System.out.println("  row " + n + ": " + key);
                prev = key;
                n++;
            }
            System.out.println("rows=" + n + "  ascending-by-(date,type,v_no)=" + ordered);
        }
    }
}
