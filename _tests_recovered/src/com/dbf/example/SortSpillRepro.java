package com.dbf.example;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;

/**
 * Scans the user's ORDER BY query (which spills) for any column value that
 * looks like a leaked temp-file path or contains control characters.
 */
public class SortSpillRepro {
    private static final char BACKSLASH = (char) 92;

    public static void main(String[] args) throws Exception {
        String url = "jdbc:dbf:" + (args.length > 0 ? args[0] : "E:/METRO/PA25");
        String sql =
            "SELECT M.C_HEAD AS M_C_HEAD,M.CUST_DESC AS M_CUST_DESC,M.ADD_1 AS M_ADD_1,"
            + "M.ADD_2 AS M_ADD_2,M.LICENSE AS M_LICENSE,M.GST_NO AS M_GST_NO,0 AS M_AG_CL,"
            + "null AS M_X FROM MASTER M WHERE M.L_FLAG='X' AND (UCASE(M.CUST_DESC) like '%') "
            + "ORDER BY UCASE(M.CUST_DESC)";

        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData m = rs.getMetaData();
            int cc = m.getColumnCount();
            int n = 0, bad = 0, maxDesc = 0;
            while (rs.next()) {
                n++;
                String d = rs.getString("M_CUST_DESC");
                if (d != null) maxDesc = Math.max(maxDesc, d.length());
                for (int i = 1; i <= cc; i++) {
                    String v = rs.getString(i);
                    if (v != null && looksBad(v)) {
                        bad++;
                        if (bad <= 10) {
                            System.out.println("row " + n + " col " + m.getColumnLabel(i)
                                + " = [" + v + "]  (len " + v.length() + ")");
                        }
                    }
                }
            }
            System.out.println("rows=" + n + "  badHits=" + bad + "  maxCustDescLen=" + maxDesc);
        }
    }

    private static boolean looksBad(String v) {
        if (v.indexOf(BACKSLASH) >= 0) return true;
        if (v.contains("Temp") || v.contains("Users") || v.contains("dbf_sort")
            || v.contains(".tmp")) return true;
        for (int i = 0; i < v.length(); i++) {
            if (v.charAt(i) < 0x20) return true; // control char
        }
        return false;
    }
}
