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
 * Memory stress test for the partitioned hash join. Generates CUST
 * (100,000 rows) and ORDERS (500,000 rows) and joins them. Run with a
 * small heap (-Xmx32m) to prove memory stays bounded.
 *
 * Data layout: CUST codes are 1..100000; ORDERS reference only codes
 * 1..50000, ten orders each. So:
 *   INNER JOIN  -> 500,000 rows
 *   CUST LEFT JOIN ORDERS -> 500,000 matched + 50,000 unmatched = 550,000
 */
public class JoinBigTest {
    private static final int CUSTS = 100_000;
    private static final int ORDERS = 500_000;

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(System.getProperty("java.io.tmpdir"), "dbf-join-test");
        Files.createDirectories(dir);

        if (!Files.exists(dir.resolve("cust.dbf"))) {
            System.out.println("Generating test tables...");
            generateCust(dir.resolve("cust.dbf"));
            generateOrders(dir.resolve("orders.dbf"));
        }
        System.out.println("heap max: " + (Runtime.getRuntime().maxMemory() / 1024 / 1024)
            + " MB, orders file: " + (Files.size(dir.resolve("orders.dbf")) / 1024 / 1024) + " MB");

        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');
        int passed = 0;
        int failed = 0;

        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {

            // INNER JOIN: every order matches exactly one customer
            long t = System.currentTimeMillis();
            long n = 0;
            boolean keysMatch = true;
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT * FROM orders JOIN cust ON OCODE = CODE")) {
                int ocodeIdx = rs.findColumn("OCODE");
                int codeIdx = rs.findColumn("CODE");
                while (rs.next()) {
                    if (rs.getLong(ocodeIdx) != rs.getLong(codeIdx)) keysMatch = false;
                    n++;
                }
            }
            if (n == ORDERS && keysMatch) {
                passed++;
                System.out.println("PASS: INNER JOIN returned " + n + " correctly-keyed rows in "
                    + (System.currentTimeMillis() - t) + "ms");
            } else {
                failed++;
                System.out.println("FAIL: INNER JOIN rows=" + n + " keysMatch=" + keysMatch);
            }

            // LEFT JOIN: matched orders plus customers with no orders
            t = System.currentTimeMillis();
            n = 0;
            long unmatched = 0;
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT * FROM cust LEFT JOIN orders ON CODE = OCODE")) {
                int ocodeIdx = rs.findColumn("OCODE");
                while (rs.next()) {
                    if (rs.getObject(ocodeIdx) == null) unmatched++;
                    n++;
                }
            }
            if (n == ORDERS + (CUSTS / 2) && unmatched == CUSTS / 2) {
                passed++;
                System.out.println("PASS: LEFT JOIN returned " + n + " rows (" + unmatched
                    + " unmatched) in " + (System.currentTimeMillis() - t) + "ms");
            } else {
                failed++;
                System.out.println("FAIL: LEFT JOIN rows=" + n + " unmatched=" + unmatched
                    + " (expected " + (ORDERS + CUSTS / 2) + " / " + (CUSTS / 2) + ")");
            }

            // JOIN with WHERE filter after it
            t = System.currentTimeMillis();
            n = 0;
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT * FROM orders JOIN cust ON OCODE = CODE WHERE CODE <= 100")) {
                while (rs.next()) n++;
            }
            if (n == 1000) { // 100 customers x 10 orders each
                passed++;
                System.out.println("PASS: JOIN + WHERE returned " + n + " rows in "
                    + (System.currentTimeMillis() - t) + "ms");
            } else {
                failed++;
                System.out.println("FAIL: JOIN + WHERE rows=" + n + " (expected 1000)");
            }
        }

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    /** CUST: CODE N(10), CNAME C(30). Codes 1..CUSTS. */
    private static void generateCust(Path file) throws Exception {
        try (DataOutputStream out = open(file, CUSTS, 1 + 10 + 30)) {
            writeFieldDescriptor(out, "CODE", 'N', 10);
            writeFieldDescriptor(out, "CNAME", 'C', 30);
            out.writeByte(0x0D);
            for (int i = 1; i <= CUSTS; i++) {
                out.writeByte(0x20);
                out.write(String.format("%10d", i).getBytes(StandardCharsets.US_ASCII));
                out.write(padRight("CUSTOMER-" + i, 30));
            }
            out.writeByte(0x1A);
        }
    }

    /** ORDERS: OCODE N(10), AMT N(12). References codes 1..CUSTS/2. */
    private static void generateOrders(Path file) throws Exception {
        try (DataOutputStream out = open(file, ORDERS, 1 + 10 + 12)) {
            writeFieldDescriptor(out, "OCODE", 'N', 10);
            writeFieldDescriptor(out, "AMT", 'N', 12);
            out.writeByte(0x0D);
            for (int i = 0; i < ORDERS; i++) {
                out.writeByte(0x20);
                int code = (i % (CUSTS / 2)) + 1;
                out.write(String.format("%10d", code).getBytes(StandardCharsets.US_ASCII));
                out.write(String.format("%12d", i).getBytes(StandardCharsets.US_ASCII));
            }
            out.writeByte(0x1A);
        }
    }

    private static DataOutputStream open(Path file, int rows, int recordSize) throws Exception {
        DataOutputStream out = new DataOutputStream(
            new BufferedOutputStream(new FileOutputStream(file.toFile()), 1 << 20));
        int headerSize = 32 + 2 * 32 + 1;
        out.writeByte(0x03);
        out.writeByte(126); out.writeByte(6); out.writeByte(12);
        out.writeByte(rows & 0xFF); out.writeByte((rows >>> 8) & 0xFF);
        out.writeByte((rows >>> 16) & 0xFF); out.writeByte((rows >>> 24) & 0xFF);
        out.writeByte(headerSize & 0xFF); out.writeByte((headerSize >>> 8) & 0xFF);
        out.writeByte(recordSize & 0xFF); out.writeByte((recordSize >>> 8) & 0xFF);
        out.write(new byte[20]);
        return out;
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

    private static byte[] padRight(String s, int length) {
        byte[] result = new byte[length];
        byte[] src = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(src, 0, result, 0, Math.min(src.length, length));
        for (int i = src.length; i < length; i++) result[i] = ' ';
        return result;
    }
}
