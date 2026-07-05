package com.dbf.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLDataException;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Verifies the data-validation semantics ported from the DANS DBF library,
 * surfaced as SQLDataExceptions with proper SQLStates:
 *   22003 numeric value out of range (ValueTooLargeException)
 *   22018 data mismatch              (DataMismatchException)
 *   22007 invalid datetime format
 *   42000 invalid field length       (InvalidFieldLengthException)
 */
public class ValidationTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("dbf-validation-test");
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');

        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {

            // ---- CREATE TABLE field length validation ----
            expectState(stmt, "CREATE TABLE bad1 (NAME CHAR(300))", "42000",
                "CHAR(300) rejected (max 254)");
            expectState(stmt, "CREATE TABLE bad2 (AMT NUMERIC(25))", "42000",
                "NUMERIC(25) rejected (max 19)");
            expectState(stmt, "CREATE TABLE bad3 (AMT NUMERIC(5,5))", "42000",
                "NUMERIC(5,5) rejected (no room for point)");
            check("CREATE with no .dbf leftovers from rejected tables",
                !Files.exists(dir.resolve("bad1.dbf")) && !Files.exists(dir.resolve("bad2.dbf")));

            // ---- record-too-large: 300 x CHAR(254) = ~76k > 65535 ----
            StringBuilder wide = new StringBuilder("CREATE TABLE huge (");
            for (int i = 0; i < 300; i++) {
                if (i > 0) wide.append(", ");
                wide.append("C").append(i).append(" CHAR(254)");
            }
            wide.append(")");
            expectState(stmt, wide.toString(), "54000",
                "record wider than 65535 bytes rejected (RecordTooLargeException)");
            check("oversized table left no file behind", !Files.exists(dir.resolve("huge.dbf")));

            stmt.executeUpdate("CREATE TABLE t (ID NUMERIC(3), AMT NUMERIC(8,2), "
                + "NAME CHAR(5), DOB DATE)");

            // ---- numeric overflow: digits are never silently chopped ----
            expectState(stmt, "INSERT INTO t (ID) VALUES (12345)", "22003",
                "numeric overflow rejected with SQLState 22003");
            check("rejected insert wrote nothing",
                count(stmt, "SELECT COUNT(*) AS c FROM t") == 0);

            // value that fits exactly still works
            stmt.executeUpdate("INSERT INTO t (ID, AMT) VALUES (999, 12345.67)");
            try (ResultSet rs = stmt.executeQuery("SELECT ID, AMT FROM t")) {
                check("max-width value round-trips", rs.next()
                    && rs.getInt(1) == 999
                    && Math.abs(rs.getDouble(2) - 12345.67) < 0.001);
            }

            // ---- type mismatch: text into a numeric column ----
            expectState(stmt, "INSERT INTO t (ID) VALUES ('abc')", "22018",
                "non-numeric text into NUMERIC rejected with 22018");

            // numeric strings are still accepted
            stmt.executeUpdate("UPDATE t SET ID = '42' WHERE ID = 999");
            check("numeric string accepted into NUMERIC",
                count(stmt, "SELECT COUNT(*) AS c FROM t WHERE ID = 42") == 1);

            // ---- date validation ----
            expectState(stmt, "INSERT INTO t (ID, DOB) VALUES (1, 'not-a-date')", "22007",
                "garbage date rejected with 22007");
            stmt.executeUpdate("INSERT INTO t (ID, DOB) VALUES (1, '2026-06-12')");
            try (ResultSet rs = stmt.executeQuery("SELECT DOB FROM t WHERE ID = 1")) {
                check("ISO date string round-trips", rs.next()
                    && "2026-06-12".equals(rs.getDate(1).toString()));
            }

            // ---- UPDATE goes through the same validation ----
            expectState(stmt, "UPDATE t SET AMT = 9999999.99 WHERE ID = 42", "22003",
                "UPDATE overflow rejected (9999999.99 needs 10 > 8 chars)");

            // ---- character truncation stays silent (dBASE behavior) ----
            stmt.executeUpdate("INSERT INTO t (ID, NAME) VALUES (2, 'LONGERTHAN5')");
            try (ResultSet rs = stmt.executeQuery("SELECT NAME FROM t WHERE ID = 2")) {
                check("char values truncate silently like dBASE", rs.next()
                    && "LONGE".equals(rs.getString(1).trim()));
            }
        }

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void expectState(Statement stmt, String sql, String expectedState,
            String testName) {
        try {
            stmt.executeUpdate(sql);
            failed++;
            System.out.println("FAIL: " + testName + " (no exception thrown)");
        } catch (SQLException e) {
            boolean stateOk = expectedState.equals(e.getSQLState());
            boolean typeOk = !expectedState.startsWith("22") || e instanceof SQLDataException;
            if (stateOk && typeOk) {
                passed++;
                System.out.println("PASS: " + testName);
            } else {
                failed++;
                System.out.println("FAIL: " + testName + " (state=" + e.getSQLState()
                    + " class=" + e.getClass().getSimpleName() + "): " + e.getMessage());
            }
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
