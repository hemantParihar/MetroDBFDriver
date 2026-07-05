package com.dbf.example;

import java.sql.*;

/** The user's exact aggregate ERP query, run verbatim against E:/METRO/PA25. */
public class AggErpTest {
    public static void main(String[] args) {
        String url = "jdbc:dbf:E:/METRO/PA25";
        String sql = "select MAX(STR(YEAR(D_DATE))+'-'+STR(MONTH(D_DATE))) as fun000_007_8_Sn,MAX(d_date) as fun000_008_8_Sn,MAX(TRAN.debit)  as fun000_005_8_CodeDr  ,MAX(TRAN.credit) as fun000_005_8_CodeCr,MAX(TRAN.d_date) as fun005_010_0_vDate,MAX(TRAN.V_TYPE) as fun000_002_8_Bk      ,MAX(TRAN.ENTERY) AS fun000_003_8_Sn,MAX(TRAN.type)   as fun000_000_8_type    ,MAX(TRAN.v_type) as fun008_004_0_Book    ,MAX(TRAN.a_nar ) AS Fun000_030_0_Particulars,'' AS fun000_010_8_Place,MAX(TRAN.V_NO) AS fun000_006_8_VNO,MAX(TRAN.COR_REF1) as fun000_007_0_RefNo,SUM(INT_AMT) as fun001_010_n_Interest_Discount,MAX(CODE_INT) AS fun000_003_8_CodeInt,SUM(a_bal) as fun001_012_8_Amount ,SUM(IIF(TRAN.DEBIT =1,IIF(TRAN.CODE_INT= 2,TRAN.A_BAL+TRAN.INT_AMT,IIF(CODE_INT<0,A_BAL,A_BAL-INT_AMT)),0)) AS fun005_012_2_Receipts,SUM(IIF(TRAN.CREDIT=1,IIF(TRAN.CODE_INT=-2,TRAN.A_BAL+TRAN.INT_AMT,IIF(CODE_INT>0,A_BAL,A_BAL-INT_AMT)),0)) AS fun006_012_2_Payments,0 AS fun007_014_5_Balance from TRAN    WHERE ( TRAN.DEBIT=1 OR TRAN.CREDIT=1) AND not isnull(TRAN.d_date) and TRAN.debit<>0 and TRAN.credit<> 0   AND   D_DATE< #2025-04-01# GROUP BY DEBIT,CREDIT ,Code_Int";

        long start = System.currentTimeMillis();
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            ResultSetMetaData meta = rs.getMetaData();
            System.out.println("Parsed + executed. Output columns: " + meta.getColumnCount());

            int rows = 0;
            while (rs.next()) {
                rows++;
                if (rows <= 8) {
                    System.out.println("Group " + rows
                        + ": YM=" + rs.getString("fun000_007_8_Sn")
                        + " MAXdate=" + rs.getString("fun000_008_8_Sn")
                        + " Dr=" + rs.getDouble("fun000_005_8_CodeDr")
                        + " Cr=" + rs.getDouble("fun000_005_8_CodeCr")
                        + " CodeInt=" + rs.getDouble("fun000_003_8_CodeInt")
                        + " SUM(int)=" + rs.getDouble("fun001_010_n_Interest_Discount")
                        + " Receipts=" + rs.getDouble("fun005_012_2_Receipts")
                        + " Payments=" + rs.getDouble("fun006_012_2_Payments")
                        + " Place='" + rs.getString("fun000_010_8_Place") + "'"
                        + " Bal=" + rs.getInt("fun007_014_5_Balance"));
                }
            }
            System.out.println("Total groups: " + rows + " in " + (System.currentTimeMillis() - start) + "ms");
            System.out.println("SUCCESS: aggregate ERP query ran end-to-end.");
        } catch (SQLException e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
