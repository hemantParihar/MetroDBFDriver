package com.dbf.example;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;

/** Dumps column metadata for the user's exact Ledger aggregate query. */
public class MetaDumpRun {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:dbf:" + (args.length > 0 ? args[0] : "E:/METRO/sg20");

        String sql = "select MAX(STR(YEAR(D_DATE))+'-'+STR(MONTH(D_DATE))) as fun000_007_8_Sn,"
            + "MAX(d_date) as fun000_008_8_Sn,MAX(TRAN.debit)  as fun000_005_8_CodeDr,"
            + "MAX(TRAN.credit) as fun000_005_8_CodeCr,MAX(TRAN.d_date) as fun005_010_0_vDate,"
            + "MAX(TRAN.V_TYPE) as fun000_002_8_Bk,MAX(TRAN.ENTERY) AS fun000_003_8_Sn,"
            + "MAX(TRAN.type) as fun000_000_8_type,MAX(TRAN.v_type) as fun008_004_0_Book,"
            + "MAX(TRAN.a_nar ) AS Fun000_030_0_Particulars,'' AS fun000_010_8_Place,"
            + "MAX(TRAN.V_NO) AS fun000_006_8_VNO,MAX(TRAN.COR_REF1) as fun000_007_0_RefNo,"
            + "SUM(INT_AMT) as fun001_010_n_Interest_Discount,MAX(CODE_INT) AS fun000_003_8_CodeInt,"
            + "SUM(a_bal) as fun001_012_8_Amount,"
            + "SUM(IIF(TRAN.DEBIT =1,IIF(TRAN.CODE_INT= 2,TRAN.A_BAL+TRAN.INT_AMT,IIF(CODE_INT<0,A_BAL,A_BAL-INT_AMT)),0)) AS fun005_012_2_Receipts,"
            + "SUM(IIF(TRAN.CREDIT=1,IIF(TRAN.CODE_INT=-2,TRAN.A_BAL+TRAN.INT_AMT,IIF(CODE_INT>0,A_BAL,A_BAL-INT_AMT)),0)) AS fun006_012_2_Payments,"
            + "0 AS fun007_014_5_Balance,MAX(GST_VOU) AS fun000_010_8_DrNote,"
            + "MAX(a_nar) AS Fun005_030_7_Particularsa,MAX(b_nar) AS Fun005_030_7_Particularsb,"
            + "MAX(TRAN.DEBIT_SN) AS fun000_006_8_DrArc,MAX(TRAN.CREDIT_SN) AS fun000_006_8_CrArc,"
            + "MAX(TRAN.AVN_DR) AS fun000_006_8_AvnDr,MAX(TRAN.AVN_CR) AS fun000_006_8_AvnCr,"
            + "MAX(TRAN.GST_VOU) AS fun000_006_8_DN "
            + "from TRAN WHERE ( TRAN.DEBIT=1 OR TRAN.CREDIT=1) AND not isnull(TRAN.d_date) "
            + "and TRAN.debit<>0 and TRAN.credit<> 0 "
            + "AND ( D_Date>=#2025-04-01# AND D_DATE<=#2026-03-31# ) "
            + "GROUP BY D_DATE,DEBIT,CREDIT,V_TYPE,TYPE,V_NO,ENTERY,COR_REF1,A_NAR,B_NAR,A_BAL,Code_Int "
            + "ORDER BY MAX(D_DATE),MAX(TRAN.HO),MAX(V_TYPE),MAX(V_NO),MAX(TYPE),MAX(ENTERY)";

        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData m = rs.getMetaData();
            for (int i = 1; i <= m.getColumnCount(); i++) {
                System.out.printf("%2d  %-26s type=%-4d  name=%-10s class=%s%n",
                    i, m.getColumnLabel(i), m.getColumnType(i),
                    m.getColumnTypeName(i), m.getColumnClassName(i));
            }
        }
    }
}
