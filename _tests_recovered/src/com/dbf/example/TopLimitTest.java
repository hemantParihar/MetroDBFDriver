package com.dbf.example;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Verifies SQL-Server/Access TOP n and MySQL/SQLite LIMIT n row limiting. */
public class TopLimitTest {
    private static final String URL = "jdbc:dbf:E:/METRO/PA25";
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        try (Connection conn = DriverManager.getConnection(URL);
             Statement stmt = conn.createStatement()) {

            long total = count(stmt, "SELECT COUNT(*) AS c FROM master");
            check("master has many rows", total > 10);

            // The exact failing query from the report
            check("TOP 1 * returns exactly 1 row", rows(stmt, "SELECT TOP 1 * FROM master") == 1);
            check("TOP 5 returns 5 rows", rows(stmt, "SELECT TOP 5 C_HEAD FROM master") == 5);
            check("TOP 0 returns 0 rows", rows(stmt, "SELECT TOP 0 * FROM master") == 0);

            // TOP larger than table -> all rows, no error
            check("TOP huge returns all rows",
                rows(stmt, "SELECT TOP 999999999 C_HEAD FROM master") == total);

            // LIMIT (trailing)
            check("LIMIT 3 returns 3 rows", rows(stmt, "SELECT C_HEAD FROM master LIMIT 3") == 3);

            // TOP combined with WHERE
            check("TOP 2 with WHERE returns <= 2",
                rows(stmt, "SELECT C_HEAD FROM master WHERE C_HEAD > 0 LIMIT 2") <= 2);

            // TOP with ORDER BY: first N of the ordered stream
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT TOP 3 C_HEAD FROM master ORDER BY C_HEAD")) {
                double prev = Double.NEGATIVE_INFINITY;
                int n = 0;
                boolean ordered = true;
                while (rs.next()) {
                    double v = rs.getDouble(1);
                    if (v < prev) ordered = false;
                    prev = v;
                    n++;
                }
                check("TOP 3 ORDER BY returns 3 ordered rows", n == 3 && ordered);
            }

            // TOP on a single-table fast path AND mixed case keyword
            check("lowercase 'top' works", rows(stmt, "select top 4 * from master") == 4);

            // Verify TOP 1 actually fetches the first row's data
            try (ResultSet rs = stmt.executeQuery("SELECT TOP 1 C_HEAD, CUST_DESC FROM master")) {
                check("TOP 1 row is readable", rs.next() && rs.getString("CUST_DESC") != null);
                check("TOP 1 has no second row", !rs.next());
            }
        }

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static long rows(Statement stmt, String sql) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(sql)) {
            long n = 0;
            while (rs.next()) n++;
            return n;
        }
    }

    private static long count(Statement stmt, String sql) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
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
