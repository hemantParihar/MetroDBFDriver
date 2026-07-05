package com.dbf.example;

import com.dbf.jdbc.dbf.DbfType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;

/**
 * Locks down the centralized DBF-type -> JDBC mapping (com.dbf.jdbc.dbf.DbfType)
 * and proves the headline guarantee: a column reports the SAME JDBC type no
 * matter which query path produces it (plain scan vs. the expression/projection
 * pipeline used for computed columns and joins). If someone changes the mapping
 * in the future, these checks fail loudly in one place instead of letting the
 * paths drift apart silently.
 */
public class DbfTypeTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        // 1. The single source of truth itself.
        check("C -> CHAR", DbfType.sqlType('C') == Types.CHAR);
        check("N -> NUMERIC", DbfType.sqlType('N') == Types.NUMERIC);
        check("F -> NUMERIC", DbfType.sqlType('F') == Types.NUMERIC);
        check("O -> NUMERIC", DbfType.sqlType('O') == Types.NUMERIC);
        check("I -> NUMERIC", DbfType.sqlType('I') == Types.NUMERIC);
        check("D -> DATE", DbfType.sqlType('D') == Types.DATE);
        check("L -> BOOLEAN", DbfType.sqlType('L') == Types.BOOLEAN);
        check("M -> LONGVARCHAR (Jet Memo)", DbfType.sqlType('M') == Types.LONGVARCHAR);
        check("M column class is String", "java.lang.String".equals(DbfType.javaClassName('M')));
        check("unknown -> CHAR (default)", DbfType.sqlType('X') == Types.CHAR);
        check("lowercase 'n' resolves like 'N'", DbfType.sqlType('n') == Types.NUMERIC);

        check("N column class is Double", "java.lang.Double".equals(DbfType.javaClassName('N')));
        check("C column class is String", "java.lang.String".equals(DbfType.javaClassName('C')));

        // 2. Same type via two different query paths.
        Path dir = Files.createTempDirectory("dbf-type-test");
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');

        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {

            stmt.executeUpdate(
                "CREATE TABLE t (NAME CHAR(10), AMT NUMERIC(8,2), D_DATE DATE)");
            stmt.executeUpdate("INSERT INTO t (NAME, AMT, D_DATE) VALUES ('x', 12.50, '2024-01-01')");

            // Plain scan path.
            int plainName, plainAmt, plainDate;
            try (ResultSet rs = stmt.executeQuery("SELECT NAME, AMT, D_DATE FROM t")) {
                ResultSetMetaData m = rs.getMetaData();
                plainName = m.getColumnType(1);
                plainAmt = m.getColumnType(2);
                plainDate = m.getColumnType(3);
                check("plain: NAME is CHAR", plainName == Types.CHAR);
                check("plain: AMT is NUMERIC", plainAmt == Types.NUMERIC);
                check("plain: D_DATE is DATE", plainDate == Types.DATE);
                check("plain: NAME class String",
                    "java.lang.String".equals(m.getColumnClassName(1)));
                check("plain: AMT class Double",
                    "java.lang.Double".equals(m.getColumnClassName(2)));
            }

            // Expression/projection path: adding a computed column forces the
            // pipeline that historically reported VARCHAR/DOUBLE instead.
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT NAME, AMT, D_DATE, AMT*2 AS DOUBLED FROM t")) {
                ResultSetMetaData m = rs.getMetaData();
                check("computed-path: NAME type matches plain", m.getColumnType(1) == plainName);
                check("computed-path: AMT type matches plain", m.getColumnType(2) == plainAmt);
                check("computed-path: D_DATE type matches plain", m.getColumnType(3) == plainDate);
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
