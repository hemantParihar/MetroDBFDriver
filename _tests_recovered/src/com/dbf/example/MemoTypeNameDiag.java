package com.dbf.example;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;

/** Confirms TR_WT reports type name MEMO, int LONGVARCHAR, class String -- simple AND join path. */
public class MemoTypeNameDiag {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:dbf:" + (args.length > 0 ? args[0] : "E:/METRO/SG20") + ";charset=Cp1252";
        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {
            report(st, "simple", "SELECT TR_WT FROM SALES WHERE ROW_ID=33");
            report(st, "join  ", "SELECT SALES.TR_WT FROM SALES "
                + "LEFT JOIN TAX ON SALES.ST=TAX.TAX_CODE WHERE SALES.ROW_ID=33");
        }
    }

    private static void report(Statement st, String path, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData m = rs.getMetaData();
            System.out.printf("%s: typeName=%s  type=%d (LONGVARCHAR=%d CLOB=%d)  class=%s%n",
                path, m.getColumnTypeName(1), m.getColumnType(1),
                Types.LONGVARCHAR, Types.CLOB, m.getColumnClassName(1));
        }
    }
}
