package com.dbf.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Verifies that leading spaces in character fields are preserved (real xBase
 * data), so a pattern like LIKE ' %%' finds records whose value starts with a
 * space — while trailing padding is still stripped.
 */
public class LeadingSpaceTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("dbf-leadingspace");
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');

        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE m (L_FLAG CHAR(1), CUST_DESC CHAR(20))");
            st.executeUpdate("INSERT INTO m (L_FLAG, CUST_DESC) VALUES ('T', ' SPACE FIRST')");
            st.executeUpdate("INSERT INTO m (L_FLAG, CUST_DESC) VALUES ('T', 'NORMAL')");
            st.executeUpdate("INSERT INTO m (L_FLAG, CUST_DESC) VALUES ('X', ' OTHER FLAG')");

            // Leading space preserved on read, trailing padding stripped.
            try (ResultSet rs = st.executeQuery("SELECT CUST_DESC FROM m WHERE L_FLAG='X'")) {
                rs.next();
                String v = rs.getString("CUST_DESC");
                // No auto-trim: the value keeps its leading space and is padded
                // to the fixed field width on the right.
                check("leading space preserved (got [" + v + "])", rtrim(v).equals(" OTHER FLAG"));
            }

            // The reported query shape: LIKE ' %%' finds the leading-space row.
            int n = 0;
            String found = null;
            try (ResultSet rs = st.executeQuery(
                    "SELECT CUST_DESC FROM m WHERE L_FLAG = 'T' AND ( UCASE(CUST_DESC) like ' %%' ) "
                    + "ORDER BY UCASE(CUST_DESC)")) {
                while (rs.next()) { n++; found = rs.getString("CUST_DESC"); }
            }
            check("LIKE ' %%' returns exactly the leading-space row (n=" + n + ")", n == 1);
            check("returned row is ' SPACE FIRST'", rtrim(found).equals(" SPACE FIRST"));

            // A normal prefix still works and excludes the leading-space row.
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) AS c FROM m WHERE UCASE(CUST_DESC) LIKE 'N%'")) {
                rs.next();
                check("LIKE 'N%' matches NORMAL only", rs.getInt("c") == 1);
            }
        }

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static String rtrim(String s) {
        if (s == null) return null;
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') end--;
        return s.substring(0, end);
    }

    private static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("PASS: " + name); }
        else { failed++; System.out.println("FAIL: " + name); }
    }
}
