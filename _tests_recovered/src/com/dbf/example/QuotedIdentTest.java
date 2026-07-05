package com.dbf.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Verifies backtick / bracket quoted identifiers and the exact failing query
 * shape: SELECT * FROM `MASTER` WHERE (...) AND C_HEAD IN (?).
 */
public class QuotedIdentTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("dbf-quoted-test");
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');

        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE master (C_HEAD NUMERIC(6), L_FLAG CHAR(1), CUST_DESC CHAR(20))");
            st.executeUpdate("INSERT INTO master (C_HEAD, L_FLAG, CUST_DESC) VALUES (100, 'X', 'CASH')");
            st.executeUpdate("INSERT INTO master (C_HEAD, L_FLAG, CUST_DESC) VALUES (200, 'X', 'BANK')");
            st.executeUpdate("INSERT INTO master (C_HEAD, L_FLAG, CUST_DESC) VALUES (0,   'Z', 'SUSPENSE')");

            // The exact shape from the user's repository.
            String sql = "SELECT * FROM `MASTER` WHERE ( c_head>0 or L_FLAG='Z') AND C_HEAD IN (?)";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, 100);
                try (ResultSet rs = ps.executeQuery()) {
                    check("backtick table + IN(?) parses & runs", rs.next());
                    check("matched C_HEAD=100", rs.getInt("C_HEAD") == 100);
                    check("only one row", !rs.next());
                }
            }

            // Bracket-quoted table and column.
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT [C_HEAD] FROM [master] WHERE L_FLAG = 'Z'")) {
                check("bracket-quoted table/column parses", rs.next());
                check("Z row has C_HEAD 0", rs.getInt(1) == 0);
            }

            // Backtick-quoted column in WHERE/ORDER BY.
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                     "SELECT `C_HEAD` FROM `MASTER` WHERE `L_FLAG` = 'X' ORDER BY `C_HEAD`")) {
                check("backtick column row 1 = 100", rs.next() && rs.getInt(1) == 100);
                check("backtick column row 2 = 200", rs.next() && rs.getInt(1) == 200);
            }

            // Quoted column alias after AS (the sales-register failure: AS 'fun..._Tax%').
            // The alias contains a '%' which is not a legal bare identifier.
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                     "SELECT MAX(C_HEAD) AS 'fun000_005_8_Tax%' FROM master")) {
                check("quoted alias query runs", rs.next());
                check("quoted alias preserved in metadata",
                    "fun000_005_8_Tax%".equals(rs.getMetaData().getColumnLabel(1)));
                check("quoted alias retrievable by name", rs.getInt("fun000_005_8_Tax%") == 200);
            }

            // Quoted alias on a plain column reference too.
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                     "SELECT CUST_DESC AS 'Account Name' FROM master WHERE C_HEAD = 100")) {
                check("quoted alias with space runs", rs.next());
                check("quoted alias with space in metadata",
                    "Account Name".equals(rs.getMetaData().getColumnLabel(1)));
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
