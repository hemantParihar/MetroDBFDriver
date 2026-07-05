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
 * Aggregate queries that ORDER BY an expression (MID(...)) or a grouped column
 * not present in the SELECT list, alongside aggregate sort keys -- the shape of
 * the "All Ledger" report. Previously failed: "ORDER BY key not found".
 */
public class OrderByExprTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("dbf-orderby-expr");
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');
        String a30 = repeat('A', 30), b30 = repeat('B', 30), c30 = repeat('C', 30);

        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE t (NAME CHAR(40), V NUMERIC(5))");
            // chars 31+ hold a "city": ZEBRA / ALPHA / MID
            st.executeUpdate("INSERT INTO t (NAME, V) VALUES ('" + a30 + "ZEBRA', 10)");
            st.executeUpdate("INSERT INTO t (NAME, V) VALUES ('" + b30 + "ALPHA', 20)");
            st.executeUpdate("INSERT INTO t (NAME, V) VALUES ('" + c30 + "MID', 5)");

            // ORDER BY expression MID(NAME,31): ALPHA(20), MID(5), ZEBRA(10)
            check("ORDER BY MID(NAME,31) -> [20,5,10]",
                mxs(st, "SELECT 0 AS s, MAX(V) AS mx FROM t GROUP BY NAME ORDER BY MID(NAME,31)")
                    .equals(List.of(20, 5, 10)));

            // ORDER BY a grouped column not in the SELECT: NAME -> A,B,C -> [10,20,5]
            check("ORDER BY NAME -> [10,20,5]",
                mxs(st, "SELECT 0 AS s, MAX(V) AS mx FROM t GROUP BY NAME ORDER BY NAME")
                    .equals(List.of(10, 20, 5)));

            // ORDER BY aggregate + expression + grouped column together
            check("ORDER BY MAX(V) DESC, MID(NAME,31), NAME -> [20,10,5]",
                mxs(st, "SELECT 0 AS s, MAX(V) AS mx FROM t GROUP BY NAME "
                    + "ORDER BY MAX(V) DESC, MID(NAME,31), NAME").equals(List.of(20, 10, 5)));
        }

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static List<Integer> mxs(Statement st, String sql) throws Exception {
        List<Integer> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getInt("mx"));
        }
        return out;
    }

    private static String repeat(char ch, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(ch);
        return sb.toString();
    }

    private static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("PASS: " + name); }
        else { failed++; System.out.println("FAIL: " + name); }
    }
}
