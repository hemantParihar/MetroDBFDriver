package com.dbf.example;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * The cross-process lock side-car must always be named {@code <TABLE>.dbf.lck},
 * regardless of how the table is quoted/spelled in the DML. Previously a
 * backtick-quoted name leaked into the file name (`MASTER`.dbf.lck), which did
 * not guard the real MASTER.dbf and littered the data folder.
 */
public class LockFileNameTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("dbf-lck");
        // This test is specifically about the side-car .lck file naming, so force
        // the sidecar scheme (the default is now the Clipper byte-range lock).
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/') + ";lockScheme=sidecar";

        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE master (C_HEAD NUMERIC(6), NAME CHAR(10))");
            st.executeUpdate("INSERT INTO master (C_HEAD,NAME) VALUES (1,'A')");
            // Backtick-quoted UPDATE: must lock MASTER.dbf.lck, not `MASTER`.dbf.lck.
            st.executeUpdate("UPDATE `MASTER` SET NAME='B' WHERE C_HEAD=1");
            // Bracket-quoted INSERT: must reuse the same MASTER.dbf.lck.
            st.executeUpdate("INSERT INTO [MASTER] (C_HEAD,NAME) VALUES (2,'X')");
        }

        File[] lcks = dir.toFile().listFiles((d, n) -> n.toLowerCase().endsWith(".lck"));
        java.util.List<String> names = new java.util.ArrayList<>();
        if (lcks != null) for (File f : lcks) names.add(f.getName());

        check("exactly one .lck file (got " + names + ")", names.size() == 1);
        check("named MASTER.dbf.lck (case-insensitive)",
            names.size() == 1 && names.get(0).equalsIgnoreCase("MASTER.dbf.lck"));
        check("no backtick in any lock file name", names.stream().noneMatch(n -> n.contains("`")));
        check("no bracket in any lock file name",
            names.stream().noneMatch(n -> n.contains("[") || n.contains("]")));

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("PASS: " + name); }
        else { failed++; System.out.println("FAIL: " + name); }
    }
}
