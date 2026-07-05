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
 * Predicate pushdown correctness: base-table WHERE conjuncts are evaluated at
 * the base scan before the join (a speed-up that must not change results), while
 * predicates on a LEFT-joined table -- especially ISNULL -- are NOT pushed and
 * keep their post-join semantics.
 */
public class PushdownTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("dbf-pushdown");
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');

        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE a (ID NUMERIC(3), SEL CHAR(1), K NUMERIC(3))");
            st.executeUpdate("CREATE TABLE b (K NUMERIC(3), NAME CHAR(10))");
            st.executeUpdate("INSERT INTO a (ID,SEL,K) VALUES (1,'Y',10)");
            st.executeUpdate("INSERT INTO a (ID,SEL,K) VALUES (2,'N',10)");
            st.executeUpdate("INSERT INTO a (ID,SEL,K) VALUES (3,'Y',99)"); // no B match
            st.executeUpdate("INSERT INTO b (K,NAME) VALUES (10,'ten')");

            // Base-only predicate (A.SEL='Y') is pushed; LEFT join keeps the
            // unmatched A row (id=3) with a null B side.
            check("base predicate pushed: ids [1,3]",
                ids(st, "SELECT A.ID FROM a A LEFT JOIN b B ON A.K=B.K "
                    + "WHERE A.SEL='Y' ORDER BY A.ID").equals(List.of(1, 3)));

            // ISNULL on the LEFT-joined table must NOT be pushed: only the
            // unmatched row (id=3) qualifies.
            check("right-table ISNULL not mis-pushed: ids [3]",
                ids(st, "SELECT A.ID FROM a A LEFT JOIN b B ON A.K=B.K "
                    + "WHERE A.SEL='Y' AND ISNULL(B.NAME) ORDER BY A.ID").equals(List.of(3)));

            // Right-table equality (post-join) still correct: only id=1 matches.
            check("right-table equality correct: ids [1]",
                ids(st, "SELECT A.ID FROM a A LEFT JOIN b B ON A.K=B.K "
                    + "WHERE A.SEL='Y' AND TRIM(B.NAME)='ten' ORDER BY A.ID").equals(List.of(1)));

            // Mixed: base + right predicate together. A.K=10 (pushed) matches
            // ids 1 and 2; both join to 'ten'.
            check("base + right predicate: ids [1,2]",
                ids(st, "SELECT A.ID FROM a A LEFT JOIN b B ON A.K=B.K "
                    + "WHERE A.K=10 AND TRIM(B.NAME)='ten' ORDER BY A.ID").equals(List.of(1, 2)));
        }

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static List<Integer> ids(Statement st, String sql) throws Exception {
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
