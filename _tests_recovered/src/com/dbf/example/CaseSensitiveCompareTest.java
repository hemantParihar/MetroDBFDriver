package com.dbf.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * String comparison and LIKE are case-SENSITIVE (binary ASCII), matching
 * xBase/Clipper and the dBASE ODBC engine. The motivating bug: a sales-register
 * filter TYPE>='A' AND TYPE<='Z' must EXCLUDE lowercase 'i' (ASCII 105 > 'Z'=90),
 * which case-insensitive comparison wrongly admitted.
 */
public class CaseSensitiveCompareTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("dbf-case");
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');

        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE t (TYPE CHAR(1), V NUMERIC(5))");
            st.executeUpdate("INSERT INTO t (TYPE,V) VALUES ('A',1)");
            st.executeUpdate("INSERT INTO t (TYPE,V) VALUES ('Z',2)");
            st.executeUpdate("INSERT INTO t (TYPE,V) VALUES ('i',3)"); // lowercase, ascii 105
            st.executeUpdate("INSERT INTO t (TYPE,V) VALUES ('a',4)"); // lowercase, ascii 97

            // Range excludes lowercase letters (the exact bug shape).
            check("TYPE>='A' AND TYPE<='Z' -> only A,Z (V 1,2)",
                ints(st, "SELECT V FROM t WHERE TYPE>='A' AND TYPE<='Z' ORDER BY V")
                    .equals(List.of(1, 2)));

            // Equality is case-sensitive.
            check("TYPE='i' matches only the lowercase row",
                ints(st, "SELECT V FROM t WHERE TYPE='i'").equals(List.of(3)));
            check("TYPE='I' (uppercase) matches nothing",
                ints(st, "SELECT V FROM t WHERE TYPE='I'").isEmpty());

            // UCASE() restores case-insensitive behavior when wanted.
            check("UCASE(TYPE)='I' matches the 'i' row",
                ints(st, "SELECT V FROM t WHERE UCASE(TYPE)='I'").equals(List.of(3)));

            // LIKE is case-sensitive too.
            st.executeUpdate("CREATE TABLE n (NAME CHAR(10))");
            st.executeUpdate("INSERT INTO n (NAME) VALUES ('Cash')");
            st.executeUpdate("INSERT INTO n (NAME) VALUES ('CASH')");
            List<String> like = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT NAME FROM n WHERE NAME LIKE 'CA%'")) {
                while (rs.next()) like.add(rs.getString(1).trim());
            }
            check("LIKE 'CA%' matches only 'CASH' (got " + like + ")",
                like.equals(List.of("CASH")));
            List<String> likeU = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT NAME FROM n WHERE UCASE(NAME) LIKE 'CA%'")) {
                while (rs.next()) likeU.add(rs.getString(1).trim());
            }
            check("UCASE(NAME) LIKE 'CA%' matches both (got " + likeU + ")", likeU.size() == 2);
        }

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static List<Integer> ints(Statement st, String sql) throws Exception {
        List<Integer> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getInt(1));
        }
        return out;
    }

    private static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("PASS: " + name); }
        else { failed++; System.out.println("FAIL: " + name); }
    }
}
