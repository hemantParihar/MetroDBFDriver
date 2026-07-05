package com.dbf.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/** Inserts a row with a NON-EMPTY TR_WT memo into a copy of SG20 SALES, reads it back. */
public class InsertMemoDiag {
    public static void main(String[] args) throws Exception {
        String src = "E:/METRO/SG20";
        Path tmp = Files.createTempDirectory("dbf-memowrite");
        Files.copy(Paths.get(src, "SALES.DBF"), tmp.resolve("SALES.DBF"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(Paths.get(src, "SALES.DBT"), tmp.resolve("SALES.DBT"), StandardCopyOption.REPLACE_EXISTING);
        long dbtBefore = Files.size(tmp.resolve("SALES.DBT"));

        String url = "jdbc:dbf:" + tmp.toString().replace('\\', '/') + ";charset=Cp1252";
        String memo = "  1.234  5.678  9.012";

        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {
            int n = st.executeUpdate("INSERT INTO sales (`ROW_ID`,`V_NO`,`TYPE`,`ENTERY`,`D_DATE`,`TR_WT`,`WT_LIST`) "
                + "VALUES(999001,4242,'A',1,#2025-10-10#,'" + memo + "','marker50')");
            System.out.println("insert returned=" + n);
            long dbtAfter = Files.size(tmp.resolve("SALES.DBT"));
            System.out.println("DBT size before=" + dbtBefore + " after=" + dbtAfter
                + " grew=" + (dbtAfter - dbtBefore));

            try (ResultSet rs = st.executeQuery(
                    "SELECT ROW_ID, TR_WT, WT_LIST FROM SALES WHERE ROW_ID=999001")) {
                if (rs.next()) {
                    String got = rs.getString("TR_WT");
                    System.out.println("read back TR_WT=[" + got + "]");
                    System.out.println("MATCH expected memo? " + memo.equals(got));
                    System.out.println("WT_LIST=[" + rs.getString("WT_LIST") + "]");
                } else {
                    System.out.println("ROW NOT FOUND after insert");
                }
            }
        }
    }
}
