package com.dbf.example;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/** Populates BENCH then full-scans it with our driver (compare against JetReadBench). */
public class ScanBench {
    public static void main(String[] args) throws Exception {
        String dir = args[0];
        int rows = args.length > 1 ? Integer.parseInt(args[1]) : 50000;
        String url = "jdbc:dbf:" + dir.replace('\\', '/');
        try (Connection c = DriverManager.getConnection(url)) {
            try (Statement st = c.createStatement()) {
                try { st.executeUpdate("DROP TABLE bench"); } catch (Exception ignore) {}
                st.executeUpdate("CREATE TABLE bench (ID NUMERIC(8), NAME CHAR(30), AMT NUMERIC(12,2), DT DATE)");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO bench (ID,NAME,AMT,DT) VALUES (?,?,?,?)")) {
                for (int i = 1; i <= rows; i++) {
                    ps.setInt(1, i); ps.setString(2, "CUSTOMER NUMBER " + i);
                    ps.setDouble(3, i * 1.5); ps.setString(4, "2025-10-10");
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            for (int run = 0; run < 2; run++) {
                long t0 = System.currentTimeMillis();
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT ID,NAME,AMT,DT FROM bench")) {
                    int n = 0; long sum = 0;
                    while (rs.next()) { n++; sum += rs.getInt("ID"); rs.getString("NAME"); rs.getDouble("AMT"); }
                    System.out.println("OUR scan run" + run + ": " + n + " rows in "
                        + (System.currentTimeMillis() - t0) + "ms (checksum=" + sum + ")");
                }
            }
        }
    }
}
