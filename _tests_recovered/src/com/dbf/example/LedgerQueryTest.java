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
 * Exercises the features the "All Ledger" / "Stock" reports need:
 *  (A) UPDATE SET col = expression-over-other-columns (trial-balance update),
 *  (B) GROUP BY an expression (MID(...)),
 *  (C) composite equi-join (ON a.k1=b.k1 AND a.k2=b.k2),
 *  (D) ORDER BY ordinal (ORDER BY 1, 2).
 */
public class LedgerQueryTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("dbf-ledger");
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');

        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {

            // (A) UPDATE SET col = expression
            st.executeUpdate("CREATE TABLE acct (C_HEAD NUMERIC(6), O_BAL NUMERIC(12,2), "
                + "DD_DEBIT NUMERIC(12,2), DD_CREDIT NUMERIC(12,2), "
                + "P_OB NUMERIC(12,2), P_DR NUMERIC(12,2), P_CR NUMERIC(12,2), P_CL NUMERIC(12,2))");
            st.executeUpdate("INSERT INTO acct (C_HEAD,O_BAL,DD_DEBIT,DD_CREDIT) VALUES (1,100,30,20)");
            st.executeUpdate("UPDATE acct SET P_OB=O_BAL, P_DR=DD_DEBIT, P_CR=DD_CREDIT, "
                + "P_CL=O_BAL+DD_DEBIT-DD_CREDIT");
            try (ResultSet rs = st.executeQuery("SELECT P_OB,P_DR,P_CR,P_CL FROM acct")) {
                rs.next();
                check("SET P_OB=O_BAL -> 100", rs.getDouble("P_OB") == 100);
                check("SET P_DR=DD_DEBIT -> 30", rs.getDouble("P_DR") == 30);
                check("SET P_CR=DD_CREDIT -> 20", rs.getDouble("P_CR") == 20);
                check("SET P_CL=O_BAL+DD_DEBIT-DD_CREDIT -> 110", rs.getDouble("P_CL") == 110);
            }

            // (B) GROUP BY expression MID(NAME,31,10) groups by the "city" suffix
            st.executeUpdate("CREATE TABLE t (NAME CHAR(40), V NUMERIC(5))");
            st.executeUpdate("INSERT INTO t (NAME,V) VALUES ('" + rep('A',30) + "PUNE', 10)");
            st.executeUpdate("INSERT INTO t (NAME,V) VALUES ('" + rep('B',30) + "PUNE', 20)");
            st.executeUpdate("INSERT INTO t (NAME,V) VALUES ('" + rep('C',30) + "DELHI', 5)");
            check("GROUP BY MID(NAME,31,10) -> DELHI=5, PUNE=30",
                ints(st, "SELECT SUM(V) AS s FROM t GROUP BY MID(NAME,31,10) "
                    + "ORDER BY MID(NAME,31,10)").equals(List.of(5, 30)));

            // (C) Composite equi-join
            st.executeUpdate("CREATE TABLE a (K1 NUMERIC(3), K2 NUMERIC(3), AV CHAR(10))");
            st.executeUpdate("CREATE TABLE b (K1 NUMERIC(3), K2 NUMERIC(3), BV CHAR(10))");
            st.executeUpdate("INSERT INTO a (K1,K2,AV) VALUES (1,1,'a11')");
            st.executeUpdate("INSERT INTO a (K1,K2,AV) VALUES (1,2,'a12')");
            st.executeUpdate("INSERT INTO b (K1,K2,BV) VALUES (1,1,'b11')");
            st.executeUpdate("INSERT INTO b (K1,K2,BV) VALUES (1,2,'b12')");
            st.executeUpdate("INSERT INTO b (K1,K2,BV) VALUES (2,1,'bX')");
            List<String> pairs = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT a.AV AS av, b.BV AS bv "
                    + "FROM a JOIN b ON a.K1=b.K1 AND a.K2=b.K2 ORDER BY a.AV")) {
                while (rs.next()) pairs.add(rs.getString("av").trim() + "/" + rs.getString("bv").trim());
            }
            check("composite join pairs = [a11/b11, a12/b12] (got " + pairs + ")",
                pairs.equals(List.of("a11/b11", "a12/b12")));

            // (D) ORDER BY ordinal
            st.executeUpdate("CREATE TABLE ord (NAME CHAR(5), V NUMERIC(5))");
            st.executeUpdate("INSERT INTO ord (NAME,V) VALUES ('B',2)");
            st.executeUpdate("INSERT INTO ord (NAME,V) VALUES ('A',1)");
            st.executeUpdate("INSERT INTO ord (NAME,V) VALUES ('C',3)");
            check("ORDER BY 1 (NAME) -> V [1,2,3]",
                ints(st, "SELECT NAME, V FROM ord ORDER BY 1").equals(List.of(1, 2, 3)));
            check("ORDER BY 2 DESC (V) -> V [3,2,1]",
                ints(st, "SELECT NAME, V FROM ord ORDER BY 2 DESC").equals(List.of(3, 2, 1)));
        }

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static List<Integer> ints(Statement st, String sql) throws Exception {
        List<Integer> out = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getInt(rs.getMetaData().getColumnCount() == 1 ? 1 : 2));
        }
        return out;
    }

    private static String rep(char ch, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(ch);
        return sb.toString();
    }

    private static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("PASS: " + name); }
        else { failed++; System.out.println("FAIL: " + name); }
    }
}
