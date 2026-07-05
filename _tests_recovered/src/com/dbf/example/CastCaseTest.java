package com.dbf.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/** Tests the SQL:2003 special-grammar expressions: CAST, EXTRACT, CASE. */
public class CastCaseTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("dbf-castcase-test");
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');

        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE ft (ID NUMERIC(3))");
            st.executeUpdate("INSERT INTO ft (ID) VALUES (1)");

            String sql = "SELECT "
                + "CAST('123' AS INTEGER) AS c_int, "
                + "CAST(45.9 AS INTEGER) AS c_trunc, "
                + "CAST(7 AS CHAR) AS c_char, "
                + "CAST('3.14' AS DOUBLE) AS c_dbl, "
                + "CAST('2025-06-14' AS DATE) AS c_date, "
                + "CAST(2 AS NUMERIC(10,2)) AS c_num, "
                + "EXTRACT(YEAR FROM #2025-06-14#) AS e_year, "
                + "EXTRACT(MONTH FROM #2025-06-14#) AS e_month, "
                + "EXTRACT(QUARTER FROM #2025-06-14#) AS e_q, "
                + "CASE WHEN 1=1 THEN 'a' ELSE 'b' END AS case1, "
                + "CASE WHEN 1=2 THEN 'a' WHEN 2=2 THEN 'b' ELSE 'c' END AS case2, "
                + "CASE 3 WHEN 1 THEN 'x' WHEN 3 THEN 'y' ELSE 'z' END AS case3, "
                + "CASE WHEN 1=2 THEN 'a' END AS case4, "
                + "CASE WHEN ID=1 THEN 'one' ELSE 'other' END AS case_col "
                + "FROM ft";

            try (ResultSet rs = st.executeQuery(sql)) {
                check("row present", rs.next());
                checkS("CAST('123' AS INTEGER)=123", rs.getString("c_int"), "123");
                checkS("CAST(45.9 AS INTEGER)=45", rs.getString("c_trunc"), "45");
                checkS("CAST(7 AS CHAR)=7", rs.getString("c_char"), "7");
                check("CAST('3.14' AS DOUBLE)=3.14", Math.abs(rs.getDouble("c_dbl") - 3.14) < 1e-9);
                checkS("CAST('2025-06-14' AS DATE)", rs.getString("c_date"), "2025-06-14");
                check("CAST(2 AS NUMERIC)=2.0", Math.abs(rs.getDouble("c_num") - 2.0) < 1e-9);
                checkS("EXTRACT(YEAR)=2025", rs.getString("e_year"), "2025");
                checkS("EXTRACT(MONTH)=6", rs.getString("e_month"), "6");
                checkS("EXTRACT(QUARTER)=2", rs.getString("e_q"), "2");
                checkS("CASE searched true->a", rs.getString("case1"), "a");
                checkS("CASE second branch->b", rs.getString("case2"), "b");
                checkS("CASE simple 3->y", rs.getString("case3"), "y");
                check("CASE no-else, no-match -> null", rs.getString("case4") == null);
                checkS("CASE over column->one", rs.getString("case_col"), "one");
            }
        }

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void checkS(String name, String got, String want) {
        check(name + " (got " + got + ")", want.equals(got == null ? null : got.trim()));
    }

    private static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("PASS: " + name); }
        else { failed++; System.out.println("FAIL: " + name); }
    }
}
