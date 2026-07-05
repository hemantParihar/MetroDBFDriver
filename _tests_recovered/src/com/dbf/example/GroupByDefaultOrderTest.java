package com.dbf.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * A GROUP BY query with NO ORDER BY must return groups in ascending
 * grouping-key order (matching Access/Jet, the ODBC engine), not in
 * hash/scan ("first seen") order. Rows are inserted out of order on purpose.
 */
public class GroupByDefaultOrderTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("dbf-gborder");
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');

        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {

            // Single grouping column, inserted in scrambled order.
            st.executeUpdate("CREATE TABLE g (K CHAR(3), V NUMERIC(5))");
            for (String[] r : new String[][] {
                    {"CCC","1"}, {"AAA","2"}, {"BBB","3"}, {"AAA","4"}, {"CCC","5"} }) {
                st.executeUpdate("INSERT INTO g (K,V) VALUES ('" + r[0] + "'," + r[1] + ")");
            }
            check("single key: groups ascending [AAA,BBB,CCC]",
                strs(st, "SELECT K, SUM(V) FROM g GROUP BY K").equals(List.of("AAA", "BBB", "CCC")));

            // Composite key (date-like numeric + char), scrambled insert order.
            st.executeUpdate("CREATE TABLE h (D NUMERIC(8), T CHAR(1), N NUMERIC(4))");
            for (String[] r : new String[][] {
                    {"20250402","B"}, {"20250401","B"}, {"20250401","A"}, {"20250402","A"} }) {
                st.executeUpdate("INSERT INTO h (D,T,N) VALUES (" + r[0] + ",'" + r[1] + "',1)");
            }
            List<String> keys = new ArrayList<>();
            try (ResultSet rs = st.executeQuery(
                    "SELECT MAX(D) AS d, MAX(T) AS t, COUNT(*) AS c FROM h GROUP BY D, T")) {
                while (rs.next()) keys.add(rs.getLong("d") + "|" + rs.getString("t").trim());
            }
            check("composite key ascending by (D,T) (got " + keys + ")",
                keys.equals(List.of("20250401|A", "20250401|B", "20250402|A", "20250402|B")));

            // An explicit ORDER BY still wins over the default.
            check("explicit ORDER BY overrides default order",
                strs(st, "SELECT K, SUM(V) FROM g GROUP BY K ORDER BY K DESC")
                    .equals(List.of("CCC", "BBB", "AAA")));
        }

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static List<String> strs(Statement st, String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getString(1).trim());
        }
        return out;
    }

    private static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("PASS: " + name); }
        else { failed++; System.out.println("FAIL: " + name); }
    }
}
