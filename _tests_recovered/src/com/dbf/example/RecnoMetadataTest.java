package com.dbf.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;

/**
 * Reproduces the ArrayIndexOutOfBoundsException(-1) crash when calling
 * metadata accessors on the RECNO() pseudo-column, and verifies that
 * every ResultSetMetaData accessor now works for it.
 */
public class RecnoMetadataTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("dbf-recno-meta-test");
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');

        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {

            stmt.executeUpdate("CREATE TABLE master (ID NUMERIC(8), NAME CHAR(20), BAL NUMERIC(12,2))");
            stmt.executeUpdate("INSERT INTO master (ID, NAME, BAL) VALUES (1, 'ALICE', 100.50)");

            // The exact failing query from the bug report
            try (ResultSet rs = stmt.executeQuery("SELECT RECNO(), * FROM master")) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                check("4 columns (RECNO + 3 fields)", colCount == 4);

                // Interrogate EVERY accessor for EVERY column - this is what crashed
                boolean noException = true;
                try {
                    for (int i = 1; i <= colCount; i++) {
                        meta.getColumnName(i);
                        meta.getColumnLabel(i);
                        meta.getColumnType(i);
                        meta.getColumnTypeName(i);
                        meta.getColumnClassName(i);
                        meta.getColumnDisplaySize(i);
                        meta.getPrecision(i);
                        meta.getScale(i);
                        meta.isCaseSensitive(i);
                        meta.isSigned(i);
                        meta.isNullable(i);
                    }
                } catch (Exception e) {
                    noException = false;
                    System.out.println("  threw: " + e);
                }
                check("all metadata accessors work on every column (incl. RECNO)", noException);

                // RECNO column (1) should describe an integer
                check("RECNO name", "RECNO".equals(meta.getColumnName(1)));
                check("RECNO type is INTEGER", meta.getColumnType(1) == Types.INTEGER);
                check("RECNO type name", "INTEGER".equals(meta.getColumnTypeName(1)));
                check("RECNO class is Integer", "java.lang.Integer".equals(meta.getColumnClassName(1)));
                check("RECNO display size sane (>0)", meta.getColumnDisplaySize(1) > 0);
                check("RECNO precision sane (>0)", meta.getPrecision(1) > 0);
                check("RECNO scale is 0", meta.getScale(1) == 0);
                check("RECNO is signed", meta.isSigned(1));
                check("RECNO is not case-sensitive", !meta.isCaseSensitive(1));

                // Real columns still correct
                check("col 2 is ID (numeric)", meta.getColumnType(2) == Types.NUMERIC);
                check("BAL keeps scale 2", meta.getScale(4) == 2);

                // Data still reads
                check("row reads", rs.next());
                check("RECNO value is 1", rs.getInt(1) == 1);
                check("NAME reads", "ALICE".equals(rs.getString("NAME").trim()));
            }

            // RECNO with an alias must also work end-to-end
            try (ResultSet rs = stmt.executeQuery("SELECT RECNO() AS RN, NAME FROM master")) {
                ResultSetMetaData meta = rs.getMetaData();
                check("aliased RECNO label", "RN".equals(meta.getColumnLabel(1)));
                check("aliased RECNO display size", meta.getColumnDisplaySize(1) > 0);
                check("aliased RECNO reads", rs.next() && rs.getInt("RN") == 1);
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
