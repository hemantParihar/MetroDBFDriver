package com.dbf.jdbc;

import com.dbf.jdbc.dbf.DBFField;
import com.dbf.jdbc.dbf.DBFReader;

import java.io.File;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.charset.Charset;
import java.sql.*;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

public class DBFDatabaseMetaData implements DatabaseMetaData {
    private final DBFConnection connection;
    
    public DBFDatabaseMetaData(DBFConnection connection) {
        this.connection = connection;
    }
    
    @Override
    public boolean allProceduresAreCallable() throws SQLException {
        return false;
    }
    
    @Override
    public boolean allTablesAreSelectable() throws SQLException {
        return true;
    }
    
    @Override
    public String getURL() throws SQLException {
        return "jdbc:dbf:" + connection.getPath();
    }
    
    @Override
    public String getUserName() throws SQLException {
        return System.getProperty("user.name");
    }
    
    @Override
    public boolean isReadOnly() throws SQLException {
        return true;
    }
    
    @Override
    public boolean nullsAreSortedHigh() throws SQLException {
        return false;
    }
    
    @Override
    public boolean nullsAreSortedLow() throws SQLException {
        return true;
    }
    
    @Override
    public boolean nullsAreSortedAtStart() throws SQLException {
        return false;
    }
    
    @Override
    public boolean nullsAreSortedAtEnd() throws SQLException {
        return false;
    }
    
    @Override
    public String getDatabaseProductName() throws SQLException {
        return "DBF JDBC Driver";
    }
    
    @Override
    public String getDatabaseProductVersion() throws SQLException {
        return "1.0";
    }
    
    @Override
    public String getDriverName() throws SQLException {
        return "DBF JDBC Driver";
    }
    
    @Override
    public String getDriverVersion() throws SQLException {
        return "1.0";
    }
    
    @Override
    public int getDriverMajorVersion() {
        return 1;
    }
    
    @Override
    public int getDriverMinorVersion() {
        return 0;
    }
    
    @Override
    public boolean usesLocalFiles() throws SQLException {
        return true;
    }
    
    @Override
    public boolean usesLocalFilePerTable() throws SQLException {
        return true;
    }
    
    @Override
    public boolean supportsResultSetType(int type) throws SQLException {
        return type == ResultSet.TYPE_FORWARD_ONLY;
    }
    
    @Override
    public boolean supportsResultSetConcurrency(int type, int concurrency) throws SQLException {
        return type == ResultSet.TYPE_FORWARD_ONLY && concurrency == ResultSet.CONCUR_READ_ONLY;
    }
    
    @Override
    public boolean ownUpdatesAreVisible(int type) throws SQLException {
        return false;
    }
    
    @Override
    public boolean ownDeletesAreVisible(int type) throws SQLException {
        return false;
    }
    
    @Override
    public boolean ownInsertsAreVisible(int type) throws SQLException {
        return false;
    }
    
    @Override
    public boolean othersUpdatesAreVisible(int type) throws SQLException {
        return false;
    }
    
    @Override
    public boolean othersDeletesAreVisible(int type) throws SQLException {
        return false;
    }
    
    @Override
    public boolean othersInsertsAreVisible(int type) throws SQLException {
        return false;
    }
    
    @Override
    public boolean updatesAreDetected(int type) throws SQLException {
        return false;
    }
    
    @Override
    public boolean deletesAreDetected(int type) throws SQLException {
        return false;
    }
    
    @Override
    public boolean insertsAreDetected(int type) throws SQLException {
        return false;
    }
    
    @Override
    public boolean supportsBatchUpdates() throws SQLException {
        return false;
    }
    
