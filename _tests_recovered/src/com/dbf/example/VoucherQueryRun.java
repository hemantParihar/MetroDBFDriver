package com.dbf.example;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/** Runs the single-voucher 4-LEFT-JOIN detail query; proves it is memory-bounded. */
public class VoucherQueryRun {
    public static void main(String[] args) throws Exception {
        String folder = args.length > 0 ? args[0] : "E:/METRO/PA25";
        int vno = args.length > 1 ? Integer.parseInt(args[1]) : 1129;
        String sql =
            "SELECT SALES.ENTERY AS Sn, SALES.A_BAL AS InvAmount, SALES.V_NO AS VNo, "
            + "SALES.TYPE AS B, SALES.D_DATE AS Dt, L.QUALITY AS Particulars, "
            + "TAX.FORM_YN AS FormYn, TAX1.TAX_RATE AS MandiRate, MASTER.GST_PIN AS Pin "
            + "from ( ((SALES LEFT JOIN LOTE AS L ON SALES.LOTE=L.LOTE AND SALES.LOTESN=L.LOTESN) "
            + "LEFT JOIN TAX ON SALES.ST=TAX.TAX_CODE)  LEFT JOIN MASTER ON SALES.DEBIT=MASTER.C_HEAD) "
            + "LEFT JOIN TAX AS TAX1 ON SALES.MT=TAX1.TAX_CODE "
            + "Where SALES.V_NO=" + vno + " and SALES.type='A' "
            + "AND ( ISNULL(TAX.TAX_CODE1) OR TAX.TAX_CODE1='Z' ) ORDER BY SALES.ENTERY";

        System.out.println("folder=" + folder + " V_NO=" + vno
            + "  maxHeap=" + (Runtime.getRuntime().maxMemory() / (1024 * 1024)) + "MB");
        long t0 = System.currentTimeMillis();
        try (Connection c = DriverManager.getConnection("jdbc:dbf:" + folder);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            int cols = rs.getMetaData().getColumnCount();
            int n = 0;
            while (rs.next()) n++;
            System.out.println("DONE: " + n + " rows, " + cols + " cols in "
                + (System.currentTimeMillis() - t0) + "ms");
        }
    }
}
