package com.dbf.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * A memo column must fetch correctly via getString, getObject AND getClob, on
 * both the plain path and through a join (Grace hash join spills rows via
 * RowSerializer, which must preserve the memo string).
 */
public class MemoFetchTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("dbf-memofetch");
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');

        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE sales (ROW_ID NUMERIC(6), ST NUMERIC(3), TR_WT MEMO)");
            st.executeUpdate("CREATE TABLE tax (TAX_CODE NUMERIC(3), FORM CHAR(4))");
            st.executeUpdate("INSERT INTO sales (ROW_ID,ST,TR_WT) VALUES (1,10,'  1.234  5.678')");
            st.executeUpdate("INSERT INTO sales (ROW_ID,ST,TR_WT) VALUES (2,10,'')");
            st.executeUpdate("INSERT INTO sales (ROW_ID,ST,TR_WT) VALUES (3,20,'9.9 8.8 7.7')");
            st.executeUpdate("INSERT INTO tax (TAX_CODE,FORM) VALUES (10,'C')");
            st.executeUpdate("INSERT INTO tax (TAX_CODE,FORM) VALUES (20,'H')");

            // Plain path: getString / getObject / getClob.
            try (ResultSet rs = st.executeQuery("SELECT TR_WT FROM sales WHERE ROW_ID=1")) {
                rs.next();
                check("plain getString preserves spaces", "  1.234  5.678".equals(rs.getString(1)));
                check("plain getObject == string", "  1.234  5.678".equals(rs.getObject(1)));
                Clob cl = rs.getClob(1);
                check("plain getClob non-null", cl != null);
                check("plain getClob text", cl != null
                    && "  1.234  5.678".equals(cl.getSubString(1, (int) cl.length())));
            }

            // Join path (Grace hash join + RowSerializer spill round-trip).
            int got1 = 0, got3 = 0, empty2 = 0;
            try (ResultSet rs = st.executeQuery(
                    "SELECT SALES.ROW_ID, SALES.TR_WT FROM sales SALES "
                  + "LEFT JOIN tax TAX ON SALES.ST=TAX.TAX_CODE ORDER BY SALES.ROW_ID")) {
                while (rs.next()) {
                    int id = rs.getInt("ROW_ID");
                    String v = rs.getString("TR_WT");
                    if (id == 1 && "  1.234  5.678".equals(v)) got1++;
                    if (id == 3 && "9.9 8.8 7.7".equals(v)) got3++;
                    if (id == 2 && (v == null || v.isEmpty())) empty2++;
                }
            }
            check("join path row1 memo correct", got1 == 1);
            check("join path row3 memo correct", got3 == 1);
            check("join path row2 empty memo correct", empty2 == 1);
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