    @Override
    public ResultSet getCatalogs() throws SQLException {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{connection.getPath()});
        return createStringResultSet(new String[]{"TABLE_CAT"}, rows);
    }
    
    @Override
    public ResultSet getSchemas() throws SQLException {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"", null});
        return createStringResultSet(new String[]{"TABLE_SCHEM", "TABLE_CATALOG"}, rows);
    }
    
    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern, String[] types) throws SQLException {
        File dir = new File(connection.getPath());
        File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".dbf"));
        
        List<String[]> rows = new ArrayList<>();
        if (files != null) {
            for (File file : files) {
                String name = file.getName().substring(0, file.getName().length() - 4);
                if (tableNamePattern == null || matchesPattern(name, tableNamePattern)) {
                    rows.add(new String[]{
                        connection.getPath(), // TABLE_CAT
                        null,                 // TABLE_SCHEM
                        name,                 // TABLE_NAME
                        "TABLE",              // TABLE_TYPE
                        "DBF File"            // REMARKS
                    });
                }
            }
        }
        
        return createStringResultSet(new String[]{
            "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS"
        }, rows);
    }
    
    @Override
    public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        
        File dir = new File(connection.getPath());
        File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".dbf"));
        
        if (files != null) {
            for (File file : files) {
                String tableName = file.getName().substring(0, file.getName().length() - 4);
                if (tableNamePattern == null || matchesPattern(tableName, tableNamePattern)) {
                    try {
                        DBFReader reader = new DBFReader(file.getPath(),getCharset());
                        int ordinal = 1;
                        for (DBFField field : reader.getHeader().getFields()) {
                            String colName = field.getName();
                            if (columnNamePattern == null || matchesPattern(colName, columnNamePattern)) {
                                rows.add(new Object[]{
                                    connection.getPath(),                      // TABLE_CAT
                                    null,                                      // TABLE_SCHEM
                                    tableName,                                 // TABLE_NAME
                                    colName,                                   // COLUMN_NAME
                                    mapTypeToSQL(field.getType()),             // DATA_TYPE
                                    getTypeName(field.getType()),              // TYPE_NAME
                                    field.getLength(),                         // COLUMN_SIZE
                                    0,                                         // BUFFER_LENGTH
                                    field.getDecimalCount(),                   // DECIMAL_DIGITS
                                    10,                                        // NUM_PREC_RADIX
                                    columnNullableUnknown,                     // NULLABLE
                                    null,                                      // REMARKS
                                    null,                                      // COLUMN_DEF
                                    0,                                         // SQL_DATA_TYPE
                                    0,                                         // SQL_DATETIME_SUB
                                    field.getLength(),                         // CHAR_OCTET_LENGTH
                                    ordinal++,                                 // ORDINAL_POSITION
                                    "YES"                                      // IS_NULLABLE
                                });
                            }
                        }
                        reader.close();
                    } catch (Exception e) {
                        // Skip file if can't read
                    }
                }
            }
        }
        
        return createObjectResultSet(new String[]{
            "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "DATA_TYPE",
            "TYPE_NAME", "COLUMN_SIZE", "BUFFER_LENGTH", "DECIMAL_DIGITS", "NUM_PREC_RADIX",
            "NULLABLE", "REMARKS", "COLUMN_DEF", "SQL_DATA_TYPE", "SQL_DATETIME_SUB",
            "CHAR_OCTET_LENGTH", "ORDINAL_POSITION", "IS_NULLABLE"
        }, rows);
    }
    
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
    
    private String getTypeName(char dbfType) {
        switch (dbfType) {
            case 'C': return "CHARACTER";
            case 'N': return "NUMERIC";
            case 'F': return "FLOAT";
            case 'D': return "DATE";
            case 'L': return "LOGICAL";
            case 'I': return "INTEGER";
            case 'O': return "DOUBLE";
            case 'M': return "MEMO";
            default: return "UNKNOWN";
        }
    }
    
    private boolean matchesPattern(String name, String pattern) {
        if (pattern == null) return true;
        String regex = pattern.replace("%", ".*").replace("_", ".");
        return name.matches(regex);
    }
    
    private ResultSet createStringResultSet(String[] columns, List<String[]> rows) throws SQLException {
        List<Object[]> objRows = new ArrayList<>();
        for (String[] row : rows) {
            objRows.add(row);
        }
        return createObjectResultSet(columns, objRows);
    }
    
    private ResultSet createObjectResultSet(String[] columns, List<Object[]> rows) throws SQLException {
        // Simple in-memory result set implementation
        return new InMemoryResultSet(columns, rows);
    }
    
    // Inner class for simple result set
    private static class InMemoryResultSet implements ResultSet {
        private final String[] columns;
        private final List<Object[]> rows;
        private int currentRow = -1;
        private boolean closed = false;
        
        InMemoryResultSet(String[] columns, List<Object[]> rows) {
            this.columns = columns;
            this.rows = rows;
        }
        
        @Override
        public boolean next() throws SQLException {
            if (currentRow + 1 < rows.size()) {
                currentRow++;
                return true;
            }
            return false;
        }
        
        @Override
        public void close() throws SQLException {
            closed = true;
        }
        
        @Override
        public boolean wasNull() throws SQLException {
            return false;
        }
        
        @Override
        public String getString(int columnIndex) throws SQLException {
            Object val = getObject(columnIndex);
            return val != null ? val.toString() : null;
        }
        
        @Override
        public String getString(String columnLabel) throws SQLException {
            return getString(findColumn(columnLabel));
        }
        
        @Override
        public int getInt(int columnIndex) throws SQLException {
            Object val = getObject(columnIndex);
            if (val instanceof Number) return ((Number) val).intValue();
            if (val instanceof String) return Integer.parseInt((String) val);
            return 0;
        }
        
        @Override
        public Object getObject(int columnIndex) throws SQLException {
            if (currentRow < 0 || currentRow >= rows.size()) {
                throw new SQLException("No current row");
            }
            return rows.get(currentRow)[columnIndex - 1];
        }
        
        @Override
        public int findColumn(String columnLabel) throws SQLException {
            for (int i = 0; i < columns.length; i++) {
                if (columns[i].equalsIgnoreCase(columnLabel)) {
                    return i + 1;
                }
            }
            throw new SQLException("Column not found: " + columnLabel);
        }
        
        @Override
        public boolean isBeforeFirst() throws SQLException {
            return currentRow == -1 && !rows.isEmpty();
        }
        
        @Override
        public boolean isAfterLast() throws SQLException {
            return currentRow >= rows.size();
        }
        
        @Override
        public boolean isFirst() throws SQLException {
            return currentRow == 0;
        }
        
        @Override
        public boolean isLast() throws SQLException {
            return currentRow == rows.size() - 1;
        }
        
        @Override
        public void beforeFirst() throws SQLException {
            currentRow = -1;
        }
        
        @Override
        public void afterLast() throws SQLException {
            currentRow = rows.size();
        }
        
        @Override
        public boolean first() throws SQLException {
            if (!rows.isEmpty()) {
                currentRow = 0;
                return true;
            }
            return false;
        }
        
        @Override
        public boolean last() throws SQLException {
            if (!rows.isEmpty()) {
                currentRow = rows.size() - 1;
                return true;
            }
            return false;
        }
        
        @Override
        public int getRow() throws SQLException {
            return isBeforeFirst() || isAfterLast() ? 0 : currentRow + 1;
        }
        
        // All other ResultSet methods throw SQLFeatureNotSupportedException
        // Implement as needed for completeness
        
        @Override
        public boolean absolute(int row) throws SQLException {
            if (row == 0) {
                beforeFirst();
                return false;
            }
            int target = row > 0 ? row - 1 : rows.size() + row;
            if (target >= 0 && target < rows.size()) {
                currentRow = target;
                return true;
            }
            if (target < 0) beforeFirst();
            else afterLast();
            return false;
        }
        
        @Override
        public boolean previous() throws SQLException {
            if (currentRow > 0) {
                currentRow--;
                return true;
            }
            if (currentRow == 0) beforeFirst();
            return false;
        }
        
        @Override
        public ResultSetMetaData getMetaData() throws SQLException {
            return new ResultSetMetaData() {
                @Override
                public int getColumnCount() { return columns.length; }
                
                @Override
                public String getColumnLabel(int column) { return columns[column - 1]; }
                
                @Override
                public String getColumnName(int column) { return columns[column - 1]; }
                
                @Override
                public int getColumnType(int column) { return Types.VARCHAR; }
                
                @Override
                public String getColumnTypeName(int column) { return "VARCHAR"; }
                
                @Override
                public int getColumnDisplaySize(int column) { return 255; }
                
                @Override
                public int getPrecision(int column) { return 0; }
                
                @Override
                public int getScale(int column) { return 0; }
                
                @Override
                public int isNullable(int column) { return columnNullable; }
                
                @Override
                public boolean isAutoIncrement(int column) { return false; }
                
                @Override
                public boolean isCaseSensitive(int column) { return true; }
                
                @Override
                public boolean isSearchable(int column) { return true; }
                
                @Override
                public boolean isCurrency(int column) { return false; }
                
                @Override
                public boolean isSigned(int column) { return false; }
                
                @Override
                public String getSchemaName(int column) { return null; }
                
                @Override
                public String getTableName(int column) { return null; }
                
                @Override
                public String getCatalogName(int column) { return null; }
                
                @Override
                public boolean isReadOnly(int column) { return true; }
                
                @Override
                public boolean isWritable(int column) { return false; }
                
                @Override
                public boolean isDefinitelyWritable(int column) { return false; }
                
                @Override
                public String getColumnClassName(int column) { return String.class.getName(); }
                
                @Override
                public <T> T unwrap(Class<T> iface) { return null; }
                
                @Override
                public boolean isWrapperFor(Class<?> iface) { return false; }
            };
        }
        
        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) return iface.cast(this);
            throw new SQLException("Cannot unwrap");
        }
        
        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return iface.isInstance(this);
        }
        
        // Add other required methods with default implementations
        @Override public boolean getBoolean(int columnIndex) throws SQLException { return false; }
        @Override public byte getByte(int columnIndex) throws SQLException { return 0; }
        @Override public short getShort(int columnIndex) throws SQLException { return 0; }
        @Override public long getLong(int columnIndex) throws SQLException { return 0; }
        @Override public float getFloat(int columnIndex) throws SQLException { return 0; }
        @Override public double getDouble(int columnIndex) throws SQLException { return 0; }
        @Override public byte[] getBytes(int columnIndex) throws SQLException { return null; }
        @Override public Date getDate(int columnIndex) throws SQLException { return null; }
        @Override public Time getTime(int columnIndex) throws SQLException { return null; }
        @Override public Timestamp getTimestamp(int columnIndex) throws SQLException { return null; }
        @Override public InputStream getAsciiStream(int columnIndex) throws SQLException { return null; }
        @Override public InputStream getBinaryStream(int columnIndex) throws SQLException { return null; }
        @Override public SQLWarning getWarnings() throws SQLException { return null; }
        @Override public void clearWarnings() throws SQLException { }
        @Override public String getCursorName() throws SQLException { return null; }
        @Override public Object getObject(String columnLabel) throws SQLException { return null; }
