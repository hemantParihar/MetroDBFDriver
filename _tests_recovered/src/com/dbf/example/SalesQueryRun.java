package com.dbf.example;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/** Runs the big "sales register" join+aggregate query and reports time/rows/errors. */
public class SalesQueryRun {
    public static void main(String[] args) throws Exception {
        String folder = args.length > 0 ? args[0] : "E:/METRO/PA25";
        String sql =
            "Select MAX(sales.d_date) as fun000_004_8_Sno1,MAX(SALES.TYPE+STR(SALES.V_NO)) as fun000_006_8_Sno2,"
            + "MAX(L.G_C) as fun000_000_8_G_C,MAX(d_date) as Fun000_010_8_vDate,MAX(SALES.TYPE) AS fun000_001_8_B,"
            + "MAX(SALES.TYPE+STR(SALES.V_NO)) as fun010_008_0_InvNo,MAX(bill_no) AS fun015_010_8_InvRef,"
            + "MAX(bill_dt) as fun000_010_8_RefDt,MAX(STR(SALES.DEBIT)) AS fun000_005_8_OnAccount,"
            + "MAX(MID(MASTER.CUST_DESC,1,30)) AS fun000_030_8_AccountName,MAX(SALES.PARTY_N) AS fun015_030_0_PartyParticulars,"
            + "MAX(MID(MASTER.CUST_DESC,31,10)) as fun000_010_0_Place,MAX(MASTER.GST_NO) as fun000_015_8_Gstin,"
            + "MAX(SALES.TIN_NO) AS fun015_016_8_GstTin,MAX(MASTER.LICENSE) AS fun000_015_8_Pan,"
            + "MAX(MID(AGENT.CUST_DESC,1,30)) as fun000_030_8_Agent,MAX(SALES.by_hand) as fun015_020_0_Th,"
            + "sum(sales.a_bal) AS fun034_010_9_InvAmount,0 as fun034_010_8_CashAmt,0 as fun034_010_8_CreditAmt,"
            + "'' as fun000_001_8_Next1,MAX(GOODSAC.CUST_DESC) AS fun000_030_8_GoodsAcName,"
            + "MAX(MID(GOODSAC.CUST_DESC,31)) AS fun000_010_8_GoodsCity,MAX(SALES.lote) as fun000_003_0_Lot,"
            + "MAX(SALES.lotesn) as fun000_001_0_S,MAX(l.QUALITY) AS Fun000_019_0_ProductName,MAX(l.REM) AS fun000_010_8_Remark,"
            + "MAX(AREA.SCH_NO  ) AS fun000_008_8_HSN,MAX(L.HSN_NEW    ) AS fun001_008_8_Hsn,MAX(SALES.HSN_NEW) AS fun015_008_t_HSN_SAC,"
            + "MAX(SALES.PROD_INV) AS fun000_010_8_Marka,MAX(SALES.L_rate) as fun000_008_8_lRate,MAX(SALES.L_Add) as fun000_008_8_Slb ,"
            + "SUM(sales.qt) as fun002_004_9_Qty,SUM(sales.wt) as fun002_008_9_Weight,SUM(SALES.A_BAL) as Fun038_010_8_TaxPaid,"
            + "AVG(SALES.rate) as fun000_008_2_Rate,SUM(SALES.NET-SALES.BARDAN) AS fun002_010_8_Amount,SUM(sales.bardan) as fun002_008_8_Bardan,"
            + "SUM(sales.net) as fun001_010_9_Amount,SUM(sales.comm) as fun002_007_9_Adhat,SUM(sales.s_comm) as fun002_007_9_Dalali ,"
            + "SUM(sales.s_charg) as fun002_006_9_Tulai ,MAX(SALES.MT) AS fun000_002_8_Mt,MAX(L.MTT) AS fun000_002_8_Pmt,"
            + "SUM(sales.s_tax1) as fun002_009_9_Mandi,SUM(iif(sales.mt=1,0,sales.s_tax1sc)) as fun002_008_2_Kkc,"
            + "SUM(iif(sales.mt=1,sales.s_tax1sc,0)) as fun002_008_2_UserCh,SUM(sales.s_tax1+sales.s_tax1sc) as fun002_008_8_TotalKm,"
            + "SUM(sales.labour) as fun002_007_9_Labour,SUM(SALES.A_BAL-SALES.NET-SALES.S_TAX-SALES.L_D) as fun002_010_8_ExpTotal,"
            + "SUM(SALES.A_BAL-SALES.S_TAX-SALES.L_D) AS fun002_019_8_AssValue,SUM(SALES.s_tax) as fun002_008_9_Tax,"
            + "SUM(SALES.IGST_AMT) AS fun001_014_8_IgstAmt,SUM(SALES.CGST_AMT) AS fun001_014_8_CgstAmt,SUM(SALES.SGST_AMT) AS fun001_014_8_SgstAmt,"
            + "SUM(SALES.CESS_AMT) AS fun001_014_8_CessAmt ,MAX(SALES.RCM_OPT) AS fun000_001_8_Rcm,MAX(SALES.ITC_SLB) AS fun000_001_8_Itc,"
            + "MAX(L.STT) AS fun000_002_8_Pst,MAX(TAX.FORM) AS 'fun000_005_8_Tax%',SUM(sales.l_d) as fun002_006_9_Oth,"
            + "SUM(sales.a_bal ) AS fun001_014_8_InvAmount ,SUM(SALES.TCS_AMT) AS fun001_010_8_TcsOn,SUM(SALES.TCS_TAX) AS fun001_010_9_TcsTax,"
            + "SUM(sales.TDS_PUR_V-SALES.TDS_PUR_A) as fun002_008_8_TdsOnPur,MAX(SALES.TDS_PUR_R) AS fun000_007_8_TdsRate,"
            + "Sum(sales.TDS_PUR_T) AS fun002_010_9_TdsAmount "
            + "From (((((SALES LEFT JOIN MASTER ON SALES.DEBIT=MASTER.C_HEAD) LEFT JOIN LOTE AS L ON l.LOTE=SALES.LOTE AND l.LOTESN=SALES.LOTESN) "
            + "LEFT JOIN MASTER AS GOODSAC ON l.CUST_CODE=GOODSAC.C_HEAD ) LEFT JOIN AREA ON SALES.ITEM=AREA.ACODE) LEFT JOIN TAX ON SALES.ST=TAX.TAX_CODE)  "
            + "LEFT JOIN MASTER AS AGENT ON SALES.AGENT=AGENT.C_HEAD   "
            + "WHERE not isnull(d_date)   AND ( D_Date>=#2020-04-01# AND D_DATE<=#2025-10-10# ) AND ( TYPE>='A' AND TYPE<='Z' ) "
            + "AND ( isnull(TAX.TAX_CODE1) OR TAX.TAX_CODE1='Z') AND ( AGENT.C_HEAD>0 OR AGENT.L_FLAG='Z')  "
            + "GROUP BY SALES.D_DATE,SALES.TYPE,SALES.V_NO,SALES.ENTERY ,SALES.RCM_OPT,SALES.ITC_SLB";

        System.out.println("folder=" + folder);
        long t0 = System.currentTimeMillis();
        try (Connection c = DriverManager.getConnection("jdbc:dbf:" + folder);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            int cols = rs.getMetaData().getColumnCount();
            int n = 0;
            while (rs.next()) {
                n++;
                if (n % 5000 == 0) {
                    System.out.println("  ... " + n + " rows in "
                        + (System.currentTimeMillis() - t0) + "ms");
                }
            }
            System.out.println("DONE: " + n + " rows, " + cols + " cols in "
                + (System.currentTimeMillis() - t0) + "ms");
        } catch (Throwable e) {
            System.out.println("ERROR after " + (System.currentTimeMillis() - t0) + "ms: " + e);
            Throwable cause = e.getCause();
            if (cause != null) System.out.println("  caused by: " + cause);
        }
    }
}
