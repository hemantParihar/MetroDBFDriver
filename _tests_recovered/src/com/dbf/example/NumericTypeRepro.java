package com.dbf.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;

/** Shows which query paths return a whole-number NUMERIC as Long vs Double. */
public class NumericTypeRepro {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("dbf-numtype");
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');

        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE t (N8 NUMERIC(8), AMT NUMERIC(10,2), G NUMERIC(2))");
            st.executeUpdate("INSERT INTO t (N8, AMT, G) VALUES (11455, 12.50, 1)");
            st.executeUpdate("INSERT INTO t (N8, AMT, G) VALUES (22000, 7.50, 1)");

            System.out.println("== plain SELECT ==");
            dump(st, "SELECT N8, AMT FROM t");

            System.out.println("\n== arithmetic types ==");
            dump(st, "SELECT N8 + 5 AS int_int, N8 + AMT AS int_dbl, AMT + AMT AS dbl_dbl, "
                + "N8 * 2 AS int_mul, N8 / 2 AS div_exact, N8 / 3 AS div_inexact FROM t");

            System.out.println("\n== aggregates ==");
            dump(st, "SELECT SUM(N8) AS sum_int, SUM(AMT) AS sum_dbl, AVG(N8) AS avg_int, "
                + "AVG(AMT) AS avg_dbl, MAX(N8) AS mx FROM t GROUP BY G");
        }
    }

    private static void dump(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData m = rs.getMetaData();
            rs.next();
            for (int i = 1; i <= m.getColumnCount(); i++) {
                Object o = rs.getObject(i);
                System.out.printf("  %-10s getObject=%-12s class=%-18s getString=%s%n",
                    m.getColumnLabel(i), o,
                    o == null ? "null" : o.getClass().getSimpleName(),
                    rs.getString(i));
            }
        }
    }
}
