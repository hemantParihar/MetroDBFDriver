package com.dbf.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;

/** Shows the reported metadata type of literal SELECT items in an aggregate query. */
public class MetaTypeRepro {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("dbf-meta-type");
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');

        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE t (G NUMERIC(3), V NUMERIC(10,2))");
            st.executeUpdate("INSERT INTO t (G, V) VALUES (1, 10.50)");
            st.executeUpdate("INSERT INTO t (G, V) VALUES (1, 20.00)");
            st.executeUpdate("INSERT INTO t (G, V) VALUES (2, 5.00)");

            String sql = "SELECT MAX(V) AS mx, SUM(V) AS sm, 0 AS bal, "
                + "0.0 AS balf, '' AS place, null AS nope, G AS grp "
                + "FROM t GROUP BY G ORDER BY MAX(V)";

            try (ResultSet rs = st.executeQuery(sql)) {
                ResultSetMetaData m = rs.getMetaData();
                System.out.printf("%-8s %-12s %-10s %-22s%n", "COLUMN", "TYPE_NAME", "sqlType", "className");
                for (int i = 1; i <= m.getColumnCount(); i++) {
                    System.out.printf("%-8s %-12s %-10d %-22s%n",
                        m.getColumnLabel(i),
                        m.getColumnTypeName(i),
                        m.getColumnType(i),
                        m.getColumnClassName(i));
                }
                System.out.println("\n(reference: NUMERIC=" + Types.NUMERIC + " CHAR=" + Types.CHAR
                    + " OTHER=" + Types.OTHER + " NULL=" + Types.NULL + ")");
            }
        }
    }
}
