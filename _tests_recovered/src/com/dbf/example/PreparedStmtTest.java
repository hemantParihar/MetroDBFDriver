package com.dbf.example;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Reproduces the Repository bug: a parameterless PreparedStatement query
 * ("SELECT * FROM SETP") wrongly demanded one parameter. Also confirms that
 * parameterized prepared statements (setObject/setInt) still work.
 */
public class PreparedStmtTest {
    private static final String URL = "jdbc:dbf:E:/METRO/PA25";
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        try (Connection conn = DriverManager.getConnection(URL)) {

            // 1. Parameterless prepared query (the exact failing shape)
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM SETP");
                 ResultSet rs = ps.executeQuery()) {
                int n = 0;
                while (rs.next()) n++;
                check("parameterless 'SELECT * FROM SETP' executes (" + n + " rows)", n >= 0);
            } catch (SQLException e) {
                fail("parameterless SELECT * FROM SETP", e);
            }

            // 2. Parameterless with WHERE, via prepared statement
            try (PreparedStatement ps =
                     conn.prepareStatement("SELECT * FROM master WHERE C_HEAD > 0");
                 ResultSet rs = ps.executeQuery()) {
                int n = 0;
                while (rs.next()) n++;
                check("parameterless WHERE query executes (" + n + " rows)", n > 0);
            }

            // 3. Repository pattern: setObject loop with an empty params array
            check("repository-style call with no params works",
                repositoryStyle(conn, "SELECT * FROM master") >= 0);

            // 4. Parameterized: one '?', bound with setObject
            try (PreparedStatement ps =
                     conn.prepareStatement("SELECT C_HEAD, CUST_DESC FROM master WHERE C_HEAD = ?")) {
                ps.setObject(1, 1);
                try (ResultSet rs = ps.executeQuery()) {
                    boolean found = rs.next();
                    check("parameterized setObject(1, ...) binds and runs", found);
                    if (found) {
                        check("bound value matched C_HEAD=1", rs.getInt("C_HEAD") == 1);
                    }
                }
            }

            // 5. Parameterized via repository-style varargs
            check("repository-style call with one param works",
                repositoryStyle(conn, "SELECT C_HEAD FROM master WHERE C_HEAD = ?", 1) == 1);

            // 6. An unset parameter must STILL be reported (regression guard)
            boolean threw = false;
            try (PreparedStatement ps =
                     conn.prepareStatement("SELECT * FROM master WHERE C_HEAD = ?")) {
                ps.executeQuery(); // never set parameter 1
            } catch (SQLException e) {
                threw = e.getMessage() != null && e.getMessage().contains("not set");
            }
            check("genuinely-missing parameter is still reported", threw);
        }

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    /** Mirrors com.metro.repository.Repository.executeQuery. */
    private static long repositoryStyle(Connection con, String query, Object... params)
            throws SQLException {
        try (PreparedStatement statement = con.prepareStatement(query)) {
            if (params != null) {
                for (int i = 1; i <= params.length; i++) {
                    statement.setObject(i, params[i - 1]);
                }
            }
            try (ResultSet rs = statement.executeQuery()) {
                long n = 0;
                while (rs.next()) n++;
                return n;
            }
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

    private static void fail(String name, Exception e) {
        failed++;
        System.out.println("FAIL: " + name + " -> " + e.getMessage());
    }
}
