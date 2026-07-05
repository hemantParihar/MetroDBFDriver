package com.dbf.example;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Verification suite for the production-hardening changes.
 * Runs read-only queries against E:/METRO/PA25 and checks results
 * for internal consistency (counts cross-checked between queries).
 */
public class ProductionVerifyTest {
    private static final String URL = "jdbc:dbf:E:/METRO/PA25";
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        // No Class.forName: verifies META-INF/services auto-registration
        try (Connection conn = DriverManager.getConnection(URL);
             Statement stmt = conn.createStatement()) {

            // 1. Total count
            long total = queryLong(stmt, "SELECT COUNT(*) AS total FROM master");
            check("COUNT(*) returns positive total", total > 0);

            // 2. WHERE RECNO() range vs LIMIT-like behavior
            long first10 = countRows(stmt, "SELECT RECNO() FROM master WHERE RECNO() BETWEEN 1 AND 10");
            check("RECNO() BETWEEN 1 AND 10 returns <= 10 rows (got " + first10 + ")", first10 <= 10 && first10 > 0);

            // 3. WHERE equality on RECNO
            try (ResultSet rs = stmt.executeQuery("SELECT RECNO(), * FROM master WHERE RECNO() = 5")) {
                check("RECNO() = 5 finds the row", rs.next());
                check("RECNO() = 5 returns record 5", rs.getInt(1) == 5);
                check("RECNO() = 5 returns exactly one row", !rs.next());
            }

            // 4. Empty result (WHERE 1=0) plus metadata
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM master WHERE 1=0")) {
                check("WHERE 1=0 returns no rows", !rs.next());
                check("WHERE 1=0 still exposes metadata", rs.getMetaData().getColumnCount() > 0);
            }

            // 5. AND / OR combination consistency:
            //    count(A) + count(B) - count(A AND B) == count(A OR B)
            long a = countRows(stmt, "SELECT RECNO() FROM master WHERE RECNO() <= 100");
            long b = countRows(stmt, "SELECT RECNO() FROM master WHERE RECNO() BETWEEN 51 AND 150");
            long aAndB = countRows(stmt, "SELECT RECNO() FROM master WHERE RECNO() <= 100 AND RECNO() BETWEEN 51 AND 150");
            long aOrB = countRows(stmt, "SELECT RECNO() FROM master WHERE RECNO() <= 100 OR RECNO() BETWEEN 51 AND 150");
            check("inclusion-exclusion holds for AND/OR (" + a + "+" + b + "-" + aAndB + "=" + aOrB + ")",
                a + b - aAndB == aOrB);

            // 6. NOT inverts a predicate
            long le100 = countRows(stmt, "SELECT RECNO() FROM master WHERE RECNO() <= 100");
            long notLe100 = countRows(stmt, "SELECT RECNO() FROM master WHERE NOT RECNO() <= 100");
            long all = countRows(stmt, "SELECT RECNO() FROM master");
            check("NOT partitions the table (" + le100 + "+" + notLe100 + "=" + all + ")",
                le100 + notLe100 == all);

            // 7. IN desugaring
            long inCount = countRows(stmt, "SELECT RECNO() FROM master WHERE RECNO() IN (1, 2, 3)");
            check("IN (1,2,3) returns 3 rows (got " + inCount + ")", inCount == 3);

            // 8. LIKE on a character column
            long likeCount = countRows(stmt, "SELECT CUST_DESC FROM master WHERE CUST_DESC LIKE '%ASSET%'");
            check("LIKE '%ASSET%' finds rows (got " + likeCount + ")", likeCount > 0);

            // 9. ORDER BY descending: first row has the max live RECNO
            //    (computed independently with an unordered scan)
            long maxLive = 0;
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT RECNO() FROM master WHERE RECNO() <= 50")) {
                while (rs.next()) {
                    maxLive = Math.max(maxLive, rs.getLong(1));
                }
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT RECNO() FROM master WHERE RECNO() <= 50 ORDER BY RECNO DESC")) {
                check("ORDER BY DESC has rows", rs.next());
                check("ORDER BY RECNO DESC starts at max live recno (" + maxLive + ")",
                    rs.getLong(1) == maxLive);
            }

            // 10. Aggregates with WHERE
            long count50 = queryLong(stmt, "SELECT COUNT(*) AS c FROM master WHERE RECNO() <= 50");
            check("COUNT with WHERE <= 50 (got " + count50 + ")", count50 <= 50 && count50 > 0);

            // 11. MIN / MAX / SUM / AVG
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT MIN(ROW_ID) AS mn, MAX(ROW_ID) AS mx, AVG(ROW_ID) AS av FROM master")) {
                check("MIN/MAX/AVG returns a row", rs.next());
                double mn = rs.getDouble("mn");
                double mx = rs.getDouble("mx");
                double av = rs.getDouble("av");
                check("MIN <= AVG <= MAX (" + mn + " <= " + av + " <= " + mx + ")",
                    mn <= av && av <= mx);
            }

            // 12. GROUP BY: group counts must sum to total
            long groupSum = 0;
            int groupCount = 0;
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT M_S, COUNT(*) AS c FROM master GROUP BY M_S")) {
                while (rs.next()) {
                    groupSum += rs.getLong("c");
                    groupCount++;
                }
            }
            check("GROUP BY produces groups (got " + groupCount + ")", groupCount > 0);
            check("GROUP BY counts sum to total (" + groupSum + "=" + total + ")", groupSum == total);

            // 13. Column alias
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT CUST_DESC AS name FROM master WHERE RECNO() = 1")) {
                check("Alias row exists", rs.next());
                check("Alias is addressable", rs.getString("name") != null);
            }

            // 14. Parse error is reported, not swallowed
            boolean threw = false;
            try {
                stmt.executeQuery("SELECT FROM WHERE");
            } catch (SQLException e) {
                threw = true;
            }
            check("Invalid SQL throws SQLException", threw);

            // 15. Unknown column is reported
            threw = false;
            try {
                stmt.executeQuery("SELECT NO_SUCH_COL FROM master");
            } catch (SQLException e) {
                threw = true;
            }
            check("Unknown column throws SQLException", threw);
        }

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static long queryLong(Statement stmt, String sql) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(sql)) {
            if (!rs.next()) throw new SQLException("No row for: " + sql);
            return rs.getLong(1);
        }
    }

    private static long countRows(Statement stmt, String sql) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(sql)) {
            long n = 0;
            while (rs.next()) n++;
            return n;
        }
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
