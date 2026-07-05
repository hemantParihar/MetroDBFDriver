package com.dbf.example;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetProvider;

/** Does a CachedRowSet fetch a KNOWN non-empty memo (ROW_ID=33, "  0.462")? */
public class MemoClobDiag {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:dbf:" + (args.length > 0 ? args[0] : "E:/METRO/SG20") + ";charset=Cp1252";
        String sql = "SELECT ROW_ID, TR_WT FROM SALES WHERE ROW_ID=33";

        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery(sql)) {
                System.out.println("type=" + rs.getMetaData().getColumnType(2)
                    + " (CLOB=" + java.sql.Types.CLOB + ", LONGVARCHAR=" + java.sql.Types.LONGVARCHAR + ")");
                if (rs.next()) System.out.println("direct getString=[" + rs.getString("TR_WT") + "]");
            }
            try (ResultSet rs = st.executeQuery(sql)) {
                CachedRowSet crs = RowSetProvider.newFactory().createCachedRowSet();
                crs.populate(rs);
                if (crs.next()) {
                    System.out.println("CachedRowSet getString=[" + crs.getString("TR_WT") + "]");
                    System.out.println("CachedRowSet getObject=[" + crs.getObject("TR_WT") + "]");
                }
            } catch (Throwable t) {
                System.out.println("CachedRowSet threw: " + t);
            }
        }
    }
}
