package com.dbf.example;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;

import com.dbf.jdbc.dbf.DBFReader;
import com.dbf.jdbc.index.ntx.NtxPlanner;

import java.nio.charset.StandardCharsets;

/** Shows the index introspection for a table: indexes, fields, SQL syntax. */
public class IndexInfoRun {
    public static void main(String[] args) throws Exception {
        String folder = args.length > 0 ? args[0] : "E:/METRO/sg20";
        String table = args.length > 1 ? args[1] : "MASTER";

        System.out.println("== DatabaseMetaData.getIndexInfo(" + table + ") ==");
        try (Connection conn = DriverManager.getConnection("jdbc:dbf:" + folder)) {
            DatabaseMetaData md = conn.getMetaData();
            try (ResultSet rs = md.getIndexInfo(null, null, table, false, true)) {
                while (rs.next()) {
                    System.out.println("  index=" + rs.getString("INDEX_NAME")
                        + "  ord=" + rs.getShort("ORDINAL_POSITION")
                        + "  column=" + rs.getString("COLUMN_NAME")
                        + "  key=[" + rs.getString("FILTER_CONDITION") + "]");
                }
            }
        }

        System.out.println();
        System.out.println("== Synthesized CREATE INDEX statements ==");
        try (DBFReader reader = new DBFReader(folder + "/" + table + ".DBF",
                StandardCharsets.ISO_8859_1)) {
            for (NtxPlanner.IndexInfo info
                    : NtxPlanner.describeIndexes(folder, table, reader.getHeader().getFields())) {
                System.out.println("  " + info.fileName + ":  " + info.createIndexSql);
            }
        }
    }
}
