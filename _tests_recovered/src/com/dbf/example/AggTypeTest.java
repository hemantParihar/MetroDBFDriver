package com.dbf.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

/**
 * Verifies ResultSetMetaData reports correct JDBC types for aggregate
 * output columns: COUNT/SUM/AVG numeric, MIN/MAX take the argument type
 * (date stays DATE, char stays VARCHAR), and constants typed by literal.
 */
public class AggTypeTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("dbf-aggtype-test");
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');

        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {

            stmt.executeUpdate("CREATE TABLE t (GRP CHAR(2), AMT NUMERIC(10,2), "
                + "NAME CHAR(20), D DATE)");
            stmt.executeUpdate("INSERT INTO t (GRP, AMT, NAME, D) VALUES ('A', 100.50, 'ALICE', '2025-03-01')");
            stmt.executeUpdate("INSERT INTO t (GRP, AMT, NAME, D) VALUES ('A', 200.00, 'BOB', '2025-07-15')");
            stmt.executeUpdate("INSERT INTO t (GRP, AMT, NAME, D) VALUES ('B', 50.00, 'CAROL', '2025-01-10')");

            String sql = "SELECT GRP, COUNT(*) AS cnt, SUM(AMT) AS total, AVG(AMT) AS average, "
                + "MIN(AMT) AS lo, MAX(D) AS maxdate, MAX(NAME) AS maxname, "
                + "0 AS zero, '' AS blank, "
                + "MAX(STR(YEAR(D))+'-'+STR(MONTH(D))) AS ym "
                + "FROM t GROUP BY GRP";

            try (ResultSet rs = stmt.executeQuery(sql)) {
                ResultSetMetaData m = rs.getMetaData();
                check("10 columns", m.getColumnCount() == 10);

                // Driver maps DBF 'C' -> CHAR and 'N' -> NUMERIC (DBF-accurate)
                checkType(m, 1, "GRP (grouped char)", Types.CHAR);
                checkType(m, 2, "COUNT(*)", Types.NUMERIC);
                checkType(m, 3, "SUM(AMT)", Types.NUMERIC);
                checkType(m, 4, "AVG(AMT)", Types.NUMERIC);
                checkType(m, 5, "MIN(AMT) -> numeric", Types.NUMERIC);
                checkType(m, 6, "MAX(D) -> DATE", Types.DATE);
                checkType(m, 7, "MAX(NAME) -> CHAR", Types.CHAR);
                checkType(m, 8, "0 literal -> numeric", Types.NUMERIC);
                checkType(m, 9, "'' literal -> CHAR", Types.CHAR);
                checkType(m, 10, "MAX(STR(...)+...) -> CHAR", Types.CHAR);

                // And the values still read correctly through the typed getters
                check("first row reads", rs.next());
                check("COUNT is numeric value", rs.getInt("cnt") > 0);
                check("MAX(D) reads as Date", rs.getDate("maxdate") != null);
                check("MAX(NAME) reads as String", rs.getString("maxname") != null);
                check("typeName for COUNT is numeric-ish",
                    "NUMERIC".equals(m.getColumnTypeName(2)) || "DOUBLE".equals(m.getColumnTypeName(2)));
                check("typeName for MAX(D) is DATE", "DATE".equals(m.getColumnTypeName(6)));
            }
        }

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void checkType(ResultSetMetaData m, int col, String name, int expected)
            throws SQLException {
        int actual = m.getColumnType(col);
        if (actual == expected) {
            passed++;
            System.out.println("PASS: col " + col + " " + name + " -> " + typeName(actual));
        } else {
            failed++;
            System.out.println("FAIL: col " + col + " " + name + " expected " + typeName(expected)
                + " got " + typeName(actual));
        }
    }

    private static String typeName(int t) {
        switch (t) {
            case Types.DOUBLE: return "DOUBLE";
            case Types.NUMERIC: return "NUMERIC";
            case Types.INTEGER: return "INTEGER";
            case Types.CHAR: return "CHAR";
            case Types.VARCHAR: return "VARCHAR";
            case Types.DATE: return "DATE";
            case Types.BOOLEAN: return "BOOLEAN";
            case Types.OTHER: return "OTHER";
            default: return "type#" + t;
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
