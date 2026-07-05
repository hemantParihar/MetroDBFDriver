package com.dbf.example;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/** Compares TR_WT via simple path vs join path for the SAME rows. */
public class MemoCachedRowSetDiag {
    public static void main(String[] args) throws Exception {
        String folder = args.length > 0 ? args[0] : "E:/METRO/SG20";
        String url = "jdbc:dbf:" + folder + ";charset=Cp1252";

        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {

            // 1. Simple path: find rows with a non-null TR_WT; remember one ROW_ID.
            int simpleNonNull = 0, simpleScanned = 0;
            String sampleId = null, sampleVal = null;
            try (ResultSet rs = st.executeQuery("SELECT ROW_ID, TR_WT FROM SALES")) {
                while (rs.next()) {
                    simpleScanned++;
                    String v = rs.getString("TR_WT");
                    if (v != null && !v.trim().isEmpty()) {
                        simpleNonNull++;
                        if (sampleId == null) { sampleId = rs.getString("ROW_ID"); sampleVal = v; }
                    }
                }
            }
            System.out.println("SIMPLE path: scanned=" + simpleScanned + " nonNullTR_WT=" + simpleNonNull);
            System.out.println("  sample ROW_ID=" + sampleId + " TR_WT=[" + sampleVal + "]");

            // 2. Join path: count non-null TR_WT over the same first rows.
            int joinNonNull = 0, joinScanned = 0;
            try (ResultSet rs = st.executeQuery(
                    "SELECT SALES.ROW_ID, SALES.TR_WT FROM SALES "
                  + "LEFT JOIN TAX ON SALES.ST=TAX.TAX_CODE")) {
                while (rs.next()) {
                    joinScanned++;
                    String v = rs.getString("TR_WT");
                    if (v != null && !v.trim().isEmpty()) joinNonNull++;
                }
            }
            System.out.println("JOIN path:   scanned=" + joinScanned + " nonNullTR_WT=" + joinNonNull);

            // 3. The SAME known row, through the join path.
            if (sampleId != null) {
                try (ResultSet rs = st.executeQuery(
                        "SELECT SALES.ROW_ID, SALES.TR_WT FROM SALES "
                      + "LEFT JOIN TAX ON SALES.ST=TAX.TAX_CODE WHERE SALES.ROW_ID=" + sampleId)) {
                    String v = rs.next() ? rs.getString("TR_WT") : "<no row>";
                    System.out.println("JOIN path, ROW_ID=" + sampleId + " TR_WT=[" + v + "]");
                }
            }
        }
    }
}
