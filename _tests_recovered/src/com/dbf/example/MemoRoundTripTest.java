package com.dbf.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Verifies memo (.DBT) round-trips with the format rules ported from
 * the DANS DBF library: ASCII block numbers in the .DBF record, 0x1A
 * terminators, multi-block memos, next-free-block accounting.
 */
public class MemoRoundTripTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("dbf-memo-test");
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');

        String shortMemo = "A short note.";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append("Line ").append(i).append(": the quick brown fox jumps over the lazy dog. ");
        }
        String longMemo = sb.toString(); // ~3 KB -> spans several 512-byte blocks
        String thirdMemo = "Third memo, written after the long one.";

        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {

            stmt.executeUpdate("CREATE TABLE notes (ID NUMERIC(10), BODY MEMO)");
            check(".DBT created alongside table", Files.exists(dir.resolve("notes.dbt")));

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO notes (ID, BODY) VALUES (?, ?)")) {
                ps.setInt(1, 1);
                ps.setString(2, shortMemo);
                ps.addBatch();
                ps.setInt(1, 2);
                ps.setString(2, longMemo);
                ps.addBatch();
                ps.setInt(1, 3);
                ps.setString(2, thirdMemo);
                ps.addBatch();
                ps.executeBatch();
            }

            // Block number must be ASCII digits in the .dbf record
            byte[] dbf = Files.readAllBytes(dir.resolve("notes.dbf"));
            int headerSize = (dbf[8] & 0xFF) | ((dbf[9] & 0xFF) << 8);
            int recordSize = (dbf[10] & 0xFF) | ((dbf[11] & 0xFF) << 8);
            String memoField = new String(dbf, headerSize + 1 + 10, 10, "US-ASCII").trim();
            check("memo block number stored as ASCII digits ('" + memoField + "')",
                memoField.matches("\\d+"));

            verifyContent(stmt, shortMemo, longMemo, thirdMemo, "same connection");
        }

        // Reopen from scratch: everything must come back from disk
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {
            verifyContent(stmt, shortMemo, longMemo, thirdMemo, "fresh connection");

            // UPDATE writes a replacement memo
            stmt.executeUpdate("UPDATE notes SET BODY = 'replaced text' WHERE ID = 1");
            try (ResultSet rs = stmt.executeQuery("SELECT BODY FROM notes WHERE ID = 1")) {
                check("updated memo reads back", rs.next()
                    && "replaced text".equals(rs.getString(1)));
            }
            // The other memos must be untouched
            try (ResultSet rs = stmt.executeQuery("SELECT BODY FROM notes WHERE ID = 2")) {
                check("long memo survives unrelated update", rs.next()
                    && longMemo.equals(rs.getString(1)));
            }
        }

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void verifyContent(Statement stmt, String shortMemo, String longMemo,
            String thirdMemo, String label) throws SQLException {
        try (ResultSet rs = stmt.executeQuery("SELECT ID, BODY FROM notes ORDER BY ID")) {
            check(label + ": row 1 present", rs.next());
            check(label + ": short memo round-trips", shortMemo.equals(rs.getString(2)));
            check(label + ": row 2 present", rs.next());
            String got = rs.getString(2);
            check(label + ": multi-block memo round-trips (" + longMemo.length()
                + " chars)", longMemo.equals(got));
            check(label + ": row 3 present", rs.next());
            check(label + ": memo after multi-block one is intact",
                thirdMemo.equals(rs.getString(2)));
        }
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
