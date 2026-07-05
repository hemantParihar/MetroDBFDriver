package com.dbf.example;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Times the user's real MASTER search with the NTX index on vs. off. */
public class SearchTimeRun {
    public static void main(String[] args) throws Exception {
        String folder = args.length > 0 ? args[0] : "E:/METRO/sg20";

        String prefix = args.length > 1 ? args[1] : "C";
        String sql = "SELECT TOP 25 M.C_HEAD AS M_C_HEAD, M.LF_NO AS M_LF_NO, "
            + "M.L_FLAG AS M_L_FLAG, M.CUST_DESC AS M_CUST_DESC, 0 AS M_CREDITDAY, "
            + "null AS M_RATESLB, M.INT_RATE AS M_INT_RATE "
            + "FROM MASTER M WHERE M.L_FLAG = 'X' AND ( UCASE(M.CUST_DESC) like '" + prefix + "%' ) "
            + "ORDER BY UCASE(M.CUST_DESC)";

        List<String> idxOn = run("INDEX ON ", "jdbc:dbf:" + folder + ";indexRead=on", sql);
        List<String> idxOff = run("INDEX OFF", "jdbc:dbf:" + folder, sql);

        System.out.println();
        System.out.println("Results identical: " + idxOn.equals(idxOff)
            + "  (" + idxOn.size() + " rows)");
        if (!idxOn.equals(idxOff)) {
            System.out.println("  INDEX ON : " + idxOn);
            System.out.println("  INDEX OFF: " + idxOff);
        }
    }

    private static List<String> run(String label, String url, String sql) throws Exception {
        List<String> rows = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {
            for (int i = 0; i < 3; i++) {
                long t0 = System.currentTimeMillis();
                rows.clear();
                try (ResultSet rs = stmt.executeQuery(sql)) {
                    while (rs.next()) {
                        rows.add(rs.getString("M_C_HEAD") + "|"
                            + String.valueOf(rs.getString("M_CUST_DESC")).trim());
                    }
                }
                System.out.println(label + " run " + (i + 1) + ": " + rows.size()
                    + " rows in " + (System.currentTimeMillis() - t0) + "ms");
            }
        }
        return rows;
    }
}
