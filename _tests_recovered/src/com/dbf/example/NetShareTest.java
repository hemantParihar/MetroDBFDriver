package com.dbf.example;

import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import com.dbf.jdbc.tx.LockScheme;
import com.dbf.jdbc.tx.WriteLock;

/**
 * Exercises the driver over a network (UNC/SMB) data folder.
 *  crud <folder>   - create/batch-insert/scan/update/delete with timings
 *  hold <folder>   - acquire the Clipper write lock on BENCH.DBF, hold 4s (for
 *                    a second process on "another machine" to probe against)
 *  probe <folder>  - try to take the same Clipper lock region; reports whether
 *                    the other process's lock is visible through SMB
 */
public class NetShareTest {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        String folder = args[1].replace('\\', '/');

        if (mode.equals("crud")) {
            crud(folder);
        } else if (mode.equals("hold")) {
            WriteLock lock = LockScheme.CLIPPER.acquire(folder + "/bench.dbf");
            System.out.println("HOLDING");
            System.out.flush();
            Thread.sleep(4000);
            lock.close();
            System.out.println("RELEASED");
        } else if (mode.equals("probe")) {
            try (RandomAccessFile raf = new RandomAccessFile(folder + "/bench.dbf", "rw")) {
                FileLock l = raf.getChannel().tryLock(1_000_000_000L, 1_000_000_000L, false);
                if (l == null) {
                    System.out.println("PROBE: region LOCKED by another process (SMB propagated)");
                } else {
                    l.release();
                    System.out.println("PROBE: region FREE");
                }
            } catch (Exception e) {
                System.out.println("PROBE: lock refused (" + e.getClass().getSimpleName()
                    + ") -> treated as LOCKED");
            }
        }
    }

    private static void crud(String folder) throws Exception {
        String url = "jdbc:dbf:" + folder;
        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {
            try { st.executeUpdate("DROP TABLE bench"); } catch (Exception ignore) {}

            long t0 = System.currentTimeMillis();
            st.executeUpdate("CREATE TABLE bench (ID NUMERIC(8), NAME CHAR(30), AMT NUMERIC(12,2), DT DATE)");
            System.out.println("create table      = " + (System.currentTimeMillis() - t0) + "ms");

            t0 = System.currentTimeMillis();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO bench (ID,NAME,AMT,DT) VALUES (?,?,?,?)")) {
                for (int i = 1; i <= 5000; i++) {
                    ps.setInt(1, i); ps.setString(2, "CUSTOMER " + i);
                    ps.setDouble(3, i * 1.5); ps.setString(4, "2025-10-10");
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            System.out.println("batch insert 5000 = " + (System.currentTimeMillis() - t0) + "ms");

            for (int run = 0; run < 2; run++) {
                t0 = System.currentTimeMillis();
                int n = 0;
                try (ResultSet rs = st.executeQuery("SELECT ID,NAME,AMT FROM bench WHERE AMT>3000")) {
                    while (rs.next()) n++;
                }
                System.out.println("filtered scan r" + run + "  = "
                    + (System.currentTimeMillis() - t0) + "ms (" + n + " rows)");
            }

            t0 = System.currentTimeMillis();
            int u = st.executeUpdate("UPDATE bench SET AMT=0 WHERE ID=2500");
            System.out.println("keyed update      = " + (System.currentTimeMillis() - t0) + "ms (" + u + " row)");

            t0 = System.currentTimeMillis();
            int d = st.executeUpdate("DELETE FROM bench WHERE ID=2501");
            System.out.println("delete            = " + (System.currentTimeMillis() - t0) + "ms (" + d + " row)");

            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM bench WHERE AMT=0")) {
                rs.next();
                System.out.println("verify update visible: " + (rs.getInt(1) == 1 ? "OK" : "FAIL"));
            }
        }
    }
}
