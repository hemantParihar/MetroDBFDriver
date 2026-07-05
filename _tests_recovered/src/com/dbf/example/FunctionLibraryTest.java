package com.dbf.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Exercises the SQL:2003-aligned scalar functions added to FunctionLibrary,
 * using literal arguments over a one-row table so results are deterministic.
 */
public class FunctionLibraryTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("dbf-func-test");
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');

        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE ft (ID NUMERIC(3))");
            st.executeUpdate("INSERT INTO ft (ID) VALUES (1)");

            String sql = "SELECT "
                + "CEIL(4.2) AS f_ceil, FLOOR(4.8) AS f_floor, POWER(2,10) AS f_pow, "
                + "SQRT(16) AS f_sqrt, SIGN(-5) AS f_sign, TRUNCATE(3.567,1) AS f_trunc, "
                + "MOD(10,3) AS f_mod, ROUND(3.14159,2) AS f_round, ABS(-7) AS f_abs, "
                + "CHAR_LENGTH('hello') AS f_clen, REPLACE('abcabc','b','X') AS f_repl, "
                + "LPAD('7',3,'0') AS f_lpad, RPAD('7',3,'0') AS f_rpad, "
                + "REPEAT('ab',3) AS f_rep, REVERSE('abc') AS f_rev, ASCII('A') AS f_ascii, "
                + "CHR(66) AS f_chr, INITCAP('hello world') AS f_initcap, "
                + "LOCATE('cd','abcde') AS f_locate, INSTR('abcde','cd') AS f_instr, "
                + "POSITION('cd','abcde') AS f_pos, NULLIF(5,5) AS f_nullif1, "
                + "NULLIF(5,6) AS f_nullif2, GREATEST(3,9,5) AS f_greatest, "
                + "LEAST(3,9,5) AS f_least, DECODE(2,1,'a',2,'b','def') AS f_decode, "
                + "NVL2(null,'x','y') AS f_nvl2, COALESCE(null,null,'z') AS f_coalesce, "
                + "DATEDIFF(#2025-01-10#,#2025-01-01#) AS f_datediff, "
                + "DAYOFWEEK(#2025-06-14#) AS f_dow, QUARTER(#2025-06-14#) AS f_q, "
                + "MONTHNAME(#2025-06-14#) AS f_mname, CURRENT_DATE AS f_today "
                + "FROM ft";

            try (ResultSet rs = st.executeQuery(sql)) {
                check("row present", rs.next());
                checkD("CEIL(4.2)=5", rs.getDouble("f_ceil"), 5);
                checkD("FLOOR(4.8)=4", rs.getDouble("f_floor"), 4);
                checkD("POWER(2,10)=1024", rs.getDouble("f_pow"), 1024);
                checkD("SQRT(16)=4", rs.getDouble("f_sqrt"), 4);
                checkD("SIGN(-5)=-1", rs.getDouble("f_sign"), -1);
                checkD("TRUNCATE(3.567,1)=3.5", rs.getDouble("f_trunc"), 3.5);
                checkD("MOD(10,3)=1", rs.getDouble("f_mod"), 1);
                checkD("ROUND(3.14159,2)=3.14", rs.getDouble("f_round"), 3.14);
                checkD("ABS(-7)=7", rs.getDouble("f_abs"), 7);
                checkS("CHAR_LENGTH('hello')=5", rs.getString("f_clen"), "5");
                checkS("REPLACE", rs.getString("f_repl"), "aXcaXc");
                checkS("LPAD('7',3,'0')=007", rs.getString("f_lpad"), "007");
                checkS("RPAD('7',3,'0')=700", rs.getString("f_rpad"), "700");
                checkS("REPEAT('ab',3)=ababab", rs.getString("f_rep"), "ababab");
                checkS("REVERSE('abc')=cba", rs.getString("f_rev"), "cba");
                checkS("ASCII('A')=65", rs.getString("f_ascii"), "65");
                checkS("CHR(66)=B", rs.getString("f_chr"), "B");
                checkS("INITCAP", rs.getString("f_initcap"), "Hello World");
                checkS("LOCATE('cd','abcde')=3", rs.getString("f_locate"), "3");
                checkS("INSTR('abcde','cd')=3", rs.getString("f_instr"), "3");
                checkS("POSITION('cd','abcde')=3", rs.getString("f_pos"), "3");
                check("NULLIF(5,5)=null", rs.getString("f_nullif1") == null);
                checkS("NULLIF(5,6)=5", rs.getString("f_nullif2"), "5");
                checkD("GREATEST(3,9,5)=9", rs.getDouble("f_greatest"), 9);
                checkD("LEAST(3,9,5)=3", rs.getDouble("f_least"), 3);
                checkS("DECODE=b", rs.getString("f_decode"), "b");
                checkS("NVL2(null,..)=y", rs.getString("f_nvl2"), "y");
                checkS("COALESCE=z", rs.getString("f_coalesce"), "z");
                checkS("DATEDIFF=9", rs.getString("f_datediff"), "9");
                checkS("DAYOFWEEK(2025-06-14 Sat)=7", rs.getString("f_dow"), "7");
                checkS("QUARTER(Jun)=2", rs.getString("f_q"), "2");
                checkS("MONTHNAME=June", rs.getString("f_mname"), "June");
                check("CURRENT_DATE not null", rs.getString("f_today") != null);
            }
        }

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void checkD(String name, double got, double want) {
        check(name + " (got " + got + ")", Math.abs(got - want) < 1e-6);
    }

    private static void checkS(String name, String got, String want) {
        check(name + " (got " + got + ")", want.equals(got == null ? null : got.trim()));
    }

    private static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("PASS: " + name); }
        else { failed++; System.out.println("FAIL: " + name); }
    }
}
