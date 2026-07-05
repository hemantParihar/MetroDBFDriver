package com.dbf.example;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;

import com.dbf.jdbc.parser.Lexer;
import com.dbf.jdbc.parser.Parser;

public class WorkingDBFTest {
    public static void main(String[] args) {
        try {
            // Register driver
            Class.forName("com.dbf.jdbc.DBFDriver");
            System.out.println("Driver loaded");
            
            // Test the parser
            testParser();
            
            // Test JDBC connection
            testJDBC();
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private static void testParser() throws Exception {
        System.out.println("\n=== Testing Parser ===");
        String sql = "SELECT RECNO(), * FROM master";
        
        Lexer lexer = new Lexer(new StringReader(sql));
        Parser parser = new Parser(new StringReader(sql));
        parser.parseSelect();
        
        System.out.println("✓ Parser successful!");
    }
    
    private static void testJDBC() throws Exception {
        System.out.println("\n=== Testing JDBC ===");
        String url = "jdbc:dbf:E:/METRO/PA25";
        String sql = "SELECT RECNO(), * FROM master";
        
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            
            System.out.println("Columns (" + colCount + "):");
            for (int i = 1; i <= colCount; i++) {
                System.out.println("  " + i + ": " + meta.getColumnName(i) + 
                    " (" + meta.getColumnTypeName(i) + ")");
            }
            
            System.out.println("\nData (first 10 rows):");
            int rowCount = 0;
            while (rs.next() && rowCount < 10) {
                System.out.print("Row " + (rowCount + 1) + ": ");
                System.out.print("[RECNO=" + rs.getInt("RECNO") + "] ");
                for (int i = 2; i <= colCount; i++) {
                    String val = rs.getString(i);
                    System.out.print((val != null ? val : "NULL") + " ");
                }
                System.out.println();
                rowCount++;
            }
            
            System.out.println("\n✓ Query successful!");
        }
    }
}