package com.dbf.example;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/** Breaks the TOP-1 join query into its cost components (one warm JVM). */
public class GapProfile {
    static final String FULL =
        "SELECT TOP 1 SALES.D_DATE,SALES.DEBIT,SALES.V_NO,SALES.LOTE,SALES.LOTESN,SALES.AGENT,"
      + "SALES.TR_CODE1,SALES.TR_CODE2,SALES.TR_CODE3,SALES.TR_CODE4,L.G_C,L.QUALITY,L.REM "
      + "From (SALES LEFT JOIN LOTE AS L ON SALES.LOTE=L.LOTE AND SALES.LOTESN=L.LOTESN) "
      + "WHERE SALES.TYPE='A' AND SALES.V_NO<>999999 ORDER BY SALES.V_NO DESC";

    public static void main(String[] args) throws Exception {
        String url = "jdbc:dbf:" + (args.length > 0 ? args[0] : "E:/METRO/SG20") + ";charset=Cp1252";
        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {
            for (int warm = 0; warm < 2; warm++) { time(st, "warmup", FULL); }
            System.out.println("--- components (warm) ---");
            time(st, "A: SALES scan+filter only  ",
                "SELECT SALES.V_NO FROM SALES WHERE SALES.TYPE='A' AND SALES.V_NO<>999999");
            time(st, "B: LOTE full scan          ", "SELECT LOTE, LOTESN FROM LOTE");
            time(st, "C: A + ORDER BY (no join)  ",
                "SELECT TOP 1 SALES.V_NO FROM SALES WHERE SALES.TYPE='A' AND SALES.V_NO<>999999 "
              + "ORDER BY SALES.V_NO DESC");
            time(st, "D: full query (join+sort)  ", FULL);
        }
    }

    static void time(Statement st, String label, String sql) throws Exception {
        long best = Long.MAX_VALUE; long rows = 0;
        for (int i = 0; i < 3; i++) {
            long t0 = System.nanoTime();
            try (ResultSet rs = st.executeQuery(sql)) {
                rows = 0;
                while (rs.next()) rows++;
            }
            best = Math.min(best, (System.nanoTime() - t0) / 1_000_000);
        }
        System.out.println(label + " = " + best + "ms  (" + rows + " rows)");
    }
}
