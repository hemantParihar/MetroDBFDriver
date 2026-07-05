package com.dbf.jdbc.error;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLDataException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;

/**
 * Factory for creating consistent SQL exceptions
 * Centralizes error code and message management
 */
public class SQLExceptionFactory {
    
    // ==================== SQL State Constants ====================
    
    public static final String SQL_STATE_SUCCESS = "00000";
    public static final String SQL_STATE_WARNING = "01000";
    public static final String SQL_STATE_NO_DATA = "02000";
    public static final String SQL_STATE_DYNAMIC_SQL_ERROR = "07000";
    public static final String SQL_STATE_CONNECTION_EXCEPTION = "08000";
    public static final String SQL_STATE_CONNECTION_CLOSED = "08003";
    public static final String SQL_STATE_CONNECTION_FAILURE = "08006";
    public static final String SQL_STATE_DATA_EXCEPTION = "22000";
    public static final String SQL_STATE_DATA_TRUNCATION = "22001";
    public static final String SQL_STATE_NUMERIC_VALUE_OUT_OF_RANGE = "22003";
    public static final String SQL_STATE_INVALID_DATETIME_FORMAT = "22007";
    public static final String SQL_STATE_DIVISION_BY_ZERO = "22012";
    public static final String SQL_STATE_INTEGRITY_CONSTRAINT_VIOLATION = "23000";
    public static final String SQL_STATE_INVALID_AUTHORIZATION = "28000";
    public static final String SQL_STATE_SYNTAX_ERROR = "3C000";
    public static final String SQL_STATE_INVALID_SCHEMA = "3F000";
    public static final String SQL_STATE_TRANSACTION_ROLLBACK = "40000";
    public static final String SQL_STATE_DEADLOCK = "40001";
    public static final String SQL_STATE_SYNTAX_ERROR_OR_ACCESS_RULE = "42000";
    public static final String SQL_STATE_INVALID_COLUMN_NAME = "42S22";
    public static final String SQL_STATE_INVALID_TABLE_NAME = "42S02";
    public static final String SQL_STATE_INVALID_CATALOG_NAME = "42S04";
    public static final String SQL_STATE_SYSTEM_ERROR = "58000";
    public static final String SQL_STATE_IO_ERROR = "58030";
    public static final String SQL_STATE_FEATURE_NOT_SUPPORTED = "0A000";
    
    // ==================== Generic SQL State ====================
    
    public static final String SQL_STATE_GENERAL = "HY000";  // ← ADDED THIS
    
    // ==================== Vendor Error Codes ====================
    
    public static final int ERROR_GENERAL = 17000;
    public static final int ERROR_SYNTAX = 17001;
    public static final int ERROR_PARSE = 17002;
    public static final int ERROR_NO_DATA = 17003;
    public static final int ERROR_CONNECTION_CLOSED = 17004;
    public static final int ERROR_CONNECTION_FAILURE = 17005;
    public static final int ERROR_INVALID_COLUMN = 17006;
    public static final int ERROR_INVALID_TABLE = 17007;
    public static final int ERROR_INVALID_PARAMETER = 17008;
    public static final int ERROR_DATA_CONVERSION = 17009;
    public static final int ERROR_FILE_NOT_FOUND = 17010;
    public static final int ERROR_IO = 17011;
    public static final int ERROR_LOCK_TIMEOUT = 17012;
    public static final int ERROR_DEADLOCK = 17013;
    public static final int ERROR_NOT_SUPPORTED = 17090;
    public static final int ERROR_UNKNOWN = 17999;
    
    // ==================== Connection Exceptions ====================
    
    public static SQLException connectionClosed() {
        return new SQLNonTransientConnectionException(
            "Connection is closed",
            SQL_STATE_CONNECTION_CLOSED,
            ERROR_CONNECTION_CLOSED
        );
    }
    
    public static SQLException connectionFailure(String reason) {
        return new SQLNonTransientConnectionException(
            "Connection failed: " + reason,
            SQL_STATE_CONNECTION_FAILURE,
            ERROR_CONNECTION_FAILURE
        );
    }
    
    public static SQLException connectionTimeout(String host, int port) {
        return new SQLTransientConnectionException(
            "Connection timed out to " + host + ":" + port,
            SQL_STATE_CONNECTION_EXCEPTION,
            ERROR_CONNECTION_FAILURE
        );
    }
    
    // ==================== ResultSet Exceptions ====================
    
