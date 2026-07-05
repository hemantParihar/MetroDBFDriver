package com.dbf.jdbc;

import com.dbf.jdbc.dbf.DBFField;
import com.dbf.jdbc.resultset.core.RowMapper;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

public class DBFResultSetMetaData implements ResultSetMetaData {
    private final DBFResultSet resultSet;
    private final List<DBFField> fields;
    private final List<Integer> selectedColumns;
    private final List<String> columnLabels;
    
    public DBFResultSetMetaData(DBFResultSet resultSet, List<DBFField> fields, 
                                 List<Integer> selectedColumns, List<String> columnLabels) {
        this.resultSet = resultSet;
        this.fields = fields;
        this.selectedColumns = selectedColumns;
        this.columnLabels = columnLabels;
    }
    
    // RECNO() is a synthetic 1-based row number, not a stored field.
    private static final DBFField RECNO_FIELD = createRecnoField();

    private static DBFField createRecnoField() {
        DBFField f = new DBFField();
        f.setName("RECNO");
        f.setType('I');   // integer
        f.setLength(10);  // fits a 32-bit record number
        f.setDecimalCount(0);
        return f;
    }

    private DBFField getField(int column) throws SQLException {
        if (fields == null || column < 1 || column > selectedColumns.size()) {
            throw new SQLException("Invalid column index: " + column);
        }
        // The RECNO pseudo-column has no backing field (marker index -1);
        // return a synthetic descriptor so every metadata accessor works.
        int fieldIndex = selectedColumns.get(column - 1);
        if (fieldIndex < 0 || isRecnoColumn(column)) {
            return RECNO_FIELD;
        }
        return fields.get(fieldIndex);
    }
    
    private String getTypeName(int sqlType) {
        switch (sqlType) {
            case Types.BIT: return "BIT";
            case Types.TINYINT: return "TINYINT";
            case Types.SMALLINT: return "SMALLINT";
            case Types.INTEGER: return "INTEGER";
            case Types.BIGINT: return "BIGINT";
            case Types.FLOAT: return "FLOAT";
            case Types.REAL: return "REAL" ;
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
    public int getColumnCount() throws SQLException {
        return selectedColumns != null ? selectedColumns.size() : 0;
    }
    
    @Override
    public boolean isAutoIncrement(int column) throws SQLException {
        return false;
    }
    
    @Override
    public boolean isCaseSensitive(int column) throws SQLException {
        char type = getField(column).getType();
        return type == 'C';
    }
    
    @Override
    public boolean isSearchable(int column) throws SQLException {
        return true;
    }
    
    @Override
    public boolean isCurrency(int column) throws SQLException {
        return false;
    }
    
    @Override
    public int isNullable(int column) throws SQLException {
        return columnNullableUnknown;
    }
    
    @Override
    public boolean isSigned(int column) throws SQLException {
        char type = getField(column).getType();
        return type == 'N' || type == 'F' || type == 'I' || type == 'O';
    }
    
    @Override
    public int getColumnDisplaySize(int column) throws SQLException {
        return getField(column).getLength();
    }
    
    @Override
    public String getColumnLabel(int column) throws SQLException {
        if (columnLabels != null && column <= columnLabels.size()) {
            return columnLabels.get(column - 1);
        }
        return getColumnName(column);
    }
    
 // In DBFResultSetMetaData.java, modify getColumnName() etc.:

    @Override
    public String getColumnName(int column) throws SQLException {
        if (isRecnoColumn(column)) {
            return "RECNO";
        }
        return getField(column).getName();
    }

    @Override
    public int getColumnType(int column) throws SQLException {
        if (isRecnoColumn(column)) {
            return Types.INTEGER;
        }
        return mapTypeToSQL(getField(column).getType());
    }

    @Override
    public String getColumnTypeName(int column) throws SQLException {
        if (isRecnoColumn(column)) {
            return "INTEGER";
        }
        // Memo fields report the Jet/Access type name "MEMO" (they map to
        // LONGVARCHAR + java.lang.String; see com.dbf.jdbc.dbf.DbfType).
        if (Character.toUpperCase(getField(column).getType()) == 'M') {
            return "MEMO";
        }
        return getTypeName(getColumnType(column));
    }

    @Override
    public String getColumnClassName(int column) throws SQLException {
        if (isRecnoColumn(column)) {
            return "java.lang.Integer";
        }
        return mapTypeToClass(getField(column).getType());
    }

    private boolean isRecnoColumn(int column) {
        if (columnLabels != null && column <= columnLabels.size()) {
            String label = columnLabels.get(column - 1);
            return "RECNO".equalsIgnoreCase(label);
        }
        return false;
    }
    @Override
    public String getSchemaName(int column) throws SQLException {
        return "";
    }
    
    @Override
    public int getPrecision(int column) throws SQLException {
        return getField(column).getLength();
    }
    
    @Override
    public int getScale(int column) throws SQLException {
        return getField(column).getDecimalCount();
    }
    
    @Override
    public String getTableName(int column) throws SQLException {
        return "";
    }
    
    @Override
    public String getCatalogName(int column) throws SQLException {
        return "";
    }
    
//    @Override
//    public int getColumnType(int column) throws SQLException {
//        return mapTypeToSQL(getField(column).getType());
//    }
    
//    private int mapTypeToSQL(char dbfType) {
//        switch (dbfType) {
//            case 'C': return Types.VARCHAR;
//            case 'N':
//            case 'F':
//            case 'O': return Types.DOUBLE;
//            case 'D': return Types.DATE;
//            case 'L': return Types.BOOLEAN;
//            case 'I': return Types.INTEGER;
//            case 'M': return Types.CLOB;
//            default: return Types.VARCHAR;
//        }
//    }
    private int mapTypeToSQL(char dbfType) {
        // Single source of truth: see com.dbf.jdbc.dbf.DbfType
        return com.dbf.jdbc.dbf.DbfType.sqlType(dbfType);
    }
    
//    @Override
//    public String getColumnTypeName(int column) throws SQLException {
//        int sqlType = getColumnType(column);
//        return getTypeName(sqlType);
//    }
    
    @Override
    public boolean isReadOnly(int column) throws SQLException {
        return true;
    }
    
    @Override
    public boolean isWritable(int column) throws SQLException {
        return false;
    }
    
    @Override
    public boolean isDefinitelyWritable(int column) throws SQLException {
        return false;
    }
    
//    @Override
//    public String getColumnClassName(int column) throws SQLException {
//        return mapTypeToClass(getField(column).getType());
//    }
    
    private String mapTypeToClass(char dbfType) {
        // Single source of truth: see com.dbf.jdbc.dbf.DbfType
        return com.dbf.jdbc.dbf.DbfType.javaClassName(dbfType);
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
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this);
    }
}