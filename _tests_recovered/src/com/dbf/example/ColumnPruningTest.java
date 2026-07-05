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
 * Column pruning (decode only referenced fields) must not drop columns used in
 * WHERE / ORDER BY / JOIN ON / GROUP BY even when they are NOT in the SELECT
 * list. Verified on the streaming (join) path.
 */
public class ColumnPruningTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("dbf-prune");
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');

        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE wide (ID NUMERIC(4), A CHAR(5), B NUMERIC(4), "
                + "C CHAR(5), D NUMERIC(4), E NUMERIC(4), K NUMERIC(3))");
            st.executeUpdate("CREATE TABLE dim (K NUMERIC(3), NAME CHAR(6))");
            // ID, A, B, C, D, E, K
            st.executeUpdate("INSERT INTO wide (ID,A,B,C,D,E,K) VALUES (1,'a1',30,'c1',9,5,7)");
            st.executeUpdate("INSERT INTO wide (ID,A,B,C,D,E,K) VALUES (2,'a2',10,'c2',9,5,7)");
            st.executeUpdate("INSERT INTO wide (ID,A,B,C,D,E,K) VALUES (3,'a3',20,'c3',9,1,7)"); // E=1 filtered out
            st.executeUpdate("INSERT INTO dim (K,NAME) VALUES (7,'seven')");

            // SELECT only ID, C and the dim NAME. WHERE on E, ORDER BY on B,
            // JOIN ON K -- none of E/B/K are in the SELECT list, so pruning must
            // still decode them.
            List<String> got = new ArrayList<>();
            try (ResultSet rs = st.executeQuery(
                    "SELECT W.ID, W.C, D.NAME FROM wide W LEFT JOIN dim D ON W.K=D.K "
                  + "WHERE W.E=5 ORDER BY W.B")) {
                while (rs.next()) {
                    got.add(rs.getInt("ID") + ":" + rs.getString("C").trim()
                        + ":" + rs.getString("NAME").trim());
                }
            }
            // E=5 keeps ids 1,2 (id3 has E=1). Ordered by B asc: id2(B=10), id1(B=30).
            check("WHERE on non-selected E + ORDER BY non-selected B + JOIN on K (got " + got + ")",
                got.equals(List.of("2:c2:seven", "1:c1:seven")));

            // GROUP BY a non-selected column, aggregate over another.
            check("GROUP BY non-selected D, SUM(B) (1 group, sum 60)",
                ints(st, "SELECT SUM(W.B) AS s FROM wide W LEFT JOIN dim D ON W.K=D.K GROUP BY W.D")
                    .equals(List.of(60)));

            // SELECT * must still work (pruning disabled) through a join.
            int cols;
            try (ResultSet rs = st.executeQuery(
                    "SELECT * FROM wide W LEFT JOIN dim D ON W.K=D.K WHERE W.ID=1")) {
                rs.next();
                cols = rs.getMetaData().getColumnCount();
                check("SELECT * returns all values (A=a1)", "a1".equals(rs.getString("A").trim()));
            }
            check("SELECT * column count = 9 (7 + 2)", cols == 9);
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
