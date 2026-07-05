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
 * Verifies aggregates over expressions (MAX/SUM of functions, IIF),
 * YEAR/MONTH date functions, MS-Access #date# literals, and constant
 * select items mixed into a GROUP BY query.
 */
public class AggExprTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("dbf-aggexpr-test");
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');

        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {

            stmt.executeUpdate("CREATE TABLE tran (DEBIT NUMERIC(2), CREDIT NUMERIC(2), "
                + "CODE_INT NUMERIC(2), A_BAL NUMERIC(10,2), INT_AMT NUMERIC(10,2), D_DATE DATE)");
            // group (DEBIT=1,CREDIT=0): two rows in 2025, one in 2024
            stmt.executeUpdate("INSERT INTO tran (DEBIT,CREDIT,CODE_INT,A_BAL,INT_AMT,D_DATE) VALUES (1,0,2,100.00,5.00,'2025-03-15')");
            stmt.executeUpdate("INSERT INTO tran (DEBIT,CREDIT,CODE_INT,A_BAL,INT_AMT,D_DATE) VALUES (1,0,2,200.00,7.00,'2025-07-20')");
            stmt.executeUpdate("INSERT INTO tran (DEBIT,CREDIT,CODE_INT,A_BAL,INT_AMT,D_DATE) VALUES (1,0,2,50.00,1.00,'2024-12-01')");
            // group (DEBIT=0,CREDIT=1): one row
            stmt.executeUpdate("INSERT INTO tran (DEBIT,CREDIT,CODE_INT,A_BAL,INT_AMT,D_DATE) VALUES (0,1,-2,300.00,9.00,'2025-05-10')");
            // excluded by WHERE (blank date)
            stmt.executeUpdate("INSERT INTO tran (DEBIT,CREDIT,CODE_INT,A_BAL,INT_AMT) VALUES (1,0,2,999.00,9.00)");
            // excluded by date filter (>= 2025-04-01 boundary uses < so this 2025-09 stays; use a future one excluded)
            stmt.executeUpdate("INSERT INTO tran (DEBIT,CREDIT,CODE_INT,A_BAL,INT_AMT,D_DATE) VALUES (1,0,2,5.00,5.00,'2030-01-01')");

            String sql =
                "SELECT MAX(STR(YEAR(D_DATE))+'-'+STR(MONTH(D_DATE))) AS YM, "
                + "MAX(D_DATE) AS MAXD, MAX(DEBIT) AS MD, SUM(INT_AMT) AS SI, "
                + "SUM(A_BAL) AS SB, '' AS PLACE, 0 AS BAL, "
                + "SUM(IIF(DEBIT=1, A_BAL+INT_AMT, 0)) AS RECEIPTS "
                + "FROM tran "
                + "WHERE (DEBIT=1 OR CREDIT=1) AND not isnull(D_DATE) "
                + "AND DEBIT<>0 OR CREDIT<>0 "
                + "GROUP BY DEBIT, CREDIT, CODE_INT";

            // Simpler, deterministic version of the ERP shape
            sql = "SELECT MAX(STR(YEAR(D_DATE))+'-'+STR(MONTH(D_DATE))) AS YM, "
                + "MAX(D_DATE) AS MAXD, SUM(INT_AMT) AS SI, SUM(A_BAL) AS SB, "
                + "'' AS PLACE, 0 AS BAL, "
                + "SUM(IIF(DEBIT=1, A_BAL+INT_AMT, 0)) AS RECEIPTS, "
                + "MAX(DEBIT) AS MD, MAX(CREDIT) AS MC "
                + "FROM tran "
                + "WHERE not isnull(D_DATE) AND D_DATE < #2026-01-01# "
                + "GROUP BY DEBIT, CREDIT, CODE_INT";

            try (ResultSet rs = stmt.executeQuery(sql)) {
                ResultSetMetaData m = rs.getMetaData();
                check("9 output columns in SELECT order", m.getColumnCount() == 9);
                check("col 1 label YM", "YM".equalsIgnoreCase(m.getColumnLabel(1)));
                check("col 5 label PLACE (constant)", "PLACE".equalsIgnoreCase(m.getColumnLabel(5)));
                check("col 6 label BAL (constant)", "BAL".equalsIgnoreCase(m.getColumnLabel(6)));

                boolean sawDebitGroup = false;
                boolean sawCreditGroup = false;
                int groups = 0;
                while (rs.next()) {
                    groups++;
                    double md = rs.getDouble("MD");
                    double mc = rs.getDouble("MC");
                    check("PLACE is empty string", "".equals(rs.getString("PLACE")));
                    check("BAL is 0", rs.getInt("BAL") == 0);

                    if (md == 1 && mc == 0) {
                        sawDebitGroup = true;
                        // 2 in-range rows (2025-03, 2025-07); 2024-12 also < 2026 -> 3 rows
                        // INT_AMT: 5+7+1=13 ; A_BAL 100+200+50=350
                        check("debit-group SUM(INT_AMT)=13", Math.abs(rs.getDouble("SI") - 13.0) < 0.01);
                        check("debit-group SUM(A_BAL)=350", Math.abs(rs.getDouble("SB") - 350.0) < 0.01);
                        // RECEIPTS = sum of (A_BAL+INT_AMT) for debit rows = 105+207+51 = 363
                        check("debit-group RECEIPTS=363", Math.abs(rs.getDouble("RECEIPTS") - 363.0) < 0.01);
                        // MAX(D_DATE) = 2025-07-20
                        check("debit-group MAX(D_DATE)=2025-07-20",
                            "2025-07-20".equals(rs.getDate("MAXD").toString()));
                        // YM = MAX("2025-3","2025-7","2024-12") lexicographic = "2025-7"
                        check("debit-group YM = 2025-7", "2025-7".equals(rs.getString("YM")));
                    } else if (md == 0 && mc == 1) {
                        sawCreditGroup = true;
                        check("credit-group SUM(INT_AMT)=9", Math.abs(rs.getDouble("SI") - 9.0) < 0.01);
                        check("credit-group RECEIPTS=0 (no debit rows)",
                            Math.abs(rs.getDouble("RECEIPTS")) < 0.01);
                    }
                }
                check("two groups returned", groups == 2);
                check("saw debit group", sawDebitGroup);
                check("saw credit group", sawCreditGroup);
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
