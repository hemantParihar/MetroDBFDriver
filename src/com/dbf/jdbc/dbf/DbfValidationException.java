package com.dbf.jdbc.dbf;

import java.io.IOException;

/**
 * Signals that a value cannot be stored in a DBF field (too large, wrong
 * type, bad format). Mirrors the validation semantics of the DANS DBF
 * library's ValueTooLargeException / DataMismatchException, but carries a
 * SQLState so the JDBC layer can surface it as a proper SQLDataException.
 */
public class DbfValidationException extends IOException {
    /** SQLState 22003: numeric value out of range. */
    public static final String NUMERIC_OUT_OF_RANGE = "22003";
    /** SQLState 22018: invalid character value for cast. */
    public static final String DATA_MISMATCH = "22018";
    /** SQLState 22007: invalid datetime format. */
    public static final String INVALID_DATETIME = "22007";

    private final String sqlState;

    public DbfValidationException(String message, String sqlState) {
        super(message);
        this.sqlState = sqlState;
    }

    public String getSqlState() {
        return sqlState;
    }
}
