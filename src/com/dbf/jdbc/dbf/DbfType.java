package com.dbf.jdbc.dbf;

import java.sql.Types;

/**
 * Single source of truth for how a DBF field's one-character type code maps to
 * JDBC. Historically this mapping was copy-pasted into ~6 places
 * (ResultSet/Database metadata plus the streaming scan, projection and
 * materialized-row operators); editing one copy and missing the others led to
 * the ResultSet reporting one JDBC type on a plain SELECT and a different one
 * once a join, aggregate or computed column was involved.
 *
 * Every char-to-JDBC decision now lives here. To re-map a type, change the row
 * below and rebuild -- nothing else needs editing.
 *
 * <p>The {@code C -> CHAR} and {@code N -> NUMERIC} choices are deliberate (the
 * reporting layer depends on them). Note that {@code sqlType} and
 * {@code javaClassName} are intentionally independent: e.g. NUMERIC columns
 * report {@code java.lang.Double} as their class, which is what callers expect
 * from this driver -- do not "align" them without a reason.
 */
public enum DbfType {
    //       code  JDBC type       column class
    CHARACTER('C', Types.CHAR,    "java.lang.String"),
    NUMERIC  ('N', Types.NUMERIC, "java.lang.Double"),
    FLOAT    ('F', Types.NUMERIC, "java.lang.Double"),
    DOUBLE   ('O', Types.NUMERIC, "java.lang.Double"),
    DATE     ('D', Types.DATE,        "java.sql.Date"),
    LOGICAL  ('L', Types.BOOLEAN,     "java.lang.Boolean"),
    INTEGER  ('I', Types.NUMERIC,     "java.lang.Integer"),
    // Memo: a long character value. Reported as LONGVARCHAR with the type name
    // "MEMO" and Java class String -- matching what the MS Jet (Access) engine
    // returns for a Memo field. (Not CLOB: clients that special-case CLOB call
    // getClob, and a Memo is plain text best read as a String via getString.)
    MEMO     ('M', Types.LONGVARCHAR, "java.lang.String");

    /** Type used for any unrecognised code (matches the legacy default). */
    private static final DbfType DEFAULT = CHARACTER;

    private final char code;
    private final int sqlType;
    private final String javaClassName;

    DbfType(char code, int sqlType, String javaClassName) {
        this.code = code;
        this.sqlType = sqlType;
        this.javaClassName = javaClassName;
    }

    public char code() {
        return code;
    }

    public int sqlType() {
        return sqlType;
    }

    public String javaClassName() {
        return javaClassName;
    }

    /** Resolves a DBF type code (case-insensitive) to its enum, defaulting to CHARACTER. */
    public static DbfType of(char dbfType) {
        char c = Character.toUpperCase(dbfType);
        for (DbfType t : values()) {
            if (t.code == c) {
                return t;
            }
        }
        return DEFAULT;
    }

    /** JDBC {@link java.sql.Types} constant for the given DBF type code. */
    public static int sqlType(char dbfType) {
        return of(dbfType).sqlType;
    }

    /** Column Java class name (per {@link java.sql.ResultSetMetaData#getColumnClassName}). */
    public static String javaClassName(char dbfType) {
        return of(dbfType).javaClassName;
    }
}
