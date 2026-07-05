package com.dbf.example;

import java.sql.*;
import java.util.Properties;

import com.dbf.utility.FunUtility;

/**
 * DBF JDBC Driver Example using RECNO()
 * 
 * File location: E:/METRO/PA25/MASTER.DBF
 */
public class DBFExample {
    
    // CORRECTED PATH - include the PA25 subfolder
    private static final String FOLDER_PATH = "E:/METRO/PA25";
    private static final String URL = "jdbc:dbf:" + FOLDER_PATH;
    
    public static void main(String[] args) {
        // STEP 1: Register the driver
        try {
            Class.forName("com.dbf.jdbc.DBFDriver");
            System.out.println("DBF Driver registered successfully!");
        } catch (ClassNotFoundException e) {
            System.err.println("Failed to load DBF Driver.");
            System.err.println("Make sure the driver JAR is in classpath.");
            System.err.println("Error: " + e.getMessage());
            return;
        }
        
        System.out.println("=== DBF JDBC Driver Example ===");
        System.out.println("Folder: " + FOLDER_PATH);
        System.out.println();
        
        // Test connection first
        if (!testConnection()) {
            System.err.println("Cannot connect to " + FOLDER_PATH);
            System.err.println("Make sure the folder exists: E:/METRO/PA25");
            System.err.println("And MASTER.DBF exists in that folder");
            return;
        }
        
        // Run all tests
        testSelectWithRecno();
        testWhereWithRecno();
        testRecnoRange();
        testRecordCount();
        testMetadata();
    }
    
    /**
     * Test connection
     */
    private static boolean testConnection() {
        System.out.println("=== Testing Connection ===");
        try (Connection conn = DriverManager.getConnection(URL)) {
            System.out.println("✓ Connection successful to: " + FOLDER_PATH);
            return true;
        } catch (SQLException e) {
            System.err.println("✗ Connection failed: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Test SELECT with RECNO()
     */
    private static void testSelectWithRecno() {
        System.out.println("\n=== SELECT with RECNO() ===");
        
        String sql = "SELECT RECNO(), * FROM master";
        
        try (Connection conn = DriverManager.getConnection(URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            
            // Print headers
            System.out.println("\nData from MASTER.DBF:");
            for (int i = 1; i <= colCount; i++) {
                System.out.print(meta.getColumnName(i)+ "\t");
                System.out.print(meta.getColumnType(i)+ "\t");
                System.out.print(meta.getColumnDisplaySize(i)+ "\t");
                System.out.print(meta.getPrecision(i)+ "\t");
                System.out.println(meta.getScale(i) + "\t");
            }
            System.out.println();
            System.out.println(FunUtility.repeat("--------",colCount));
            
            // Print data (first 20 rows)
            int rowCount = 0;
            while (rs.next() && rowCount < 20) {
                int recno = rs.getInt("RECNO");
                System.out.print("[" + recno + "]\t");
                for (int i = 2; i <= colCount; i++) {
                    String value = rs.getString(i);
                    System.out.print((value != null ? value : "NULL") + "\t");
                }
                System.out.println();
                rowCount++;
            }
            
            if (rowCount == 0) {
                System.out.println("No records found in MASTER.DBF");
            } else {
                System.out.println("\n(Showing first " + rowCount + " records)");
            }
            
        } catch (SQLException e) {
            System.err.println("Error: " + e.getMessage());
        }
    }
    
    /**
     * Test WHERE with RECNO()
     */
    private static void testWhereWithRecno() {
        System.out.println("\n=== WHERE with RECNO() ===");
        
        // Try to find record number 5
        String sql = "SELECT RECNO(), * FROM master WHERE RECNO() = 5";
        
        try (Connection conn = DriverManager.getConnection(URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            if (rs.next()) {
                int recno = rs.getInt("RECNO");
                System.out.println("Found record #" + recno);
                
                ResultSetMetaData meta = rs.getMetaData();
                for (int i = 2; i <= meta.getColumnCount(); i++) {
                    System.out.println("  " + meta.getColumnName(i) + ": " + rs.getString(i));
                }
            } else {
                System.out.println("Record #5 not found");
            }
            
        } catch (SQLException e) {
            System.err.println("Error: " + e.getMessage());
        }
    }
    
    /**
     * Test RECNO() range
     */
    private static void testRecnoRange() {
        System.out.println("\n=== RECNO() Range Query ===");
        
        String sql = "SELECT RECNO(), * FROM master WHERE RECNO() BETWEEN 1 AND 10";
        
        try (Connection conn = DriverManager.getConnection(URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            System.out.println("Records 1-10:");
            int count = 0;
            while (rs.next()) {
                int recno = rs.getInt("RECNO");
                System.out.println("  Record " + recno);
                count++;
            }
            if (count == 0) {
                System.out.println("  No records found in range 1-10");
            } else {
                System.out.println("  Total: " + count + " records");
            }
            
        } catch (SQLException e) {
            System.err.println("Error: " + e.getMessage());
        }
    }
    
    /**
     * Test total record count
     */
    private static void testRecordCount() {
        System.out.println("\n=== Total Record Count ===");
        
        String sql = "SELECT COUNT(*) AS total FROM master";
        
        try (Connection conn = DriverManager.getConnection(URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            if (rs.next()) {
                int count = rs.getInt("total");
                System.out.println("Total records in MASTER.DBF: " + count);
            }
            
        } catch (SQLException e) {
            System.err.println("Error: " + e.getMessage());
        }
    }
    
    /**
     * Test metadata
     */
    private static void testMetadata() {
        System.out.println("\n=== Table Metadata ===");
        
        try (Connection conn = DriverManager.getConnection(URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM master WHERE 1=0")) {
            
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            
            System.out.println("MASTER.DBF Structure:");
            System.out.println("Total columns: " + colCount);
            System.out.println();
            
            for (int i = 1; i <= colCount; i++) {
                System.out.println("Column " + i + ":");
                System.out.println("  Name: " + meta.getColumnName(i));
                System.out.println("  Type: " + meta.getColumnTypeName(i));
                System.out.println("  Class: " + meta.getColumnClassName(i));
                System.out.println();
            }
            
        } catch (SQLException e) {
            System.err.println("Error: " + e.getMessage());
        }
    }
}