package com.dbf.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;

/**
 * Exercises aggregates over a join -- the shape of the real METRO "Ledger"
 * query: MAX/SUM over a TRAN LEFT JOIN MASTER, with a WHERE spanning both
 * tables (including #date# range literals), GROUP BY on base-table columns,
 * an aggregate argument that reads the joined table (MAX(AG.CUST_DESC)),
 * SUM(IIF(...)) conditionals, and ORDER BY MAX(date). Also verifies that a
 * LEFT JOIN row with no match contributes a group whose joined aggregate is
 * null. Fully self-contained on a throwaway folder.
 */
public class AggJoinTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("dbf-aggjoin-test");
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');

        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {

            stmt.executeUpdate(
                "CREATE TABLE master (C_HEAD NUMERIC(6), CUST_DESC CHAR(20), L_FLAG CHAR(1))");
            stmt.executeUpdate("INSERT INTO master (C_HEAD, CUST_DESC, L_FLAG) VALUES (100, 'CASH', 'A')");
            stmt.executeUpdate("INSERT INTO master (C_HEAD, CUST_DESC, L_FLAG) VALUES (200, 'BANK', 'A')");

            stmt.executeUpdate("CREATE TABLE tran (CO_CODE NUMERIC(6), V_TYPE CHAR(4), "
                + "DEBIT NUMERIC(1), CREDIT NUMERIC(1), A_BAL NUMERIC(12,2), D_DATE DATE)");
            // Group (CO_CODE=100, DEBIT=1): two rows -> CASH, SUM=80, MAX date 2021-06-01
            stmt.executeUpdate("INSERT INTO tran (CO_CODE, V_TYPE, DEBIT, CREDIT, A_BAL, D_DATE) "
                + "VALUES (100, 'pm', 1, 0, 50.00, '2021-01-01')");
            stmt.executeUpdate("INSERT INTO tran (CO_CODE, V_TYPE, DEBIT, CREDIT, A_BAL, D_DATE) "
                + "VALUES (100, 'pm', 1, 0, 30.00, '2021-06-01')");
            // Group (CO_CODE=200, DEBIT=0): one row -> BANK, SUM=20, receipts 0
            stmt.executeUpdate("INSERT INTO tran (CO_CODE, V_TYPE, DEBIT, CREDIT, A_BAL, D_DATE) "
                + "VALUES (200, 'rc', 0, 1, 20.00, '2022-01-01')");
            // Group (CO_CODE=999, DEBIT=1): one row, NO master match -> CUST_DESC null
            stmt.executeUpdate("INSERT INTO tran (CO_CODE, V_TYPE, DEBIT, CREDIT, A_BAL, D_DATE) "
                + "VALUES (999, 'pm', 1, 0, 7.00, '2023-01-01')");
            // Excluded by WHERE: neither debit nor credit set
            stmt.executeUpdate("INSERT INTO tran (CO_CODE, V_TYPE, DEBIT, CREDIT, A_BAL, D_DATE) "
                + "VALUES (100, 'xx', 0, 0, 999.00, '2021-02-01')");
            // Excluded by WHERE: date out of range
            stmt.executeUpdate("INSERT INTO tran (CO_CODE, V_TYPE, DEBIT, CREDIT, A_BAL, D_DATE) "
                + "VALUES (100, 'old', 1, 0, 5.00, '2010-01-01')");

            String sql =
                "SELECT MAX(AG.CUST_DESC) AS TH, SUM(A_BAL) AS AMT, "
                + "SUM(IIF(TRAN.DEBIT=1, A_BAL, 0)) AS RECEIPTS, "
                + "MAX(D_DATE) AS MAXDATE, COUNT(*) AS CNT "
                + "FROM TRAN LEFT JOIN MASTER AS AG ON TRAN.CO_CODE=AG.C_HEAD "
                + "WHERE (TRAN.DEBIT=1 OR TRAN.CREDIT=1) "
                + "AND (D_DATE>=#2020-04-01# AND D_DATE<=#2025-10-10#) "
                + "GROUP BY CO_CODE, DEBIT "
                + "ORDER BY MAX(D_DATE)";

            try (ResultSet rs = stmt.executeQuery(sql)) {
                ResultSetMetaData meta = rs.getMetaData();
                check("5 output columns", meta.getColumnCount() == 5);
                check("aggregate-over-join did not throw", true);

                // Row 1: group (100,1) -> ordered first (max date 2021-06-01)
                check("row 1 present", rs.next());
                check("row 1 TH = CASH (joined MAX)", "CASH".equals(trim(rs.getString("TH"))));
                check("row 1 AMT = 80.00", rs.getDouble("AMT") == 80.0);
                check("row 1 RECEIPTS = 80.00 (IIF debit=1)", rs.getDouble("RECEIPTS") == 80.0);
                check("row 1 MAXDATE = 2021-06-01", "2021-06-01".equals(String.valueOf(rs.getDate("MAXDATE"))));
                check("row 1 CNT = 2", rs.getInt("CNT") == 2);

                // Row 2: group (200,0) -> max date 2022-01-01
                check("row 2 present", rs.next());
                check("row 2 TH = BANK", "BANK".equals(trim(rs.getString("TH"))));
                check("row 2 AMT = 20.00", rs.getDouble("AMT") == 20.0);
                check("row 2 RECEIPTS = 0 (credit row)", rs.getDouble("RECEIPTS") == 0.0);
                check("row 2 MAXDATE = 2022-01-01", "2022-01-01".equals(String.valueOf(rs.getDate("MAXDATE"))));

                // Row 3: group (999,1) -> LEFT JOIN unmatched, CUST_DESC null
                check("row 3 present", rs.next());
                check("row 3 TH null (unmatched LEFT JOIN)", rs.getString("TH") == null);
                check("row 3 AMT = 7.00", rs.getDouble("AMT") == 7.0);
                check("row 3 RECEIPTS = 7.00", rs.getDouble("RECEIPTS") == 7.0);

                check("exactly 3 groups (2 rows filtered by WHERE)", !rs.next());
            }

            // Global aggregate (no GROUP BY) over a join: one row.
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) AS C, SUM(A_BAL) AS S, MAX(AG.CUST_DESC) AS MX "
                    + "FROM TRAN LEFT JOIN MASTER AS AG ON TRAN.CO_CODE=AG.C_HEAD "
                    + "WHERE TRAN.DEBIT=1 OR TRAN.CREDIT=1")) {
                check("global agg over join: one row", rs.next());
                // 4 qualifying rows (debit/credit set): 100/100/200/999 + the 2010 row (debit=1) = 5
                check("global COUNT = 5", rs.getInt("C") == 5);
                check("global SUM = 112.00", rs.getDouble("S") == 112.0);
                check("global MAX(CUST_DESC) = CASH", "CASH".equals(trim(rs.getString("MX"))));
                check("global agg: exactly one row", !rs.next());
            }
        }

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
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
