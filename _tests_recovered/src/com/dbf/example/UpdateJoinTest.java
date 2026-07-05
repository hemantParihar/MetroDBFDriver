package com.dbf.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Covers the two failures from METRO's stock-statement flow:
 *  (1) Access/Jet correlated UPDATE with a JOIN
 *      (UPDATE t1 AS C INNER JOIN t2 AS E ON C.k1=E.k1 AND C.k2=E.k2 SET ...), and
 *  (2) table names written with a .DBF extension (DELETE FROM yx05t02.DBF).
 */
public class UpdateJoinTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("dbf-ujoin");
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');

        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {

            String cols = "(LOTE NUMERIC(4), LOTESN NUMERIC(3), O_QT NUMERIC(6), O_WT NUMERIC(8), "
                + "R_QT NUMERIC(6), RECD_WT NUMERIC(8), I_QT NUMERIC(6), ISS_WT NUMERIC(8), "
                + "O_BAL NUMERIC(10), DEBIT NUMERIC(10), CREDIT NUMERIC(10))";
            st.executeUpdate("CREATE TABLE yx05t03 " + cols);
            st.executeUpdate("CREATE TABLE yx05t02 " + cols);

            // target C rows
            insert(st, "yx05t03", 1, 1, 10, 100, 0, 0, 0, 0, 5, 0, 0);
            insert(st, "yx05t03", 1, 2, 1, 2, 0, 0, 0, 0, 3, 0, 0);   // composite key (1,2)
            insert(st, "yx05t03", 2, 1, 99, 99, 9, 9, 9, 9, 9, 9, 9); // no E match -> unchanged
            // join E rows
            insert(st, "yx05t02", 1, 1, 3, 30, 7, 70, 2, 20, 4, 9, 8);
            insert(st, "yx05t02", 1, 2, 5, 6, 1, 1, 1, 1, 1, 2, 3);
            insert(st, "yx05t02", 7, 7, 1, 1, 1, 1, 1, 1, 1, 1, 1);   // no C match -> ignored

            int n = st.executeUpdate(
                "update yx05t03 AS C INNER join yx05t02 AS E on C.LOTE=E.LOTE AND C.LOTESN=E.LOTESN "
              + "set C.O_QT=C.O_QT+E.O_QT,C.O_WT=C.O_WT+E.O_WT,C.R_QT=E.R_QT,C.RECD_WT=E.RECD_WT,"
              + "C.i_QT=E.i_QT,C.ISS_WT=E.ISS_WT,C.O_BAL=C.O_BAL+E.O_BAL,C.DEBIT=E.DEBIT,C.CREDIT=E.CREDIT");
            check("UPDATE-JOIN affected 2 matched rows (got " + n + ")", n == 2);

            // row (1,1): O_QT 10+3=13, O_WT 100+30=130, R_QT=7, RECD_WT=70, I_QT=2,
            //            ISS_WT=20, O_BAL 5+4=9, DEBIT=9, CREDIT=8
            try (ResultSet rs = st.executeQuery("SELECT * FROM yx05t03 WHERE LOTE=1 AND LOTESN=1")) {
                rs.next();
                check("(1,1) O_QT=13", rs.getInt("O_QT") == 13);
                check("(1,1) O_WT=130", rs.getInt("O_WT") == 130);
                check("(1,1) R_QT=7", rs.getInt("R_QT") == 7);
                check("(1,1) RECD_WT=70", rs.getInt("RECD_WT") == 70);
                check("(1,1) I_QT=2 (lowercase i_QT in SET)", rs.getInt("I_QT") == 2);
                check("(1,1) ISS_WT=20", rs.getInt("ISS_WT") == 20);
                check("(1,1) O_BAL=9", rs.getInt("O_BAL") == 9);
                check("(1,1) DEBIT=9", rs.getInt("DEBIT") == 9);
                check("(1,1) CREDIT=8", rs.getInt("CREDIT") == 8);
            }
            // composite-key row (1,2): O_QT 1+5=6, O_BAL 3+1=4
            try (ResultSet rs = st.executeQuery("SELECT * FROM yx05t03 WHERE LOTE=1 AND LOTESN=2")) {
                rs.next();
                check("(1,2) composite match O_QT=6", rs.getInt("O_QT") == 6);
                check("(1,2) composite match O_BAL=4", rs.getInt("O_BAL") == 4);
            }
            // unmatched row (2,1) stays as inserted
            try (ResultSet rs = st.executeQuery("SELECT * FROM yx05t03 WHERE LOTE=2 AND LOTESN=1")) {
                rs.next();
                check("(2,1) unmatched row unchanged O_QT=99", rs.getInt("O_QT") == 99);
            }

            // (2) .DBF-suffixed table names in DML
            st.executeUpdate("UPDATE yx05t02.DBF SET DEBIT=111 WHERE LOTE=7 AND LOTESN=7");
            try (ResultSet rs = st.executeQuery("SELECT DEBIT FROM yx05t02 WHERE LOTE=7")) {
                check("UPDATE on .DBF-suffixed name worked", rs.next() && rs.getInt(1) == 111);
            }
            st.executeUpdate("DELETE FROM yx05t02.DBF WHERE LOTE=7 AND LOTESN=7");
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM yx05t02 WHERE LOTE=7")) {
                check("DELETE on .DBF-suffixed name worked", rs.next() && rs.getInt(1) == 0);
            }
        }

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void insert(Statement st, String t, int... v) throws Exception {
        StringBuilder sb = new StringBuilder("INSERT INTO ").append(t)
            .append(" (LOTE,LOTESN,O_QT,O_WT,R_QT,RECD_WT,I_QT,ISS_WT,O_BAL,DEBIT,CREDIT) VALUES (");
        for (int i = 0; i < v.length; i++) { if (i > 0) sb.append(','); sb.append(v[i]); }
        sb.append(')');
        st.executeUpdate(sb.toString());
    }

    private static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("PASS: " + name); }
        else { failed++; System.out.println("FAIL: " + name); }
    }
}
