package com.dbf.example;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Memory stress test. Generates a large DBF (default 500,000 rows,
 * ~45 MB) and runs queries against it. Intended to be run with a small
 * heap (e.g. -Xmx64m) to prove that query paths stay within bounded memory.
 *
 * Usage: java BigFileTest [rows] [scan|sort|all]
 */
public class BigFileTest {
    private static final int NAME_LEN = 80;
    private static final int NUM_LEN = 10;

    public static void main(String[] args) throws Exception {
        int rows = args.length > 0 ? Integer.parseInt(args[0]) : 500_000;
        String mode = args.length > 1 ? args[1] : "all";

        Path dir = Paths.get(System.getProperty("java.io.tmpdir"), "dbf-bigfile-test");
        Files.createDirectories(dir);
        Path dbf = dir.resolve("big.dbf");

        if (!Files.exists(dbf) || Files.size(dbf) < (long) rows * (1 + NAME_LEN + NUM_LEN)) {
            System.out.println("Generating " + rows + " rows at " + dbf + " ...");
            generate(dbf, rows);
        }
        System.out.println("File size: " + (Files.size(dbf) / 1024 / 1024) + " MB, heap max: "
            + (Runtime.getRuntime().maxMemory() / 1024 / 1024) + " MB");

        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');

        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {

            if (mode.equals("scan") || mode.equals("all")) {
                long t = System.currentTimeMillis();
                long n = 0;
                try (ResultSet rs = stmt.executeQuery("SELECT * FROM big")) {
                    while (rs.next()) n++;
                }
                System.out.println("PASS: streaming scan of " + n + " rows in "
                    + (System.currentTimeMillis() - t) + "ms");
            }

            if (mode.equals("agg") || mode.equals("all")) {
                long t = System.currentTimeMillis();
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS c, MAX(NUM) AS mx FROM big")) {
                    rs.next();
                    System.out.println("PASS: aggregate over " + rs.getLong(1) + " rows (max="
                        + rs.getDouble(2) + ") in " + (System.currentTimeMillis() - t) + "ms");
                }
            }

            if (mode.equals("sort") || mode.equals("all")) {
                long t = System.currentTimeMillis();
                long n = 0;
                String prev = null;
                boolean ordered = true;
                try (ResultSet rs = stmt.executeQuery("SELECT NAME FROM big ORDER BY NAME")) {
                    while (rs.next()) {
                        String cur = rs.getString(1);
                        if (prev != null && prev.compareTo(cur) > 0) ordered = false;
                        prev = cur;
                        n++;
                    }
                }
                System.out.println((ordered ? "PASS" : "FAIL") + ": ORDER BY streamed " + n
                    + " rows, ordered=" + ordered + ", in " + (System.currentTimeMillis() - t) + "ms");
            }
        }
    }

    /** Writes a dBASE III file: NAME C(80), NUM N(10). */
    private static void generate(Path dbf, int rows) throws Exception {
        int recordSize = 1 + NAME_LEN + NUM_LEN;
        int headerSize = 32 + 2 * 32 + 1;

        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(dbf.toFile()), 1 << 20))) {
            // Fixed header
            out.writeByte(0x03);
            out.writeByte(126); out.writeByte(6); out.writeByte(12); // 2026-06-12
            writeIntLE(out, rows);
            writeShortLE(out, headerSize);
            writeShortLE(out, recordSize);
            out.write(new byte[20]);

            // Field descriptors
            writeFieldDescriptor(out, "NAME", 'C', NAME_LEN);
            writeFieldDescriptor(out, "NUM", 'N', NUM_LEN);
            out.writeByte(0x0D);

            // Records: names in REVERSE order so ORDER BY has real work to do
            java.util.Random rnd = new java.util.Random(42);
            byte[] pad = new byte[NAME_LEN];
            for (int i = 0; i < rows; i++) {
                out.writeByte(0x20);
                String name = String.format("NAME-%09d-%04d", rows - i, rnd.nextInt(10000));
                byte[] nb = name.getBytes(StandardCharsets.US_ASCII);
                out.write(nb, 0, Math.min(nb.length, NAME_LEN));
                if (nb.length < NAME_LEN) {
                    java.util.Arrays.fill(pad, 0, NAME_LEN - nb.length, (byte) ' ');
                    out.write(pad, 0, NAME_LEN - nb.length);
                }
                String num = String.format("%10d", i % 1000);
                out.write(num.getBytes(StandardCharsets.US_ASCII));
            }
            out.writeByte(0x1A);
        }
    }

    private static void writeFieldDescriptor(DataOutputStream out, String name, char type, int length)
            throws Exception {
        byte[] nameBytes = new byte[11];
        byte[] nb = name.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(nb, 0, nameBytes, 0, nb.length);
        out.write(nameBytes);
        out.writeByte(type);
        out.write(new byte[4]);
        out.writeByte(length);
        out.writeByte(0);
        out.write(new byte[14]);
    }

    private static void writeIntLE(DataOutputStream out, int v) throws Exception {
        out.writeByte(v & 0xFF);
        out.writeByte((v >>> 8) & 0xFF);
        out.writeByte((v >>> 16) & 0xFF);
        out.writeByte((v >>> 24) & 0xFF);
    }

    private static void writeShortLE(DataOutputStream out, int v) throws Exception {
        out.writeByte(v & 0xFF);
        out.writeByte((v >>> 8) & 0xFF);
    }
}