    public static SQLException resultSetClosed() {
        return new SQLException(
            "ResultSet is closed",
            SQL_STATE_SYSTEM_ERROR,
            ERROR_GENERAL
        );
    }
    
    public static SQLException noCurrentRow() {
        return new SQLException(
            "No current row in ResultSet. Did you call next()?",
            SQL_STATE_NO_DATA,
            ERROR_NO_DATA
        );
    }
    
    public static SQLException invalidColumn(String columnName) {
        return new SQLException(
            "Invalid column name: '" + columnName + "'",
            SQL_STATE_INVALID_COLUMN_NAME,
            ERROR_INVALID_COLUMN
        );
    }
    
    public static SQLException invalidColumnIndex(int columnIndex) {
        return new SQLException(
            "Invalid column index: " + columnIndex,
            SQL_STATE_INVALID_COLUMN_NAME,
            ERROR_INVALID_COLUMN
        );
    }
    
    public static SQLException invalidTable(String tableName) {
        return new SQLException(
            "Table not found: '" + tableName + "'",
            SQL_STATE_INVALID_TABLE_NAME,
            ERROR_INVALID_TABLE
        );
    }
    
    // ==================== Statement Exceptions ====================
    
    public static SQLException statementClosed() {
        return new SQLException(
            "Statement is closed",
            SQL_STATE_SYSTEM_ERROR,
            ERROR_GENERAL
        );
    }
    
    // ==================== Data Exceptions ====================
    
    public static SQLException dataConversionError(String columnName, String fromType, String toType) {
        return new SQLDataException(
            "Cannot convert column '" + columnName + "' from " + fromType + " to " + toType,
            SQL_STATE_DATA_EXCEPTION,
            ERROR_DATA_CONVERSION
        );
    }
    
    public static SQLException numericValueOutOfRange(String value, String columnName) {
        return new SQLDataException(
            "Numeric value '" + value + "' is out of range for column '" + columnName + "'",
            SQL_STATE_NUMERIC_VALUE_OUT_OF_RANGE,
            ERROR_DATA_CONVERSION
        );
    }
    
    public static SQLException divisionByZero() {
        return new SQLDataException(
            "Division by zero",
            SQL_STATE_DIVISION_BY_ZERO,
            ERROR_DATA_CONVERSION
        );
    }
    
    public static SQLException dataTruncation(String columnName, int maxLength) {
        return new SQLDataException(
            "Data too long for column '" + columnName + "'. Maximum length is " + maxLength,
            SQL_STATE_DATA_TRUNCATION,
            ERROR_DATA_CONVERSION
        );
    }
    
    public static SQLException invalidDateTimeFormat(String value, String expectedFormat) {
        return new SQLDataException(
            "Invalid datetime format: '" + value + "'. Expected format: " + expectedFormat,
            SQL_STATE_INVALID_DATETIME_FORMAT,
            ERROR_DATA_CONVERSION
        );
    }
    
    // ==================== Parsing Exceptions ====================
    
    public static SQLException syntaxError(String sql, String message) {
        return new SQLSyntaxErrorException(
            "Syntax error in SQL: " + message + "\nSQL: " + truncateSql(sql),
            SQL_STATE_SYNTAX_ERROR,
            ERROR_SYNTAX
        );
    }
    
    public static SQLException parseError(String message, int line, int column) {
        return new SQLSyntaxErrorException(
            "Parse error at line " + line + ", column " + column + ": " + message,
            SQL_STATE_SYNTAX_ERROR_OR_ACCESS_RULE,
            ERROR_PARSE
        );
    }
    
    public static SQLException unexpectedToken(String token, int line, int column) {
        return new SQLSyntaxErrorException(
            "Unexpected token '" + token + "' at line " + line + ", column " + column,
            SQL_STATE_SYNTAX_ERROR_OR_ACCESS_RULE,
            ERROR_SYNTAX
        );
    }
    
    // ==================== Parameter Exceptions ====================
    
    public static SQLException parameterNotSet(int index) {
        return new SQLException(
            "Parameter at index " + index + " is not set",
            SQL_STATE_DYNAMIC_SQL_ERROR,
            ERROR_INVALID_PARAMETER
        );
    }
    
    public static SQLException invalidParameterIndex(int index, int maxIndex) {
        return new SQLException(
            "Invalid parameter index: " + index + ". Valid range: 1 to " + maxIndex,
            SQL_STATE_DYNAMIC_SQL_ERROR,
            ERROR_INVALID_PARAMETER
        );
    }
    
