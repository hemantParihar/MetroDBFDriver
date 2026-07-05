package com.dbf.example;

import java.io.BufferedReader;
import java.io.FileReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;

/** Our-driver timing of an arbitrary SQL (read from a file). */
public class QueryBench {
    public static void main(String[] args) throws Exception {
        String folder = args[0];
        String sql = readFile(args[1]);
        int runs = args.length > 2 ? Integer.parseInt(args[2]) : 3;
        String url = "jdbc:dbf:" + folder + ";charset=Cp1252";
        try (Connection c = DriverManager.getConnection(url)) {
            for (int run = 0; run < runs; run++) {
                long t0 = System.currentTimeMillis();
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery(sql)) {
                    ResultSetMetaData m = rs.getMetaData();
                    int cols = m.getColumnCount();
                    long n = 0, sum = 0;
                    while (rs.next()) {
                        n++;
                        for (int i = 1; i <= cols; i++) {
                            Object o = rs.getObject(i);
                            sum += (o == null ? 0 : o.toString().trim().hashCode());
                        }
                    }
                    System.out.println("OUR run" + run + ": " + n + " rows in "
                        + (System.currentTimeMillis() - t0) + "ms (checksum=" + sum + ")");
                }
            }
        }
    }

    static String readFile(String p) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(p))) {
            String line; while ((line = r.readLine()) != null) sb.append(line).append(' ');
        }
        return sb.toString().trim();
    }
}
