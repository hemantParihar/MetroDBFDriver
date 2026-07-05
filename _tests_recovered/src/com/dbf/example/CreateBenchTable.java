package com.dbf.example;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/** Creates an empty BENCH.DBF in the given folder for the Jet/ODBC vs driver A/B. */
public class CreateBenchTable {
    public static void main(String[] args) throws Exception {
        String folder = args[0];
        try (Connection c = DriverManager.getConnection("jdbc:dbf:" + folder.replace('\\', '/'));
             Statement st = c.createStatement()) {
            try { st.executeUpdate("DROP TABLE bench"); } catch (Exception ignore) {}
            st.executeUpdate("CREATE TABLE bench (ID NUMERIC(8), NAME CHAR(30), AMT NUMERIC(12,2), DT DATE)");
        }
        System.out.println("created BENCH.DBF in " + folder);
    }
}
