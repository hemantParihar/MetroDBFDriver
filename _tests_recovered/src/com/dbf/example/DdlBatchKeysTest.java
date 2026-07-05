package com.dbf.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Verifies CREATE TABLE / DROP TABLE, batched inserts through a single
 * writer, and getGeneratedKeys() returning RECNOs.
 */
public class DdlBatchKeysTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("dbf-ddl-test");
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');

        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {

            // ---- CREATE TABLE ----
            stmt.executeUpdate("CREATE TABLE cust (ID NUMERIC(10), NAME CHAR(20), "
                + "BAL NUMERIC(12,2), DOB DATE, ACTIVE LOGICAL)");
            Path dbf = dir.resolve("cust.dbf");
            check("CREATE TABLE creates the file", Files.exists(dbf));

            byte[] header = Files.readAllBytes(dbf);
            check("created file is dBASE III format (0x03)", header[0] == 0x03);
            check("created file ends with EOF marker", header[header.length - 1] == 0x1A);

            // Structure check through metadata
            try (ResultSet rs = stmt.executeQuery("SELECT * FROM cust WHERE 1=0")) {
                ResultSetMetaData meta = rs.getMetaData();
                check("table has 5 columns", meta.getColumnCount() == 5);
                check("NUMERIC column typed NUMERIC", "NUMERIC".equals(meta.getColumnTypeName(1)));
                check("CHAR column typed CHAR", "CHAR".equals(meta.getColumnTypeName(2)));
                check("NUMERIC(12,2) keeps scale", meta.getScale(3) == 2);
                check("DATE column typed DATE", "DATE".equals(meta.getColumnTypeName(4)));
                check("LOGICAL column typed BOOLEAN", "BOOLEAN".equals(meta.getColumnTypeName(5)));
            }

            // ---- CREATE TABLE with MEMO -> 0x83 + .DBT ----
            stmt.executeUpdate("CREATE TABLE notes (ID NUMERIC(10), BODY MEMO)");
            byte[] memoHeader = Files.readAllBytes(dir.resolve("notes.dbf"));
            check("memo table uses version 0x83", memoHeader[0] == (byte) 0x83);
            check("memo table gets a .DBT file", Files.exists(dir.resolve("notes.dbt")));

            // ---- Batched PreparedStatement INSERT ----
            final int BATCH = 5000;
            long t = System.currentTimeMillis();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO cust (ID, NAME, BAL) VALUES (?, ?, ?)")) {
                for (int i = 1; i <= BATCH; i++) {
                    ps.setInt(1, i);
                    ps.setString(2, "CUST-" + i);
                    ps.setDouble(3, i * 1.5);
                    ps.addBatch();
                }
                int[] results = ps.executeBatch();
                check("executeBatch returns " + BATCH + " results", results.length == BATCH);
                boolean allOne = true;
                for (int r : results) {
                    if (r != 1) allOne = false;
                }
                check("every batch row reports 1", allOne);

                // ---- getGeneratedKeys returns RECNOs ----
                long expected = 1;
                boolean keysSequential = true;
                long keyCount = 0;
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    while (keys.next()) {
                        if (keys.getLong("RECNO") != expected++) keysSequential = false;
                        keyCount++;
                    }
                }
                check("generated keys count = " + BATCH, keyCount == BATCH);
                check("generated keys are sequential RECNOs 1..N", keysSequential);
            }
            System.out.println("  (batch insert of " + BATCH + " rows took "
                + (System.currentTimeMillis() - t) + "ms)");

            // ---- data round-trip ----
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS c FROM cust")) {
                rs.next();
                check("COUNT(*) = " + BATCH + " after batch", rs.getLong(1) == BATCH);
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT NAME, BAL FROM cust WHERE ID = 4321")) {
                check("row 4321 found", rs.next());
                check("string column round-trips", "CUST-4321".equals(rs.getString(1).trim()));
                check("numeric column round-trips", Math.abs(rs.getDouble(2) - 6481.5) < 0.001);
            }

            // ---- plain Statement INSERT also reports its key ----
            stmt.executeUpdate("INSERT INTO cust (ID, NAME) VALUES (99999, 'LAST')");
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                check("statement insert returns a key", keys.next());
                check("key is RECNO " + (BATCH + 1), keys.getLong(1) == BATCH + 1);
            }

            // ---- ORDER BY metadata keeps real types (caveat fix) ----
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT ID, NAME FROM cust ORDER BY ID DESC")) {
                ResultSetMetaData meta = rs.getMetaData();
                check("ORDER BY metadata: numeric col is NUMERIC",
                    "NUMERIC".equals(meta.getColumnTypeName(1)));
                check("ORDER BY metadata: char col is CHAR",
                    "CHAR".equals(meta.getColumnTypeName(2)));
                check("ORDER BY DESC first row is 99999", rs.next() && rs.getInt(1) == 99999);
            }

            // ---- DROP TABLE ----
            stmt.executeUpdate("DROP TABLE cust");
            check("DROP TABLE removes the file", !Files.exists(dbf));
            stmt.executeUpdate("DROP TABLE IF EXISTS cust"); // must not throw
            check("DROP TABLE IF EXISTS on missing table is silent", true);

            boolean threw = false;
            try {
                stmt.executeUpdate("DROP TABLE cust");
            } catch (SQLException e) {
                threw = true;
            }
            check("DROP TABLE on missing table throws", threw);

            stmt.executeUpdate("DROP TABLE notes");
            check("memo .DBT removed with table", !Files.exists(dir.resolve("notes.dbt")));
        }

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("PASS: " + name);
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }
}
