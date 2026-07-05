package com.dbf.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Verifies INSERT / UPDATE / DELETE and header rewriting against a
 * throwaway copy of a DBF file. Never touches the original data.
 */
public class WriteVerifyTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path tempDir = Files.createTempDirectory("dbf-write-test");
        Path source = Paths.get("E:/METRO/PA25/AREA.DBF");
        Files.copy(source, tempDir.resolve("area.dbf"), StandardCopyOption.REPLACE_EXISTING);

        String url = "jdbc:dbf:" + tempDir.toString().replace('\\', '/');

        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {

            // Discover a character column wide enough for the test value
            String charCol = null;
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM area WHERE 1=0")) {
                ResultSetMetaData meta = rs.getMetaData();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    String t = meta.getColumnTypeName(i);
                    if (("CHAR".equals(t) || "VARCHAR".equals(t))
                            && meta.getPrecision(i) >= 8) {
                        charCol = meta.getColumnName(i);
                        break;
                    }
                }
            }
            check("found a character column to test with", charCol != null);

            long before = count(stmt, "SELECT COUNT(*) AS c FROM area");

            // INSERT
            int inserted = stmt.executeUpdate(
                "INSERT INTO area (" + charCol + ") VALUES ('ZZTEST')");
            check("INSERT reports 1 row", inserted == 1);

            long afterInsert = count(stmt, "SELECT COUNT(*) AS c FROM area");
            check("COUNT increases by 1 after INSERT (" + before + " -> " + afterInsert + ")",
                afterInsert == before + 1);

            long found = count(stmt,
                "SELECT COUNT(*) AS c FROM area WHERE TRIM(" + charCol + ") = 'ZZTEST'");
            check("inserted row is queryable", found == 1);

            // UPDATE
            int updated = stmt.executeUpdate(
                "UPDATE area SET " + charCol + " = 'ZZTEST2' WHERE TRIM(" + charCol + ") = 'ZZTEST'");
            check("UPDATE reports 1 row", updated == 1);
            long foundUpdated = count(stmt,
                "SELECT COUNT(*) AS c FROM area WHERE TRIM(" + charCol + ") = 'ZZTEST2'");
            check("updated value is queryable", foundUpdated == 1);

            // Regression: leading whitespace must not corrupt the table name.
            // Previously the hard-coded substring(6, ...) parse turned
            // " UPDATE area ..." into table "a area" and failed with a
            // FileNotFoundException on the wrong path.
            int updatedWs = stmt.executeUpdate(
                "   UPDATE area SET " + charCol + " = 'ZZTEST' WHERE TRIM(" + charCol + ") = 'ZZTEST2'");
            check("UPDATE with leading whitespace reports 1 row", updatedWs == 1);
            long foundWs = count(stmt,
                "SELECT COUNT(*) AS c FROM area WHERE TRIM(" + charCol + ") = 'ZZTEST'");
            check("leading-whitespace UPDATE applied", foundWs == 1);

            // A string value containing the word WHERE must not be mistaken
            // for the clause keyword by the top-level scanner.
            int updatedLit = stmt.executeUpdate(
                "UPDATE area SET " + charCol + " = 'WHERE' WHERE TRIM(" + charCol + ") = 'ZZTEST'");
            check("UPDATE with WHERE inside string literal reports 1 row", updatedLit == 1);
            long foundLit = count(stmt,
                "SELECT COUNT(*) AS c FROM area WHERE TRIM(" + charCol + ") = 'WHERE'");
            check("value with literal WHERE applied", foundLit == 1);

            // Re-update back so the DELETE below still matches.
            stmt.executeUpdate(
                "UPDATE area SET " + charCol + " = 'ZZTEST2' WHERE TRIM(" + charCol + ") = 'WHERE'");

            // DELETE
            int deleted = stmt.executeUpdate(
                "DELETE FROM area WHERE TRIM(" + charCol + ") = 'ZZTEST2'");
            check("DELETE reports 1 row", deleted == 1);
            long afterDelete = count(stmt, "SELECT COUNT(*) AS c FROM area");
            check("COUNT back to original after DELETE (" + afterDelete + ")",
                afterDelete == before);

            // File is still structurally valid: full scan completes
            long scanned = 0;
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM area")) {
                while (rs.next()) scanned++;
            }
            check("full scan after writes succeeds (" + scanned + " rows)", scanned == before);
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
