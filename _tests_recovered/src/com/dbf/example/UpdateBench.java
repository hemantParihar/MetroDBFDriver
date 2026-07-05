package com.dbf.example;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;

/** Populates BENCH then runs N single-row UPDATEs by ID (compare against JetUpdateBench). */
public class UpdateBench {
    public static void main(String[] args) throws Exception {
        String dir = args[0];
        int tableRows = args.length > 1 ? Integer.parseInt(args[1]) : 20000;
        int updates = args.length > 2 ? Integer.parseInt(args[2]) : 1000;
        String url = "jdbc:dbf:" + dir.replace('\\', '/');
        try (Connection c = DriverManager.getConnection(url)) {
            try (Statement st = c.createStatement()) {
                try { st.executeUpdate("DROP TABLE bench"); } catch (Exception ignore) {}
                st.executeUpdate("CREATE TABLE bench (ID NUMERIC(8), NAME CHAR(30), AMT NUMERIC(12,2), DT DATE)");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO bench (ID,NAME,AMT,DT) VALUES (?,?,?,?)")) {
                for (int i = 1; i <= tableRows; i++) {
                    ps.setInt(1, i); ps.setString(2, "CUST " + i);
                    ps.setDouble(3, i * 1.5); ps.setString(4, "2025-10-10");
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            long t0 = System.currentTimeMillis();
            try (PreparedStatement ps = c.prepareStatement("UPDATE bench SET AMT=? WHERE ID=?")) {
                for (int i = 1; i <= updates; i++) {
                    ps.setDouble(1, i + 0.99);
                    ps.setInt(2, i);
                    ps.executeUpdate();
                }
            }
            long ms = System.currentTimeMillis() - t0;
            System.out.println("OUR " + updates + " single-row UPDATEs (table " + tableRows + ") in "
                + ms + "ms (" + Math.round(updates / (ms / 1000.0)) + " upd/sec)");
        }
    }
}
