package com.dbf.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;

/** Times a PreparedStatement batch INSERT (run with -Ddbf.insert.bulk=off to A/B). */
public class BatchInsertBench {
    private static void runWide(Connection c, String ins, int rows, int cols) throws Exception {
        System.out.println("WIDE " + cols + " numeric cols");
        long t0 = System.currentTimeMillis();
        try (PreparedStatement ps = c.prepareStatement(ins)) {
            for (int i = 1; i <= rows; i++) {
                for (int k = 1; k <= cols; k++) ps.setDouble(k, i + k * 0.25);
                ps.addBatch();
            }
            int[] r = ps.executeBatch();
            long ms = System.currentTimeMillis() - t0;
            System.out.println("batch of " + r.length + " inserts in " + ms + "ms ("
                + String.format("%.0f", r.length / (ms / 1000.0)) + " rows/sec)");
        }
    }

    public static void main(String[] args) throws Exception {
        int rows = args.length > 0 ? Integer.parseInt(args[0]) : 20000;
        Path dir = Files.createTempDirectory("dbf-batchbench");
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');

        System.out.println("bulk=" + (System.getProperty("dbf.insert.bulk") == null ? "ON" : "OFF")
            + "  rows=" + rows);
        boolean wide = args.length > 1 && args[1].equals("wide");
        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {
            if (wide) {
                StringBuilder ct = new StringBuilder("CREATE TABLE t (ID NUMERIC(8)");
                StringBuilder cols = new StringBuilder("ID");
                StringBuilder qs = new StringBuilder("?");
                for (int k = 0; k < 60; k++) { ct.append(", N").append(k).append(" NUMERIC(15,2)");
                    cols.append(",N").append(k); qs.append(",?"); }
                ct.append(")");
                st.executeUpdate(ct.toString());
                runWide(c, "INSERT INTO t (" + cols + ") VALUES (" + qs + ")", rows, 61);
                return;
            }
            st.executeUpdate("CREATE TABLE t (ID NUMERIC(8), NAME CHAR(30), AMT NUMERIC(12,2), D DATE)");
            String ins = "INSERT INTO t (ID,NAME,AMT,D) VALUES (?,?,?,?)";

            for (int trial = 0; trial < 2; trial++) {
                st.executeUpdate("DELETE FROM t"); // reset-ish (soft delete); fresh file each trial below
            }
            // Fresh table per measured run for fairness.
            long t0 = System.currentTimeMillis();
            try (PreparedStatement ps = c.prepareStatement(ins)) {
                for (int i = 1; i <= rows; i++) {
                    ps.setInt(1, i);
                    ps.setString(2, "CUSTOMER NUMBER " + i);
                    ps.setDouble(3, i * 1.5);
                    ps.setString(4, "2025-10-10");
                    ps.addBatch();
                }
                int[] r = ps.executeBatch();
                long ms = System.currentTimeMillis() - t0;
                System.out.println("batch of " + r.length + " inserts in " + ms + "ms  ("
                    + String.format("%.0f", r.length / (ms / 1000.0)) + " rows/sec)");
            }
            try (java.sql.ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM t")) {
                rs.next();
                System.out.println("row count now = " + rs.getLong(1));
            }
        }
    }
}
