package com.dbf.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * getObject() must never surface java.lang.Long for values that fit an int:
 * integer results (plain NUMERIC(n,0) columns, literals, SUM/COUNT, computed
 * arithmetic) come back as java.lang.Integer. Only values too large for int
 * remain Long. Decimal columns stay Double; getString stays clean (no ".0").
 * (The METRO app crashed on Long: it handles Integer and Double only.)
 */
public class IntegerTypeTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("dbf-inttype");
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');

        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE sales (DEBIT NUMERIC(6), V_NO NUMERIC(6), "
                + "BIGV NUMERIC(15), A_BAL NUMERIC(15,2), TYPE CHAR(1))");
            st.executeUpdate("INSERT INTO sales (DEBIT,V_NO,BIGV,A_BAL,TYPE) "
                + "VALUES (41695,1130,987654321012345,372.50,'A')");

            // The exact crash shape: plain columns + literal selects.
            try (ResultSet rs = st.executeQuery(
                    "Select DEBIT,0 AS TR_CODE5,V_NO from sales where TYPE='A' AND V_NO=1130")) {
                rs.next();
                check("plain N(6,0) is Integer (got "
                    + rs.getObject("DEBIT").getClass().getSimpleName() + ")",
                    rs.getObject("DEBIT") instanceof Integer);
                check("literal 0 AS x is Integer", rs.getObject("TR_CODE5") instanceof Integer);
                check("V_NO is Integer 1130", rs.getObject("V_NO").equals(1130));
            }

            try (ResultSet rs = st.executeQuery("SELECT DEBIT, BIGV, A_BAL FROM sales")) {
                rs.next();
                check("decimal N(15,2) stays Double", rs.getObject("A_BAL") instanceof Double);
                check("value beyond int range stays Long (987654321012345)",
                    rs.getObject("BIGV").equals(987654321012345L));
                check("getString of N(6,0) clean", "41695".equals(rs.getString("DEBIT")));
                check("getInt / getLong / getDouble all work",
                    rs.getInt("DEBIT") == 41695 && rs.getLong("DEBIT") == 41695L
                        && rs.getDouble("DEBIT") == 41695.0);
            }

            // Aggregates and computed arithmetic: integral -> Integer.
            try (ResultSet rs = st.executeQuery(
                    "SELECT SUM(DEBIT) AS s, COUNT(*) AS n FROM sales")) {
                rs.next();
                check("SUM(int) is Integer", rs.getObject("s") instanceof Integer);
                check("COUNT(*) is Integer", rs.getObject("n") instanceof Integer);
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT DEBIT+1 AS d1, DEBIT+0.5 AS d2 FROM sales WHERE TYPE='A'")) {
                rs.next();
                check("computed int+int is Integer 41696", rs.getObject("d1").equals(41696));
                check("computed int+double stays Double", rs.getObject("d2") instanceof Double);
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
