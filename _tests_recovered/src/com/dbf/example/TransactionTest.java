package com.dbf.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Verifies manual transactions: ROLLBACK undoes INSERT/UPDATE/DELETE and
 * restores the exact prior state; COMMIT persists changes.
 */
public class TransactionTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("dbf-txn-test");
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');

        try (Connection conn = DriverManager.getConnection(url);
             Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE t (ID NUMERIC(5), NAME CHAR(20))");
            st.executeUpdate("INSERT INTO t (ID, NAME) VALUES (1, 'Alice')");
            st.executeUpdate("INSERT INTO t (ID, NAME) VALUES (2, 'Bob')");
            st.executeUpdate("INSERT INTO t (ID, NAME) VALUES (3, 'Carol')");
            check("baseline count = 3", count(st, "SELECT COUNT(*) AS c FROM t") == 3);

            // ---- transaction that we ROLL BACK ----
            conn.setAutoCommit(false);
            st.executeUpdate("INSERT INTO t (ID, NAME) VALUES (4, 'Dave')");
            st.executeUpdate("UPDATE t SET NAME = 'BOBBY' WHERE ID = 2");
            st.executeUpdate("DELETE FROM t WHERE ID = 3");
            // Changes are visible within the connection before rollback.
            check("mid-txn count = 3 (4 in, 3 deleted)", count(st, "SELECT COUNT(*) AS c FROM t") == 3);
            check("mid-txn Bob renamed", "BOBBY".equals(nameOf(st, 2)));

            conn.rollback();

            check("after rollback count = 3", count(st, "SELECT COUNT(*) AS c FROM t") == 3);
            check("after rollback Dave gone", count(st, "SELECT COUNT(*) AS c FROM t WHERE ID=4") == 0);
            check("after rollback Bob restored", "Bob".equals(nameOf(st, 2)));
            check("after rollback Carol restored (un-deleted)",
                count(st, "SELECT COUNT(*) AS c FROM t WHERE ID=3") == 1);

            // ---- transaction that we COMMIT ----
            st.executeUpdate("INSERT INTO t (ID, NAME) VALUES (5, 'Eve')");
            st.executeUpdate("UPDATE t SET NAME = 'CAROLINE' WHERE ID = 3");
            conn.commit();

            check("after commit count = 4", count(st, "SELECT COUNT(*) AS c FROM t") == 4);
            check("after commit Eve present", count(st, "SELECT COUNT(*) AS c FROM t WHERE ID=5") == 1);
            check("after commit Carol renamed", "CAROLINE".equals(nameOf(st, 3)));

            // committed changes survive even if we now roll back nothing
            conn.rollback();
            check("no-op rollback leaves committed data", count(st, "SELECT COUNT(*) AS c FROM t") == 4);

            conn.setAutoCommit(true);
        }

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static long count(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) { rs.next(); return rs.getLong(1); }
    }

    private static String nameOf(Statement st, int id) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT NAME FROM t WHERE ID = " + id)) {
            return rs.next() ? rs.getString(1).trim() : null;
        }
    }

    private static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("PASS: " + name); }
        else { failed++; System.out.println("FAIL: " + name); }
    }
}
