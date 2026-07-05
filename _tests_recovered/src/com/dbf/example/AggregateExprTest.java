package com.dbf.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Verifies aggregates used inside expressions: MAX(x)+1, COUNT(*)+1,
 * MAX(x)+MIN(x), SUM(x)*2 (with GROUP BY) — the "next id" pattern and friends.
 */
public class AggregateExprTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("dbf-aggexpr-test");
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');

        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {

            // The exact reported case: SELECT MAX(lote)+1 FROM lote
            st.executeUpdate("CREATE TABLE lote (LOTE NUMERIC(6))");
            st.executeUpdate("INSERT INTO lote (LOTE) VALUES (5)");
            st.executeUpdate("INSERT INTO lote (LOTE) VALUES (8)");
            st.executeUpdate("INSERT INTO lote (LOTE) VALUES (3)");

            try (ResultSet rs = st.executeQuery("SELECT MAX(LOTE)+1 AS nextlote FROM lote")) {
                check("MAX(LOTE)+1 row present", rs.next());
                check("MAX(LOTE)+1 = 9", rs.getInt("nextlote") == 9);
                check("MAX(LOTE)+1 is integer (no .0)", "9".equals(rs.getString("nextlote").trim()));
            }

            // Variants over a grouped table
            st.executeUpdate("CREATE TABLE t (G NUMERIC(2), V NUMERIC(5))");
            st.executeUpdate("INSERT INTO t (G, V) VALUES (1, 10)");
            st.executeUpdate("INSERT INTO t (G, V) VALUES (1, 30)");
            st.executeUpdate("INSERT INTO t (G, V) VALUES (2, 5)");

            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*)+1 AS cp, MAX(V)+MIN(V) AS span, MAX(V)-1 AS mxm FROM t")) {
                rs.next();
                check("COUNT(*)+1 = 4", rs.getInt("cp") == 4);
                check("MAX(V)+MIN(V) = 35", rs.getInt("span") == 35);
                check("MAX(V)-1 = 29", rs.getInt("mxm") == 29);
            }

            try (ResultSet rs = st.executeQuery(
                    "SELECT G, SUM(V)*2 AS dbl FROM t GROUP BY G ORDER BY G")) {
                check("group row 1", rs.next());
                check("G=1 SUM(V)*2 = 80", rs.getInt("dbl") == 80);
                check("group row 2", rs.next());
                check("G=2 SUM(V)*2 = 10", rs.getInt("dbl") == 10);
            }

            // Aggregate inside a function, and mixed with a column constant
            try (ResultSet rs = st.executeQuery("SELECT ABS(MIN(V)-100) AS d FROM t")) {
                rs.next();
                check("ABS(MIN(V)-100) = 95", rs.getInt("d") == 95);
            }
        }

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("PASS: " + name); }
        else { failed++; System.out.println("FAIL: " + name); }
    }
}
