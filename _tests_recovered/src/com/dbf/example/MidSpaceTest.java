package com.dbf.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Verifies (1) character data is stored/returned EXACTLY (all spaces kept, no
 * auto-trim) and (2) the MID() function, including the app's query shape
 * UCASE(TRIM(MID(CUST_DESC,1,30)))=?.
 */
public class MidSpaceTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("dbf-midspace");
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');

        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE master (CUST_DESC CHAR(30), L_FLAG CHAR(1), C_HEAD NUMERIC(6))");
            // Insert with a leading space; field is 30 wide.
            st.executeUpdate("INSERT INTO master (CUST_DESC, L_FLAG, C_HEAD) "
                + "VALUES (' singapur', 'T', 41710)");

            // SELECT returns the value exactly as stored: leading space kept,
            // padded with trailing spaces to the 30-char field width.
            try (ResultSet rs = st.executeQuery("SELECT CUST_DESC FROM master")) {
                rs.next();
                String v = rs.getString("CUST_DESC");
                check("no auto-trim: length = field width 30 (got " + v.length() + ")", v.length() == 30);
                check("leading space preserved", v.startsWith(" singapur"));
                check("trailing spaces preserved", v.endsWith("  "));
            }

            // MID() works as SUBSTRING (1-based).
            try (ResultSet rs = st.executeQuery(
                    "SELECT MID(CUST_DESC,2,8) AS m, TRIM(MID(CUST_DESC,1,30)) AS t FROM master")) {
                rs.next();
                check("MID(CUST_DESC,2,8) = 'singapur'", "singapur".equals(rs.getString("m")));
                check("TRIM(MID(...,1,30)) = ' singapur' trimmed = 'singapur'",
                    "singapur".equals(rs.getString("t")));
            }

            // The app's exact query shape.
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT C_HEAD FROM MASTER WHERE UCASE(TRIM(MID(CUST_DESC,1,30)))=? "
                    + "AND l_flag=? AND C_HEAD>0")) {
                ps.setString(1, "SINGAPUR");
                ps.setString(2, "T");
                try (ResultSet rs = ps.executeQuery()) {
                    check("UCASE(TRIM(MID(...)))=? matches the row", rs.next());
                    check("matched C_HEAD = 41710", rs.getInt("C_HEAD") == 41710);
                }
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
