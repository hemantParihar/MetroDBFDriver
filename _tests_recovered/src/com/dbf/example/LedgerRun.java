package com.dbf.example;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;

/** Runs the real METRO "Data for Ledger" aggregate-over-join query. */
public class LedgerRun {
    public static void main(String[] args) throws Exception {
        String folder = args.length > 0 ? args[0] : "E:/METRO/sg20";
        String url = "jdbc:dbf:" + folder;

        String sql =
            "select MAX(STR(YEAR(D_DATE))+'-'+STR(MONTH(D_DATE))) as fun000_007_8_Sn,MAX(d_date) as fun000_008_8_Sn,MAX(TRAN.debit)  as fun000_005_8_CodeDr  ,MAX(TRAN.credit) as fun000_005_8_CodeCr,MAX(TRAN.d_date) as fun005_010_0_vDate,MAX(TRAN.V_TYPE) as fun000_002_8_Bk      ,MAX(TRAN.ENTERY) AS fun000_003_8_Sn,MAX(TRAN.type)   as fun000_000_8_type    ,MAX(TRAN.v_type) as fun008_004_0_Book    ,MAX(TRAN.a_nar ) AS Fun000_030_0_Particulars,'' AS fun000_010_8_Place,MAX(TRAN.V_NO) AS fun000_006_8_VNO,MAX(TRAN.COR_REF1) as fun000_007_0_RefNo,SUM(INT_AMT) as fun001_010_n_Interest_Discount,MAX(CODE_INT) AS fun000_003_8_CodeInt,SUM(a_bal) as fun001_012_8_Amount ,SUM(IIF(TRAN.DEBIT =1,IIF(TRAN.CODE_INT= 2,TRAN.A_BAL+TRAN.INT_AMT,IIF(CODE_INT<0,A_BAL,A_BAL-INT_AMT)),0)) AS fun005_012_2_Receipts,SUM(IIF(TRAN.CREDIT=1,IIF(TRAN.CODE_INT=-2,TRAN.A_BAL+TRAN.INT_AMT,IIF(CODE_INT>0,A_BAL,A_BAL-INT_AMT)),0)) AS fun006_012_2_Payments,0 AS fun007_014_5_Balance,MAX(GST_VOU) AS fun000_010_8_DrNote,MAX(a_nar) AS Fun005_030_7_Particularsa,MAX(b_nar) AS Fun005_030_7_Particularsb,MAX(TRAN.DEBIT_SN) AS fun000_006_8_DrArc,MAX(TRAN.CREDIT_SN)  AS fun000_006_8_CrArc,MAX(TRAN.AVN_DR) AS fun000_006_8_AvnDr,MAX(TRAN.AVN_CR)  AS fun000_006_8_AvnCr,MAX(TRAN.GST_VOU) AS fun000_006_8_DN,MAX(AG.CUST_DESC) AS fun000_030_8_Th from TRAN   LEFT JOIN MASTER AS AG ON TRAN.CO_CODE=AG.C_HEAD  WHERE ( TRAN.DEBIT=1 OR TRAN.CREDIT=1) AND not isnull(TRAN.d_date) and TRAN.debit<>0 and TRAN.credit<> 0   AND ( D_Date>=#2020-04-01# AND D_DATE<=#2025-10-10# ) AND ( AG.C_HEAD>0 OR AG.L_FLAG='Z')  GROUP BY D_DATE,DEBIT,CREDIT,V_TYPE,TYPE,V_NO,ENTERY,COR_REF1,A_NAR,B_NAR,A_BAL ,Code_Int  ORDER BY MAX(D_DATE),MAX(TRAN.HO),MAX(V_TYPE),MAX(V_NO),MAX(TYPE),MAX(ENTERY)";

        System.out.println("URL: " + url);
        long t0 = System.currentTimeMillis();
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            ResultSetMetaData meta = rs.getMetaData();
            int cols = meta.getColumnCount();
            System.out.println("Columns: " + cols);

            int row = 0;
            int sampleCols = Math.min(cols, 8);
            while (rs.next()) {
                row++;
                if (row <= 5) {
                    StringBuilder sb = new StringBuilder("row " + row + ": ");
                    for (int c = 1; c <= sampleCols; c++) {
                        sb.append(meta.getColumnLabel(c)).append('=')
                          .append(rs.getString(c)).append("  ");
                    }
                    sb.append("Th=").append(rs.getString("fun000_030_8_Th"));
                    System.out.println(sb);
                }
            }
            long ms = System.currentTimeMillis() - t0;
            System.out.println("TOTAL GROUPS: " + row + "  in " + ms + "ms");
        }
    }
}
