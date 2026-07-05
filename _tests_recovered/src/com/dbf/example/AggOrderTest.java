package com.dbf.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Verifies ORDER BY with aggregate functions, including aggregates over
 * columns that are not in the SELECT list (e.g. ORDER BY MAX(HO)).
 */
public class AggOrderTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("dbf-aggorder-test");
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');

        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {

            stmt.executeUpdate("CREATE TABLE t (GRP CHAR(2), AMT NUMERIC(6), HO NUMERIC(4), D DATE)");
            // GRP A: max AMT 30, max HO 5
            stmt.executeUpdate("INSERT INTO t (GRP,AMT,HO,D) VALUES ('A',10,5,'2025-01-01')");
            stmt.executeUpdate("INSERT INTO t (GRP,AMT,HO,D) VALUES ('A',30,2,'2025-02-01')");
            // GRP B: max AMT 20, max HO 9
            stmt.executeUpdate("INSERT INTO t (GRP,AMT,HO,D) VALUES ('B',20,9,'2025-03-01')");
            // GRP C: max AMT 50, max HO 1
            stmt.executeUpdate("INSERT INTO t (GRP,AMT,HO,D) VALUES ('C',50,1,'2025-04-01')");

            // ORDER BY MAX(AMT) ascending -> B(20), A(30), C(50)
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT GRP, MAX(AMT) AS ma FROM t GROUP BY GRP ORDER BY MAX(AMT)")) {
                check("row1 = B", rs.next() && "B".equals(rs.getString("GRP").trim()));
                check("row2 = A", rs.next() && "A".equals(rs.getString("GRP").trim()));
                check("row3 = C", rs.next() && "C".equals(rs.getString("GRP").trim()));
            }

            // ORDER BY an aggregate of a NON-selected column: MAX(HO) -> C(1),A(5),B(9)
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT GRP, MAX(AMT) AS ma FROM t GROUP BY GRP ORDER BY MAX(HO)")) {
                check("by MAX(HO) row1 = C", rs.next() && "C".equals(rs.getString("GRP").trim()));
                check("by MAX(HO) row2 = A", rs.next() && "A".equals(rs.getString("GRP").trim()));
                check("by MAX(HO) row3 = B", rs.next() && "B".equals(rs.getString("GRP").trim()));
            }

            // ORDER BY aggregate DESC
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT GRP FROM t GROUP BY GRP ORDER BY MAX(AMT) DESC")) {
                check("desc row1 = C", rs.next() && "C".equals(rs.getString("GRP").trim()));
                check("desc row2 = A", rs.next() && "A".equals(rs.getString("GRP").trim()));
                check("desc row3 = B", rs.next() && "B".equals(rs.getString("GRP").trim()));
            }

            // Multi-key: ORDER BY MAX(D), MAX(HO)
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT GRP, MAX(D) AS md FROM t GROUP BY GRP ORDER BY MAX(D), MAX(HO)")) {
                check("by date row1 = A (2025-02 max)", rs.next() && "A".equals(rs.getString("GRP").trim()));
                check("by date row2 = B (2025-03)", rs.next() && "B".equals(rs.getString("GRP").trim()));
                check("by date row3 = C (2025-04)", rs.next() && "C".equals(rs.getString("GRP").trim()));
            }
        }

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("PASS: " + name);
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }
}
