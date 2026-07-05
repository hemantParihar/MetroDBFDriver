package com.dbf.example;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/** Verifies case-sensitive TYPE range now excludes lowercase 'i' (matches ODBC). */
public class TypeRangeDiag {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:dbf:" + (args.length > 0 ? args[0] : "E:/METRO/PA25");
        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {

            one(st, "rows passing TYPE>='A' AND TYPE<='Z' on 01-04 (expect 8, no 'i')",
                "SELECT COUNT(*) FROM SALES WHERE D_DATE=#2025-04-01# "
              + "AND ( TYPE>='A' AND TYPE<='Z' )");

            one(st, "01-04 Inv-Amount total over A..Z rows (expect 56335)",
                "SELECT SUM(A_BAL) FROM SALES WHERE D_DATE=#2025-04-01# "
              + "AND ( TYPE>='A' AND TYPE<='Z' )");

            one(st, "lowercase 'i' rows still admitted? (expect 0)",
                "SELECT COUNT(*) FROM SALES WHERE D_DATE=#2025-04-01# "
              + "AND TYPE>='A' AND TYPE<='Z' AND TYPE='i'");
        }
    }

    private static void one(Statement st, String label, String sql) {
        try (ResultSet rs = st.executeQuery(sql)) {
            System.out.printf("%-58s = %s%n", label, rs.next() ? rs.getString(1) : "-");
        } catch (Throwable e) {
            System.out.printf("%-58s ERROR %s%n", label, e);
        }
    }
}
