package com.dbf.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Verifies ALTER TABLE: ADD COLUMN, DROP COLUMN, RENAME COLUMN,
 * RENAME TO - including data preservation, deleted-row preservation
 * (stable RECNOs), and memo column add/drop.
 */
public class AlterTableTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("dbf-alter-test");
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');

        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {

            stmt.executeUpdate("CREATE TABLE emp (ID NUMERIC(10), NAME CHAR(20))");
            stmt.executeUpdate("INSERT INTO emp (ID, NAME) VALUES (1, 'ALICE')");
            stmt.executeUpdate("INSERT INTO emp (ID, NAME) VALUES (2, 'BOB')");
            stmt.executeUpdate("INSERT INTO emp (ID, NAME) VALUES (3, 'CAROL')");
            stmt.executeUpdate("DELETE FROM emp WHERE ID = 2"); // leaves a deleted row

            // ---- ADD COLUMN ----
            stmt.executeUpdate("ALTER TABLE emp ADD COLUMN SAL NUMERIC(12,2)");
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM emp WHERE 1=0")) {
                ResultSetMetaData meta = rs.getMetaData();
                check("ADD: table has 3 columns", meta.getColumnCount() == 3);
                check("ADD: new column is last", "SAL".equals(meta.getColumnName(3)));
                check("ADD: new column keeps scale", meta.getScale(3) == 2);
            }
            try (ResultSet rs = stmt.executeQuery("SELECT NAME, SAL FROM emp WHERE ID = 1")) {
                check("ADD: old data preserved", rs.next()
                    && "ALICE".equals(rs.getString(1).trim()));
                rs.getDouble(2);
                check("ADD: new column is null", rs.wasNull());
            }
            check("ADD: deleted row stays deleted",
                count(stmt, "SELECT COUNT(*) AS c FROM emp") == 2);
            try (ResultSet rs = stmt.executeQuery("SELECT RECNO() FROM emp WHERE ID = 3")) {
                check("ADD: RECNOs preserved (CAROL still record 3)",
                    rs.next() && rs.getInt(1) == 3);
            }

            // New column is writable
            stmt.executeUpdate("UPDATE emp SET SAL = 5000.50 WHERE ID = 1");
            try (ResultSet rs = stmt.executeQuery("SELECT SAL FROM emp WHERE ID = 1")) {
                check("ADD: new column accepts updates", rs.next()
                    && Math.abs(rs.getDouble(1) - 5000.50) < 0.001);
            }

            // ---- RENAME COLUMN ----
            stmt.executeUpdate("ALTER TABLE emp RENAME COLUMN SAL TO SALARY");
            try (ResultSet rs = stmt.executeQuery("SELECT SALARY FROM emp WHERE ID = 1")) {
                check("RENAME COLUMN: new name queryable", rs.next()
                    && Math.abs(rs.getDouble(1) - 5000.50) < 0.001);
            }
            boolean threw = false;
            try {
                stmt.executeQuery("SELECT SAL FROM emp");
            } catch (SQLException e) {
                threw = true;
            }
            check("RENAME COLUMN: old name gone", threw);

            // ---- ADD a MEMO column (upgrades to 0x83 + .DBT) ----
            stmt.executeUpdate("ALTER TABLE emp ADD COLUMN NOTES MEMO");
            check("ADD MEMO: .dbt created", Files.exists(dir.resolve("emp.dbt")));
            byte[] header = Files.readAllBytes(dir.resolve("emp.dbf"));
            check("ADD MEMO: version byte is 0x83", header[0] == (byte) 0x83);
            stmt.executeUpdate("UPDATE emp SET NOTES = 'memo for alice' WHERE ID = 1");
            try (ResultSet rs = stmt.executeQuery("SELECT NOTES FROM emp WHERE ID = 1")) {
                check("ADD MEMO: memo writable and readable", rs.next()
                    && "memo for alice".equals(rs.getString(1)));
            }

            // ---- DROP the MEMO column (downgrades to 0x03, .DBT removed) ----
            stmt.executeUpdate("ALTER TABLE emp DROP COLUMN NOTES");
            check("DROP MEMO: .dbt removed", !Files.exists(dir.resolve("emp.dbt")));
            header = Files.readAllBytes(dir.resolve("emp.dbf"));
            check("DROP MEMO: version byte back to 0x03", header[0] == 0x03);

            // ---- DROP COLUMN ----
            stmt.executeUpdate("ALTER TABLE emp DROP COLUMN NAME");
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM emp WHERE 1=0")) {
                check("DROP: column count now 2", rs.getMetaData().getColumnCount() == 2);
            }
            try (ResultSet rs = stmt.executeQuery("SELECT ID, SALARY FROM emp WHERE ID = 1")) {
                check("DROP: remaining data intact", rs.next()
                    && rs.getInt(1) == 1
                    && Math.abs(rs.getDouble(2) - 5000.50) < 0.001);
            }

            // ---- error cases ----
            threw = false;
            try {
                stmt.executeUpdate("ALTER TABLE emp ADD COLUMN ID NUMERIC(10)");
            } catch (SQLException e) {
                threw = true;
            }
            check("ADD duplicate column throws", threw);

            threw = false;
            try {
                stmt.executeUpdate("ALTER TABLE emp DROP COLUMN NO_SUCH");
            } catch (SQLException e) {
                threw = true;
            }
            check("DROP missing column throws", threw);

            // ---- RENAME TO ----
            stmt.executeUpdate("ALTER TABLE emp RENAME TO staff");
            check("RENAME TO: new file exists", Files.exists(dir.resolve("staff.dbf")));
            check("RENAME TO: old file gone", !Files.exists(dir.resolve("emp.dbf")));
            check("RENAME TO: data reachable under new name",
                count(stmt, "SELECT COUNT(*) AS c FROM staff") == 2);
        }

        // Everything still consistent on a fresh connection
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {
            check("fresh connection: altered table reads fine",
                count(stmt, "SELECT COUNT(*) AS c FROM staff") == 2);
        }

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
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
