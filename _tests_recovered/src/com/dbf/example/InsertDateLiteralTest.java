package com.dbf.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * INSERT/UPDATE VALUES must accept MS-Access #...# date literals (the SELECT
 * lexer already did, but DML value parsing did not -- so an INSERT with
 * #2025-10-10# silently failed date validation). Also checks a memo column
 * inserts alongside a date, since the motivating SALES table has both.
 */
public class InsertDateLiteralTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("dbf-datelit");
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');

        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE t (ID NUMERIC(4), D_DATE DATE, NOTE MEMO, AMT NUMERIC(10,2))");

            // ISO #yyyy-MM-dd# + empty memo (the SALES case).
            int n = st.executeUpdate("INSERT INTO t (`ID`,`D_DATE`,`NOTE`,`AMT`) "
                + "VALUES(1,#2025-10-10#,'',5035.06)");
            check("INSERT with #date# returns 1 (was silently failing)", n == 1);

            // Access slash form #MM/dd/yyyy# + non-empty memo.
            st.executeUpdate("INSERT INTO t (ID,D_DATE,NOTE,AMT) "
                + "VALUES(2,#12/31/2024#,'packing 12+23+45',99.5)");

            try (ResultSet rs = st.executeQuery("SELECT ID,D_DATE,NOTE,AMT FROM t ORDER BY ID")) {
                rs.next();
                check("row1 D_DATE = 2025-10-10", "2025-10-10".equals(rs.getString("D_DATE")));
                check("row1 NOTE empty memo", rs.getString("NOTE") == null
                    || rs.getString("NOTE").isEmpty());
                check("row1 AMT = 5035.06", Math.abs(rs.getDouble("AMT") - 5035.06) < 1e-9);
                rs.next();
                check("row2 #MM/dd/yyyy# -> 2024-12-31", "2024-12-31".equals(rs.getString("D_DATE")));
                check("row2 memo round-trips", "packing 12+23+45".equals(rs.getString("NOTE").trim()));
            }

            // UPDATE with #date# literal too.
            st.executeUpdate("UPDATE t SET D_DATE=#2026-01-15# WHERE ID=1");
            try (ResultSet rs = st.executeQuery("SELECT D_DATE FROM t WHERE ID=1")) {
                rs.next();
                check("UPDATE SET D_DATE=#2026-01-15#", "2026-01-15".equals(rs.getString(1)));
            }
        }

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("PASS: " + name); }
        else { failed++; System.out.println("FAIL: " + name); }
    }
}
