package com.dbf.example;
import java.sql.*;
public class ConfirmReal {
  static final char BS=(char)92;
  public static void main(String[] a) throws Exception {
    String url="jdbc:dbf:"+(a.length>0?a[0]:"E:/METRO/PA25");
    // NO order by, NO index -> raw scan order
    String sql="SELECT C_HEAD, CUST_DESC FROM MASTER WHERE L_FLAG='X'";
    try(Connection c=DriverManager.getConnection(url);Statement st=c.createStatement();
        ResultSet rs=st.executeQuery(sql)){
      int found=0;
      while(rs.next()){String d=rs.getString("CUST_DESC");
        if(d!=null&&d.indexOf(BS)>=0){found++;
          System.out.println("C_HEAD="+rs.getInt("C_HEAD")+"  CUST_DESC=["+d+"]");}}
      System.out.println("rows with backslash in CUST_DESC (plain scan, no order): "+found);
    }
  }
}