    public static SQLException parameterTypeMismatch(int index, String expected, String actual) {
        return new SQLException(
            "Parameter at index " + index + " expects " + expected + " but got " + actual,
            SQL_STATE_DATA_EXCEPTION,
            ERROR_INVALID_PARAMETER
        );
    }
    
    // ==================== I/O Exceptions ====================
    
    public static SQLException fileNotFound(String filePath) {
        return new SQLException(
            "DBF file not found: " + filePath,
            SQL_STATE_IO_ERROR,
            ERROR_FILE_NOT_FOUND
        );
    }
    
    public static SQLException ioError(String message, IOException cause) {
        return new SQLException(
            "I/O error: " + message,
            SQL_STATE_IO_ERROR,
            ERROR_IO,
            cause
        );
    }
    
    public static SQLException fileReadError(String filePath, IOException cause) {
        return new SQLException(
            "Failed to read DBF file: " + filePath,
            SQL_STATE_IO_ERROR,
            ERROR_IO,
            cause
        );
    }
    
    public static SQLException fileWriteError(String filePath, IOException cause) {
        return new SQLException(
            "Failed to write to DBF file: " + filePath,
            SQL_STATE_IO_ERROR,
            ERROR_IO,
            cause
        );
    }
    
    // ==================== Lock & Concurrency Exceptions ====================
    
    public static SQLException lockTimeout(int recordNumber) {
        return new SQLException(
            "Could not acquire lock for record " + recordNumber + " within timeout period",
            SQL_STATE_TRANSACTION_ROLLBACK,
            ERROR_LOCK_TIMEOUT
        );
    }
    
    public static SQLException deadlock(String message) {
        return new SQLRecoverableException(
            "Deadlock detected: " + message,
            SQL_STATE_DEADLOCK,
            ERROR_DEADLOCK
        );
    }
    
    // ==================== Integrity Constraint Exceptions ====================
    
    public static SQLException uniqueConstraintViolation(String columnName, String value) {
        return new SQLIntegrityConstraintViolationException(
            "Duplicate value '" + value + "' for column '" + columnName + "'",
            SQL_STATE_INTEGRITY_CONSTRAINT_VIOLATION,
            ERROR_GENERAL
        );
    }
    
    public static SQLException notNullViolation(String columnName) {
        return new SQLIntegrityConstraintViolationException(
            "Column '" + columnName + "' cannot be null",
            SQL_STATE_INTEGRITY_CONSTRAINT_VIOLATION,
            ERROR_GENERAL
        );
    }
    
    // ==================== Feature Not Supported ====================
    
    public static SQLException featureNotSupported(String feature) {
        return new SQLFeatureNotSupportedException(
            "Feature not supported: " + feature,
            SQL_STATE_FEATURE_NOT_SUPPORTED,
            ERROR_NOT_SUPPORTED
        );
    }
    
    // ==================== Generic Exceptions ====================
    
    public static SQLException create(String message) {
        return new SQLException(message, SQL_STATE_GENERAL, ERROR_GENERAL);
    }
    
    public static SQLException create(String message, String sqlState) {
        return new SQLException(message, sqlState, ERROR_GENERAL);
    }
    
    public static SQLException create(String message, Throwable cause) {
        return new SQLException(message, SQL_STATE_GENERAL, ERROR_GENERAL, cause);
    }
    
    public static SQLException create(String message, String sqlState, Throwable cause) {
        return new SQLException(message, sqlState, ERROR_GENERAL, cause);
    }
    
    public static SQLException create(String message, String sqlState, int vendorCode) {
        return new SQLException(message, sqlState, vendorCode);
    }
    
    public static SQLException unknownError(Throwable cause) {
        return new SQLException(
            "Unknown error: " + cause.getMessage(),
            SQL_STATE_SYSTEM_ERROR,
            ERROR_UNKNOWN,
            cause
        );
    }
    
    // ==================== Helper Methods ====================
    
    private static String truncateSql(String sql) {
        if (sql == null) return "null";
        if (sql.length() <= 200) return sql;
        return sql.substring(0, 197) + "...";
    }
    
    public static SQLException toSQLException(Exception e) {
        if (e instanceof SQLException) {
            return (SQLException) e;
        }
        if (e instanceof IOException) {
            return ioError(e.getMessage(), (IOException) e);
        }
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return create("Operation interrupted", e);
        }
        return create(e.getMessage(), e);
    }
}