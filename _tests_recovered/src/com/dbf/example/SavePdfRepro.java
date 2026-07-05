package com.dbf.example;
import java.sql.*;
/** The exact query from the app's savePdf crash log, printing each column's class. */
public class SavePdfRepro {
  public static void main(String[] a) throws Exception {
    String url = "jdbc:dbf:E:/METRO/SG20;charset=Cp1252";
    try (Connection c = DriverManager.getConnection(url);
         Statement st = c.createStatement();
         ResultSet rs = st.executeQuery(
           "Select D_DATE,DEBIT,AGENT,TR_CODE1,TR_CODE2,TR_CODE3,TR_CODE4,"
         + "0 AS TR_CODE5,0 AS B_CODE,V_NO from Sales where TYPE='A' AND V_NO=1130")) {
      ResultSetMetaData m = rs.getMetaData();
      if (!rs.next()) { System.out.println("no row"); return; }
      boolean anyLong = false;
      for (int i = 1; i <= m.getColumnCount(); i++) {
        Object o = rs.getObject(i);
        if (o instanceof Long) anyLong = true;
        System.out.printf("%-10s value=%-12s class=%s%n", m.getColumnLabel(i), o,
          o == null ? "null" : o.getClass().getSimpleName());
      }
      System.out.println(anyLong ? "FAIL: a Long leaked through" : "OK: no Long anywhere");
    }
  }
}
