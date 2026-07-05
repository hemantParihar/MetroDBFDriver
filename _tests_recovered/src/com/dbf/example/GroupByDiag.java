package com.dbf.example;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/** Probes whether GROUP BY collapses rows on the real SALES table. */
public class GroupByDiag {
    public static void main(String[] args) throws Exception {
        String folder = args.length > 0 ? args[0] : "E:/METRO/PA25";
        String url = "jdbc:dbf:" + folder;
        String w = "WHERE not isnull(d_date) AND ( D_Date>=#2020-04-01# AND D_DATE<=#2025-10-10# ) "
                 + "AND ( TYPE>='A' AND TYPE<='Z' )";

        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {
            count(st, "total SALES rows in range",
                "SELECT D_DATE FROM SALES " + w);
            count(st, "GROUP BY D_DATE only",
                "SELECT MAX(V_NO) FROM SALES " + w + " GROUP BY D_DATE");
            count(st, "GROUP BY TYPE only",
                "SELECT MAX(V_NO) FROM SALES " + w + " GROUP BY TYPE");
            count(st, "GROUP BY D_DATE,TYPE,V_NO",
                "SELECT MAX(V_NO) FROM SALES " + w + " GROUP BY D_DATE,TYPE,V_NO");
            count(st, "GROUP BY D_DATE,TYPE,V_NO,ENTERY,RCM_OPT,ITC_SLB",
                "SELECT MAX(V_NO) FROM SALES " + w
                + " GROUP BY D_DATE,TYPE,V_NO,ENTERY,RCM_OPT,ITC_SLB");
            // Inspect a few raw key tuples to see if ENTERY/V_NO make them unique.
            System.out.println("--- sample rows (V_NO, ENTERY, RCM_OPT, ITC_SLB) ---");
            try (ResultSet rs = st.executeQuery(
                    "SELECT D_DATE, TYPE, V_NO, ENTERY, RCM_OPT, ITC_SLB FROM SALES " + w)) {
                int i = 0;
                while (rs.next() && i++ < 12) {
                    System.out.printf("  date=%s type=[%s] v_no=%s entery=[%s] rcm=[%s] itc=[%s]%n",
                        rs.getString("D_DATE"), rs.getString("TYPE"), rs.getString("V_NO"),
                        rs.getString("ENTERY"), rs.getString("RCM_OPT"), rs.getString("ITC_SLB"));
                }
            }
        }
    }

    private static void count(Statement st, String label, String sql) {
        try (ResultSet rs = st.executeQuery(sql)) {
            long n = 0;
            while (rs.next()) n++;
            System.out.printf("%-50s = %d%n", label, n);
        } catch (Throwable e) {
            System.out.printf("%-50s ERROR %s%n", label, e);
        }
    }
}
