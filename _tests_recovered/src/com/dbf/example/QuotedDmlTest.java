package com.dbf.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Verifies backtick / bracket quoted identifiers in DML (INSERT/UPDATE/DELETE),
 * mirroring the app's UPDATE MASTER SET `cust_desc`=... WHERE c_head=... shape.
 */
public class QuotedDmlTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("dbf-quoted-dml");
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');

        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE master (C_HEAD NUMERIC(6), CUST_DESC CHAR(30), "
                + "M_HEAD NUMERIC(4), O_BAL NUMERIC(12,2), Y_N CHAR(1))");

            // INSERT with backtick-quoted table + columns
            st.executeUpdate("INSERT INTO `master` (`C_HEAD`, `CUST_DESC`, `M_HEAD`) "
                + "VALUES (6918, 'OLD NAME', 100)");
            check("insert with backtick identifiers", count(st, "SELECT COUNT(*) AS c FROM master") == 1);

            // The reported UPDATE shape: backtick SET columns, mixed value types
            st.executeUpdate("UPDATE MASTER SET `cust_desc`='   -EME NEAR11', "
                + "`m_head`=241, `o_bal`=0.0, `y_n`='' WHERE c_head = 6918");

            try (ResultSet rs = st.executeQuery("SELECT CUST_DESC, M_HEAD FROM master WHERE C_HEAD=6918")) {
                rs.next();
                String desc = rs.getString("CUST_DESC");
                // No auto-trim: leading spaces kept, right-padded to field width.
                check("backtick UPDATE applied cust_desc (leading space kept, got [" + desc + "])",
                    desc != null && rtrim(desc).equals("   -EME NEAR11"));
                check("backtick UPDATE applied m_head=241", rs.getInt("M_HEAD") == 241);
            }

            // Bracket-quoted column in UPDATE
            st.executeUpdate("UPDATE master SET [Y_N] = 'Y' WHERE [C_HEAD] = 6918");
            try (ResultSet rs = st.executeQuery("SELECT Y_N FROM master WHERE C_HEAD=6918")) {
                rs.next();
                check("bracket UPDATE applied y_n=Y", "Y".equals(rs.getString("Y_N").trim()));
            }

            // DELETE with backtick table
            st.executeUpdate("DELETE FROM `master` WHERE `c_head` = 6918");
            check("backtick DELETE removed the row",
                count(st, "SELECT COUNT(*) AS c FROM master WHERE C_HEAD=6918") == 0);
        }

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static long count(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) { rs.next(); return rs.getLong(1); }
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
