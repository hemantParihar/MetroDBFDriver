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
 * Exercises the full multi-table expression engine with a query shaped like
 * the real METRO ERP query: a self-join of MASTER (M and M1) against TRAN,
 * literal columns (0, null), scalar functions in WHERE (ISNULL) and
 * ORDER BY (UCASE), parenthesized join tree, and table aliases.
 */
public class MultiJoinTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("dbf-multijoin-test");
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');

        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {

            stmt.executeUpdate("CREATE TABLE master (C_HEAD NUMERIC(6), CUST_DESC CHAR(20), L_FLAG CHAR(1))");
            stmt.executeUpdate("INSERT INTO master (C_HEAD, CUST_DESC, L_FLAG) VALUES (100, 'CASH', 'A')");
            stmt.executeUpdate("INSERT INTO master (C_HEAD, CUST_DESC, L_FLAG) VALUES (200, 'BANK', 'A')");
            stmt.executeUpdate("INSERT INTO master (C_HEAD, CUST_DESC, L_FLAG) VALUES (300, 'SALES', 'A')");

            stmt.executeUpdate("CREATE TABLE tran (V_TYPE CHAR(4), DEBIT NUMERIC(6), "
                + "CREDIT NUMERIC(6), V_NO NUMERIC(8), D_DATE DATE)");
            // Two qualifying rows (blank date => ISNULL true, V_NO=999999, both sides matched)
            stmt.executeUpdate("INSERT INTO tran (V_TYPE, DEBIT, CREDIT, V_NO) VALUES ('pm', 200, 300, 999999)");
            stmt.executeUpdate("INSERT INTO tran (V_TYPE, DEBIT, CREDIT, V_NO) VALUES ('jv', 100, 200, 999999)");
            // Excluded: M1 side has no match (CREDIT=999) -> filtered by WHERE
            stmt.executeUpdate("INSERT INTO tran (V_TYPE, DEBIT, CREDIT, V_NO) VALUES ('rc', 100, 999, 999999)");
            // Excluded: wrong V_NO
            stmt.executeUpdate("INSERT INTO tran (V_TYPE, DEBIT, CREDIT, V_NO) VALUES ('xx', 100, 200, 5)");
            // Excluded: D_DATE present -> ISNULL false
            stmt.executeUpdate("INSERT INTO tran (V_TYPE, DEBIT, CREDIT, V_NO, D_DATE) "
                + "VALUES ('dt', 100, 200, 999999, '2026-01-01')");

            String sql =
                "SELECT T.V_TYPE, T.DEBIT, T.CREDIT, 0 AS CO_CODE, "
                + "M.CUST_DESC AS M_DESC, M.C_HEAD AS M_C_HEAD, null AS M_SUSP, "
                + "M1.CUST_DESC AS M1_DESC, M1.C_HEAD AS M1_C_HEAD "
                + "FROM ((TRAN T LEFT JOIN MASTER M ON T.DEBIT=M.C_HEAD) "
                + "LEFT JOIN MASTER M1 ON T.CREDIT=M1.C_HEAD) "
                + "WHERE ISNULL(D_DATE) AND V_NO=999999 "
                + "AND (M.C_HEAD>0 OR M.L_FLAG='Z') AND (M1.C_HEAD>0 OR M1.L_FLAG='Z') "
                + "ORDER BY UCASE(V_TYPE)";

            try (ResultSet rs = stmt.executeQuery(sql)) {
                ResultSetMetaData meta = rs.getMetaData();
                check("9 output columns", meta.getColumnCount() == 9);
                check("literal column named CO_CODE", "CO_CODE".equals(meta.getColumnLabel(4)));
                check("aliased join column M_DESC", "M_DESC".equals(meta.getColumnLabel(5)));
                check("self-join column M1_DESC", "M1_DESC".equals(meta.getColumnLabel(8)));

                // Row 1: JV (ordered first by UCASE), DEBIT 100->CASH, CREDIT 200->BANK
                check("row 1 present", rs.next());
                check("row 1 V_TYPE jv", "jv".equals(rs.getString("V_TYPE").trim()));
                check("row 1 CO_CODE literal 0", rs.getInt("CO_CODE") == 0);
                check("row 1 M_DESC = CASH (DEBIT 100)", "CASH".equals(rs.getString("M_DESC").trim()));
                check("row 1 M1_DESC = BANK (CREDIT 200)", "BANK".equals(rs.getString("M1_DESC").trim()));
                check("row 1 null literal column", rs.getString("M_SUSP") == null);

                // Row 2: PM, DEBIT 200->BANK, CREDIT 300->SALES
                check("row 2 present", rs.next());
                check("row 2 V_TYPE pm (ordered after jv)", "pm".equals(rs.getString("V_TYPE").trim()));
                check("row 2 M_DESC = BANK (DEBIT 200)", "BANK".equals(rs.getString("M_DESC").trim()));
                check("row 2 M1_DESC = SALES (CREDIT 300)", "SALES".equals(rs.getString("M1_DESC").trim()));

                check("exactly 2 rows (others filtered)", !rs.next());
            }

            // A plain 2-table inner join with alias still works
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT T.V_TYPE, M.CUST_DESC FROM TRAN T JOIN MASTER M ON T.DEBIT=M.C_HEAD "
                    + "WHERE V_NO=999999")) {
                int n = 0;
                while (rs.next()) n++;
                check("2-table inner join returns 4 matched rows (DEBIT all =100/200/100/100)", n == 4);
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
