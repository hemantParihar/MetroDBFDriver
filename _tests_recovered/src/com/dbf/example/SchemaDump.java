package com.dbf.example;

import java.sql.*;

/** Dumps real column metadata for the ER diagram. */
public class SchemaDump {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:dbf:E:/METRO/PA25";
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {
            for (String table : new String[] { "TRAN", "MASTER" }) {
                System.out.println("### " + table);
                try (ResultSet rs = stmt.executeQuery("SELECT * FROM " + table + " WHERE 1=0")) {
                    ResultSetMetaData m = rs.getMetaData();
                    for (int i = 1; i <= m.getColumnCount(); i++) {
                        System.out.println(m.getColumnName(i) + "|" + m.getColumnTypeName(i)
                            + "|" + m.getPrecision(i) + "|" + m.getScale(i));
                    }
                }
                System.out.println("### END " + table);
            }
        }
    }
}
