package com.dbf.example;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/** Compares GROUP BY collapse on the single-table path vs the JOINED path. */
public class GroupByDiag2 {
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

        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {
            count(st, "JOINED: GROUP BY SALES.D_DATE only (expect 169)",
                "SELECT MAX(SALES.V_NO) " + join + w + " GROUP BY SALES.D_DATE");
            count(st, "JOINED: GROUP BY SALES.TYPE only (expect 8)",
                "SELECT MAX(SALES.V_NO) " + join + w + " GROUP BY SALES.TYPE");
            count(st, "JOINED: GROUP BY SALES.D_DATE,SALES.TYPE,SALES.V_NO (expect 2242)",
                "SELECT MAX(SALES.V_NO) " + join + w + " GROUP BY SALES.D_DATE,SALES.TYPE,SALES.V_NO");
        }
    }

    private static void count(Statement st, String label, String sql) {
        try (ResultSet rs = st.executeQuery(sql)) {
            long n = 0;
            while (rs.next()) n++;
            System.out.printf("%-58s = %d%n", label, n);
        } catch (Throwable e) {
            System.out.printf("%-58s ERROR %s%n", label, e);
        }
    }
}
