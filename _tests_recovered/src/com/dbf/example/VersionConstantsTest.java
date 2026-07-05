package com.dbf.example;

import com.dbf.jdbc.dbf.DbfVersion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Pins the version constants ported from the DANS DBF library to their
 * documented values, and verifies the validations that consume them.
 */
public class VersionConstantsTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        // ---- constants match DANS Version.java exactly ----
        // DBASE_3(254, 19, 1, 0, 0x1a1a, 2)
        eq("dBASE III max char", DbfVersion.DBASE_3.maxCharLength(), 254);
        eq("dBASE III max number", DbfVersion.DBASE_3.maxNumberLength(), 19);
        eq("dBASE III header terminator", DbfVersion.DBASE_3.headerTerminatorLength(), 1);
        eq("dBASE III memo data offset", DbfVersion.DBASE_3.memoDataOffset(), 0);
        eq("dBASE III memo end marker", DbfVersion.DBASE_3.memoFieldEndMarker(), 0x1a1a);
        eq("dBASE III memo marker length", DbfVersion.DBASE_3.memoFieldEndMarkerLength(), 2);

        // DBASE_4(254, 20, 1, 8, 0x00, 0)
        eq("dBASE IV max number", DbfVersion.DBASE_4.maxNumberLength(), 20);
        eq("dBASE IV memo data offset", DbfVersion.DBASE_4.memoDataOffset(), 8);
        eq("dBASE IV memo marker length", DbfVersion.DBASE_4.memoFieldEndMarkerLength(), 0);

        // CLIPPER_5(1024, 19, 2, 0, 0x1a, 1)
        eq("Clipper 5 max char", DbfVersion.CLIPPER_5.maxCharLength(), 1024);
        eq("Clipper 5 header terminator", DbfVersion.CLIPPER_5.headerTerminatorLength(), 2);
        eq("Clipper 5 memo marker length", DbfVersion.CLIPPER_5.memoFieldEndMarkerLength(), 1);

        // FOXPRO_26(254, 20, 1, 8, 0x00, 0)
        eq("FoxPro 2.6 max number", DbfVersion.FOXPRO_26.maxNumberLength(), 20);
        eq("FoxPro 2.6 memo data offset", DbfVersion.FOXPRO_26.memoDataOffset(), 8);

        // ---- version byte mapping (DANS getVersionByte) ----
        eq("dBASE III no-memo byte", DbfVersion.DBASE_3.versionByte(false), 0x03);
        eq("dBASE III memo byte", DbfVersion.DBASE_3.versionByte(true), 0x83);
        eq("dBASE IV memo byte", DbfVersion.DBASE_4.versionByte(true), 0x8B);
        eq("FoxPro memo byte", DbfVersion.FOXPRO_26.versionByte(true), 0xF5);

        // ---- field-type legality per version ----
        check("dBASE III allows C/N/D/L/M",
            DbfVersion.DBASE_3.supportsType('C') && DbfVersion.DBASE_3.supportsType('M'));
        check("dBASE III rejects Float", !DbfVersion.DBASE_3.supportsType('F'));
        check("dBASE IV allows Float", DbfVersion.DBASE_4.supportsType('F'));
        check("dBASE 5 allows General/Binary",
            DbfVersion.DBASE_5.supportsType('G') && DbfVersion.DBASE_5.supportsType('B'));
        check("FoxPro allows Picture", DbfVersion.FOXPRO_26.supportsType('P'));

        // ---- version detection from header ----
        check("Clipper detected by 2-byte terminator",
            DbfVersion.fromHeader(0x03, 32 + 32 + 2) == DbfVersion.CLIPPER_5);
        check("0x83 detected as dBASE III",
            DbfVersion.fromHeader(0x83, 32 + 32 + 1) == DbfVersion.DBASE_3);
        check("0xF5 detected as FoxPro",
            DbfVersion.fromHeader(0xF5, 32 + 32 + 1) == DbfVersion.FOXPRO_26);

        // ---- constants are actually used by CREATE TABLE validation ----
        Path dir = Files.createTempDirectory("dbf-version-test");
        String url = "jdbc:dbf:" + dir.toString().replace('\\', '/');
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {

            // dBASE III char max is 254 (the message should name the version)
            expectMessageContains(stmt, "CREATE TABLE t1 (X CHAR(300))",
                "dBASE III", "CHAR over limit names the version");

            // dBASE III number max is 19 (not 20 - that would be dBASE IV)
            expectFails(stmt, "CREATE TABLE t2 (X NUMERIC(20))",
                "NUMERIC(20) rejected for dBASE III (max 19)");
            stmt.executeUpdate("CREATE TABLE t3 (X NUMERIC(19))");
            check("NUMERIC(19) accepted for dBASE III", Files.exists(dir.resolve("t3.dbf")));
        }

        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void eq(String name, int actual, int expected) {
        if (actual == expected) {
            passed++;
            System.out.println("PASS: " + name + " = " + expected);
        } else {
            failed++;
            System.out.println("FAIL: " + name + " expected " + expected + " got " + actual);
        }
    }

    private static void expectFails(Statement stmt, String sql, String name) {
        try {
            stmt.executeUpdate(sql);
            failed++;
            System.out.println("FAIL: " + name + " (no exception)");
        } catch (SQLException e) {
            passed++;
            System.out.println("PASS: " + name);
        }
    }

    private static void expectMessageContains(Statement stmt, String sql, String fragment,
            String name) {
        try {
            stmt.executeUpdate(sql);
            failed++;
            System.out.println("FAIL: " + name + " (no exception)");
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains(fragment)) {
                passed++;
                System.out.println("PASS: " + name);
            } else {
                failed++;
                System.out.println("FAIL: " + name + " (message: " + e.getMessage() + ")");
            }
        }
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("PASS: " + name);
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }
}
