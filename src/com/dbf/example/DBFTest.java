package com.dbf.example;



import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

import com.dbf.utility.FunUtility;



public class DBFTest {
	 private static final String FOLDER_PATH = "E:/METRO/PA25";
	    private static final String URL = "jdbc:dbf:" + FOLDER_PATH;
	public static void main(String[] args) {
		try {
			Class.forName("com.dbf.jdbc.DBFDriver");
			System.out.println("DBF Driver registered successfully!");
		} catch (ClassNotFoundException e) {
			System.err.println("Failed to load DBF Driver.");
			System.err.println("Make sure the driver JAR is in classpath.");
			System.err.println("Error: " + e.getMessage());
			return;
		}
		testCOMPLEX();
		testCOMPLEX1();
	}
	/**
     * Test SELECT with RECNO()
     */
    private static void testCOMPLEX() {
        System.out.println("\n=== SELECT with RECNO() ===");
        
        String sql="SELECT T.ENTERY,T.V_TYPE,T.V_NO,T.TYPE ,T.DEBIT,T.CREDIT,T.D_DATE,T.A_BAL,T.A_NAR ,T.LOTE,T.LOTESN,T.Y_N,T.COR_REF1,T.COR_DATE1,T.ROW_ID,T.AVN_DR,T.AVN_CR,T.B_NAR,0 AS CO_CODE,T.CODE_INT,T.INT_AMT,T.HO ,M.C_HEAD AS M_C_HEAD ,M.CUST_DESC AS M_CUST_DESC ,M.Y_N AS M_Y_N ,M.M_S AS M_M_S,M.O_BAL AS M_O_BAL,M.DD_DEBIT AS M_DD_DEBIT,M.DD_CREDIT AS M_DD_CREDIT,0 AS M_AG_CL,M.ADD_1 AS M_ADD_1,M.ADD_2 AS M_ADD_2,null AS M_SUSPINT ,M1.C_HEAD AS M1_C_HEAD ,M1.CUST_DESC AS M1_CUST_DESC ,M1.Y_N AS M1_Y_N ,M1.M_S AS M1_M_S,M1.O_BAL AS M1_O_BAL,M1.DD_DEBIT AS M1_DD_DEBIT,M1.DD_CREDIT AS M1_DD_CREDIT,0 AS M1_AG_CL,M1.ADD_1 AS M1_ADD_1,M1.ADD_2 AS M1_ADD_2,null AS M1_SUSPINT From   ((TRAN T LEFT JOIN MASTER  M ON T.DEBIT=M.C_HEAD ) LEFT JOIN MASTER M1 ON T.CREDIT=M1.C_HEAD) WHERE ISNULL(D_DATE)  AND V_NO=999999 AND  (M.C_HEAD>0 OR M.L_FLAG='Z') AND  (M1.C_HEAD>0 OR M1.L_FLAG='Z') order by UCASE(V_TYPE)";

        
        try (Connection conn = DriverManager.getConnection(URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            

            while (rs.next() ) {
            	System.out.println("inside resultset");
				System.out.println(rs.getDouble("M_C_HEAD"));

            }
            
        } catch (SQLException e) {
            System.err.println("Error: " + e.getMessage());
        }
    }
    private static void testCOMPLEX1() {
        System.out.println("\n=== SELECT with RECNO() ===");
        
        String sql="SELECT * from MASTER where recno=1";

        
        try (Connection conn = DriverManager.getConnection(URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            
            while (rs.next() ) {
            	System.out.println("inside resultset");
				System.out.println(rs.getDouble("M_HEAD"));
            }
            
            
        } catch (SQLException e) {
            System.err.println("Error: " + e.getMessage());
        }
    }
}