//        @Override public int findColumn(String columnLabel) throws SQLException { return 0; }

		@Override
		public void cancelRowUpdates() throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void deleteRow() throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public Array getArray(int columnIndex) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public Array getArray(String columnLabel) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}


		@Override
		public InputStream getAsciiStream(String columnLabel) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

//		@Override
//		public InputStream getBinaryStream(int columnIndex) throws SQLException {
//			// TODO Auto-generated method stub
//			return null;
//		}

		@Override
		public InputStream getBinaryStream(String columnLabel) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public Blob getBlob(int columnIndex) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public Blob getBlob(String columnLabel) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public boolean getBoolean(String columnLabel) throws SQLException {
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		public byte getByte(String columnLabel) throws SQLException {
			// TODO Auto-generated method stub
			return 0;
		}

		@Override
		public byte[] getBytes(String columnLabel) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public Reader getCharacterStream(int columnIndex) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public Reader getCharacterStream(String columnLabel) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public Clob getClob(int columnIndex) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public Clob getClob(String columnLabel) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public int getConcurrency() throws SQLException {
			// TODO Auto-generated method stub
			return 0;
		}

		@Override
		public Date getDate(String columnLabel) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public Date getDate(int columnIndex, Calendar cal) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public Date getDate(String columnLabel, Calendar cal) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public double getDouble(String columnLabel) throws SQLException {
			// TODO Auto-generated method stub
			return 0;
		}

		@Override
		public int getFetchDirection() throws SQLException {
			// TODO Auto-generated method stub
			return 0;
		}

		@Override
		public int getFetchSize() throws SQLException {
			// TODO Auto-generated method stub
			return 0;
		}

		@Override
		public float getFloat(String columnLabel) throws SQLException {
			// TODO Auto-generated method stub
			return 0;
		}

		@Override
		public int getHoldability() throws SQLException {
			// TODO Auto-generated method stub
			return 0;
		}

		@Override
		public int getInt(String columnLabel) throws SQLException {
			// TODO Auto-generated method stub
			return 0;
		}

		@Override
		public long getLong(String columnLabel) throws SQLException {
			// TODO Auto-generated method stub
			return 0;
		}

		@Override
		public Reader getNCharacterStream(int columnIndex) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public Reader getNCharacterStream(String columnLabel) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public NClob getNClob(int columnIndex) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public NClob getNClob(String columnLabel) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public String getNString(int columnIndex) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public String getNString(String columnLabel) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public Ref getRef(int columnIndex) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public Ref getRef(String columnLabel) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public RowId getRowId(int columnIndex) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public RowId getRowId(String columnLabel) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public SQLXML getSQLXML(int columnIndex) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public SQLXML getSQLXML(String columnLabel) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public short getShort(String columnLabel) throws SQLException {
			// TODO Auto-generated method stub
			return 0;
		}

		@Override
		public Statement getStatement() throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public Time getTime(String columnLabel) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public Time getTime(int columnIndex, Calendar cal) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public Time getTime(String columnLabel, Calendar cal) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public Timestamp getTimestamp(String columnLabel) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public int getType() throws SQLException {
			// TODO Auto-generated method stub
			return 0;
		}

		@Override
		public URL getURL(int columnIndex) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public URL getURL(String columnLabel) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public InputStream getUnicodeStream(int columnIndex) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public InputStream getUnicodeStream(String columnLabel) throws SQLException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public void insertRow() throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public boolean isClosed() throws SQLException {
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		public void moveToCurrentRow() throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void moveToInsertRow() throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void refreshRow() throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public boolean relative(int rows) throws SQLException {
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		public boolean rowDeleted() throws SQLException {
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		public boolean rowInserted() throws SQLException {
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		public boolean rowUpdated() throws SQLException {
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		public void setFetchDirection(int direction) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void setFetchSize(int rows) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateArray(int columnIndex, Array x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateArray(String columnLabel, Array x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateAsciiStream(String columnLabel, InputStream x, long length) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateBinaryStream(String columnLabel, InputStream x, int length) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateBinaryStream(String columnLabel, InputStream x, long length) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateBlob(int columnIndex, Blob x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateBlob(String columnLabel, Blob x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateBlob(int columnIndex, InputStream inputStream, long length) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateBlob(String columnLabel, InputStream inputStream, long length) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateBoolean(int columnIndex, boolean x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateBoolean(String columnLabel, boolean x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateByte(int columnIndex, byte x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateByte(String columnLabel, byte x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateBytes(int columnIndex, byte[] x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateBytes(String columnLabel, byte[] x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateCharacterStream(int columnIndex, Reader x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateCharacterStream(String columnLabel, Reader reader) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateCharacterStream(String columnLabel, Reader reader, int length) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateClob(int columnIndex, Clob x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateClob(String columnLabel, Clob x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateClob(int columnIndex, Reader reader) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateClob(String columnLabel, Reader reader) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateClob(int columnIndex, Reader reader, long length) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateClob(String columnLabel, Reader reader, long length) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateDate(int columnIndex, Date x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateDate(String columnLabel, Date x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateDouble(int columnIndex, double x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateDouble(String columnLabel, double x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateFloat(int columnIndex, float x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateFloat(String columnLabel, float x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateInt(int columnIndex, int x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateInt(String columnLabel, int x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateLong(int columnIndex, long x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateLong(String columnLabel, long x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateNCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateNClob(int columnIndex, NClob nClob) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateNClob(String columnLabel, NClob nClob) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateNClob(int columnIndex, Reader reader) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateNClob(String columnLabel, Reader reader) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateNString(int columnIndex, String nString) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateNString(String columnLabel, String nString) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateNull(int columnIndex) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateNull(String columnLabel) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateObject(int columnIndex, Object x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateObject(String columnLabel, Object x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateRef(int columnIndex, Ref x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateRef(String columnLabel, Ref x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateRow() throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateRowId(int columnIndex, RowId x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateRowId(String columnLabel, RowId x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateShort(int columnIndex, short x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateShort(String columnLabel, short x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateString(int columnIndex, String x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateString(String columnLabel, String x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateTime(int columnIndex, Time x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateTime(String columnLabel, Time x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException {
			// TODO Auto-generated method stub
			
		}
        
        // Additional methods omitted for brevity
    }
    
    // Many DatabaseMetaData methods omitted for brevity
    // Implement as needed for your use case
    
    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("Cannot unwrap to " + iface);
    }
    
    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this);
    }

	@Override
	public boolean autoCommitFailureClosesAllResultSets() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean dataDefinitionCausesTransactionCommit() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean dataDefinitionIgnoredInTransactions() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean doesMaxRowSizeIncludeBlobs() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean generatedKeyAlwaysReturned() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public ResultSet getAttributes(String arg0, String arg1, String arg2, String arg3) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getBestRowIdentifier(String arg0, String arg1, String arg2, int arg3, boolean arg4)
			throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}
///////////////////////////////////////////////////////
	@Override
	public String getCatalogSeparator() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getCatalogTerm() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getClientInfoProperties() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getColumnPrivileges(String arg0, String arg1, String arg2, String arg3) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Connection getConnection() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getCrossReference(String arg0, String arg1, String arg2, String arg3, String arg4, String arg5)
			throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public int getDatabaseMajorVersion() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getDatabaseMinorVersion() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getDefaultTransactionIsolation() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public ResultSet getExportedKeys(String arg0, String arg1, String arg2) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getExtraNameCharacters() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getFunctionColumns(String arg0, String arg1, String arg2, String arg3) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getFunctions(String arg0, String arg1, String arg2) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getIdentifierQuoteString() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getImportedKeys(String arg0, String arg1, String arg2) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getIndexInfo(String catalog, String schema, String table,
			boolean unique, boolean approximate) throws SQLException {
		List<Object[]> rows = new ArrayList<>();
		if (table != null) {
			File dbf = new File(connection.getPath(), table + ".DBF");
			if (!dbf.exists()) {
				dbf = new File(connection.getPath(), table + ".dbf");
			}
			if (dbf.exists()) {
				try (DBFReader reader = new DBFReader(dbf.getPath(), getCharset())) {
					List<DBFField> fields = reader.getHeader().getFields();
					for (com.dbf.jdbc.index.ntx.NtxPlanner.IndexInfo info
							: com.dbf.jdbc.index.ntx.NtxPlanner.describeIndexes(
								connection.getPath(), table, fields)) {
						int ordinal = 1;
						for (String column : info.columns) {
							rows.add(new Object[] {
								connection.getPath(),              // TABLE_CAT
								null,                              // TABLE_SCHEM
								table,                             // TABLE_NAME
								Boolean.TRUE,                      // NON_UNIQUE
								null,                              // INDEX_QUALIFIER
								info.indexName,                    // INDEX_NAME
								(short) tableIndexOther,           // TYPE
								(short) (ordinal++),               // ORDINAL_POSITION
								column,                            // COLUMN_NAME
								"A",                               // ASC_OR_DESC
								0,                                 // CARDINALITY
								0,                                 // PAGES
								info.keyExpression                 // FILTER_CONDITION (raw Clipper key)
							});
						}
					}
				} catch (Exception e) {
					// Unreadable table/index: return what we have.
				}
			}
		}
		return createObjectResultSet(new String[] {
			"TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "NON_UNIQUE", "INDEX_QUALIFIER",
			"INDEX_NAME", "TYPE", "ORDINAL_POSITION", "COLUMN_NAME", "ASC_OR_DESC",
			"CARDINALITY", "PAGES", "FILTER_CONDITION"
		}, rows);
	}

	@Override
	public int getJDBCMajorVersion() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getJDBCMinorVersion() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxBinaryLiteralLength() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxCatalogNameLength() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxCharLiteralLength() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxColumnNameLength() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxColumnsInGroupBy() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxColumnsInIndex() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxColumnsInOrderBy() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxColumnsInSelect() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxColumnsInTable() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxConnections() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxCursorNameLength() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxIndexLength() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxProcedureNameLength() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxRowSize() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxSchemaNameLength() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxStatementLength() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxStatements() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxTableNameLength() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxTablesInSelect() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public int getMaxUserNameLength() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public String getNumericFunctions() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getPrimaryKeys(String arg0, String arg1, String arg2) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getProcedureColumns(String arg0, String arg1, String arg2, String arg3) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getProcedureTerm() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getProcedures(String arg0, String arg1, String arg2) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getPseudoColumns(String arg0, String arg1, String arg2, String arg3) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public int getResultSetHoldability() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public RowIdLifetime getRowIdLifetime() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getSQLKeywords() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public int getSQLStateType() throws SQLException {
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	public String getSchemaTerm() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getSchemas(String arg0, String arg1) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getSearchStringEscape() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getStringFunctions() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getSuperTables(String arg0, String arg1, String arg2) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getSuperTypes(String arg0, String arg1, String arg2) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getSystemFunctions() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getTablePrivileges(String arg0, String arg1, String arg2) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getTableTypes() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getTimeDateFunctions() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getTypeInfo() throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getUDTs(String arg0, String arg1, String arg2, int[] arg3) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ResultSet getVersionColumns(String arg0, String arg1, String arg2) throws SQLException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean isCatalogAtStart() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean locatorsUpdateCopy() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean nullPlusNonNullIsNull() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean storesLowerCaseIdentifiers() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean storesLowerCaseQuotedIdentifiers() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean storesMixedCaseIdentifiers() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean storesMixedCaseQuotedIdentifiers() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean storesUpperCaseIdentifiers() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean storesUpperCaseQuotedIdentifiers() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsANSI92EntryLevelSQL() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsANSI92FullSQL() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsANSI92IntermediateSQL() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsAlterTableWithAddColumn() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsAlterTableWithDropColumn() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsCatalogsInDataManipulation() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsCatalogsInIndexDefinitions() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsCatalogsInPrivilegeDefinitions() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsCatalogsInProcedureCalls() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsCatalogsInTableDefinitions() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsColumnAliasing() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsConvert() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsConvert(int arg0, int arg1) throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsCoreSQLGrammar() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsCorrelatedSubqueries() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsDataDefinitionAndDataManipulationTransactions() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsDataManipulationTransactionsOnly() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsDifferentTableCorrelationNames() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsExpressionsInOrderBy() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsExtendedSQLGrammar() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsFullOuterJoins() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsGetGeneratedKeys() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsGroupBy() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsGroupByBeyondSelect() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsGroupByUnrelated() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsIntegrityEnhancementFacility() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsLikeEscapeClause() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsLimitedOuterJoins() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsMinimumSQLGrammar() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsMixedCaseIdentifiers() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsMixedCaseQuotedIdentifiers() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsMultipleOpenResults() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsMultipleResultSets() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsMultipleTransactions() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsNamedParameters() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsNonNullableColumns() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsOpenCursorsAcrossCommit() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsOpenCursorsAcrossRollback() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsOpenStatementsAcrossCommit() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsOpenStatementsAcrossRollback() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsOrderByUnrelated() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsOuterJoins() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsPositionedDelete() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsPositionedUpdate() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsResultSetHoldability(int arg0) throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsSavepoints() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsSchemasInDataManipulation() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsSchemasInIndexDefinitions() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsSchemasInPrivilegeDefinitions() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsSchemasInProcedureCalls() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsSchemasInTableDefinitions() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsSelectForUpdate() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsStatementPooling() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsStoredFunctionsUsingCallSyntax() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsStoredProcedures() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsSubqueriesInComparisons() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsSubqueriesInExists() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsSubqueriesInIns() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsSubqueriesInQuantifieds() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsTableCorrelationNames() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsTransactionIsolationLevel(int arg0) throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsTransactions() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsUnion() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean supportsUnionAll() throws SQLException {
		// TODO Auto-generated method stub
		return false;
	}
	private Charset getCharset() {
        String charsetName = connection.getCharset();
        if (charsetName == null || charsetName.isEmpty()) {
            return Charset.forName("UTF-8");
        }
        try {
            return Charset.forName(charsetName);
        } catch (Exception e) {
            return Charset.forName("UTF-8");
        }
    }
}