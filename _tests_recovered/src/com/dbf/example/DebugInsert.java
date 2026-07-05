package com.dbf.example;

import java.nio.file.*;
import java.sql.*;

public class DebugInsert {
    public static void main(String[] a) throws Exception {
        Path dir = Files.createTempDirectory("dbg");
        Files.copy(Paths.get("E:/METRO/PA25/AREA.DBF"), dir.resolve("area.dbf"));
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) {
            ResultSetMetaData m = s.executeQuery("SELECT * FROM area WHERE 1=0").getMetaData();
            String col = null;
            for (int i = 1; i <= Math.min(5, m.getColumnCount()); i++) {
                System.out.println(i + ": " + m.getColumnName(i) + " " + m.getColumnTypeName(i));
                if (col == null && "VARCHAR".equals(m.getColumnTypeName(i))) col = m.getColumnName(i);
            }
            System.out.println("using col: " + col);
            int n = s.executeUpdate("INSERT INTO area (" + col + ") VALUES ('ZZTEST')");
            System.out.println("insert returned: " + n);
            ResultSet rs = s.executeQuery("SELECT RECNO(), * FROM area WHERE RECNO() = 89");
            if (rs.next()) {
                for (int i = 1; i <= 6; i++) System.out.println("col" + i + " = [" + rs.getString(i) + "]");
            } else {
                System.out.println("row 89 NOT FOUND");
            }
            byte[] b = Files.readAllBytes(dir.resolve("area.dbf"));
            int hdr = (b[8] & 0xFF) | ((b[9] & 0xFF) << 8);
            int rec = (b[10] & 0xFF) | ((b[11] & 0xFF) << 8);
            int off = hdr + 88 * rec;
            System.out.println("len=" + b.length + " expected=" + (off + rec + 1));
            StringBuilder sb = new StringBuilder();
            for (int i = off; i < off + 40 && i < b.length; i++) sb.append(String.format("%02X ", b[i]));
            System.out.println("record89 bytes: " + sb);
        }
    }
}
