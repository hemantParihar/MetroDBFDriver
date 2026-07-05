package com.dbf.jdbc.execution.streaming;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;

public class StreamingResultSetMetaData implements ResultSetMetaData {
    private final String[] columnNames;
    private final int[] columnTypes;
    
    public StreamingResultSetMetaData(String[] columnNames, int[] columnTypes) {
        this.columnNames = columnNames;
        this.columnTypes = columnTypes;
    }
    
    private String getTypeName(int sqlType) {
        switch (sqlType) {
            case Types.BIT: return "BIT";
            case Types.TINYINT: return "TINYINT";
            case Types.SMALLINT: return "SMALLINT";
            case Types.INTEGER: return "INTEGER";
            case Types.BIGINT: return "BIGINT";
            case Types.FLOAT: return "FLOAT";
            case Types.REAL: return "REAL";
            case Types.DOUBLE: return "DOUBLE";
            case Types.NUMERIC: return "NUMERIC";
            case Types.DECIMAL: return "DECIMAL";
            case Types.CHAR: return "CHAR";
            case Types.VARCHAR: return "VARCHAR";
            case Types.LONGVARCHAR: return "LONGVARCHAR";
            case Types.DATE: return "DATE";
            case Types.TIME: return "TIME";
            case Types.TIMESTAMP: return "TIMESTAMP";
            case Types.BINARY: return "BINARY";
            case Types.VARBINARY: return "VARBINARY";
            case Types.LONGVARBINARY: return "LONGVARBINARY";
            case Types.NULL: return "NULL";
            case Types.OTHER: return "OTHER";
            case Types.JAVA_OBJECT: return "JAVA_OBJECT";
            case Types.DISTINCT: return "DISTINCT";
            case Types.STRUCT: return "STRUCT";
            case Types.ARRAY: return "ARRAY";
            case Types.BLOB: return "BLOB";
            case Types.CLOB: return "CLOB";
            case Types.REF: return "REF";
            case Types.DATALINK: return "DATALINK";
            case Types.BOOLEAN: return "BOOLEAN";
            case Types.ROWID: return "ROWID";
            case Types.NCHAR: return "NCHAR";
            case Types.NVARCHAR: return "NVARCHAR";
            case Types.LONGNVARCHAR: return "LONGNVARCHAR";
            case Types.NCLOB: return "NCLOB";
            case Types.SQLXML: return "SQLXML";
            default: return "VARCHAR";
        }
    }
    
    @Override 
    public int getColumnCount() { 
        return columnNames.length; 
    }
    
    @Override 
    public String getColumnName(int column) { 
        return columnNames[column - 1]; 
    }
    
    @Override 
    public String getColumnLabel(int column) { 
        return columnNames[column - 1]; 
    }
    
    @Override 
    public int getColumnType(int column) { 
        return columnTypes[column - 1]; 
    }
    
    @Override 
    public String getColumnTypeName(int column) {
        return getTypeName(columnTypes[column - 1]);
    }
    
    @Override 
    public int getColumnDisplaySize(int column) { 
        return 255; 
    }
    
    @Override 
    public int getPrecision(int column) { 
        return 0; 
    }
    
    @Override 
    public int getScale(int column) { 
        return 0; 
    }
    
    @Override 
    public int isNullable(int column) { 
        return columnNullableUnknown; 
    }
    
    @Override 
    public boolean isAutoIncrement(int column) { 
        return false; 
    }
    
    @Override 
    public boolean isCaseSensitive(int column) { 
        int type = columnTypes[column - 1];
        return type == Types.VARCHAR || type == Types.CHAR;
    }
    
    @Override 
    public boolean isSearchable(int column) { 
        return true; 
    }
    
    @Override 
    public boolean isCurrency(int column) { 
        return false; 
    }
    
    @Override 
    public boolean isSigned(int column) { 
        int type = columnTypes[column - 1];
        return type == Types.INTEGER || type == Types.BIGINT || 
               type == Types.SMALLINT || type == Types.TINYINT ||
               type == Types.FLOAT || type == Types.DOUBLE || 
               type == Types.DECIMAL || type == Types.NUMERIC;
    }
    
    @Override 
    public String getSchemaName(int column) { 
        return ""; 
    }
    
    @Override 
    public String getTableName(int column) { 
        return ""; 
    }
    
    @Override 
    public String getCatalogName(int column) { 
        return ""; 
    }
    
    @Override 
    public boolean isReadOnly(int column) { 
        return true; 
    }
    
    @Override 
    public boolean isWritable(int column) { 
        return false; 
    }
    
    @Override 
    public boolean isDefinitelyWritable(int column) { 
        return false; 
    }
    
    @Override 
    public String getColumnClassName(int column) {
        int type = columnTypes[column - 1];
        switch (type) {
            case Types.INTEGER: return "java.lang.Integer";
            case Types.BIGINT: return "java.lang.Long";
            case Types.SMALLINT: return "java.lang.Short";
            case Types.TINYINT: return "java.lang.Byte";
            case Types.FLOAT:
            case Types.DOUBLE: return "java.lang.Double";
            case Types.DECIMAL:
            case Types.NUMERIC: return "java.math.BigDecimal";
            case Types.VARCHAR:
            case Types.CHAR:
            case Types.LONGVARCHAR: return "java.lang.String";
            case Types.DATE: return "java.sql.Date";
            case Types.TIME: return "java.sql.Time";
            case Types.TIMESTAMP: return "java.sql.Timestamp";
            case Types.BOOLEAN: return "java.lang.Boolean";
            case Types.BLOB: return "java.sql.Blob";
            case Types.CLOB: return "java.sql.Clob";
            default: return "java.lang.Object";
        }
    }
    
    @Override 
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return (T) this;
        }
        throw new SQLException("Cannot unwrap to " + iface);
    }
    
    @Override 
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}