package com.dbf.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

import com.dbf.jdbc.dbf.DBFField;
import com.dbf.jdbc.dbf.DBFReader;
import com.dbf.jdbc.error.SQLExceptionFactory;
import com.dbf.jdbc.execution.streaming.RowStream;
import com.dbf.jdbc.parser.ast.SelectNode;
import com.dbf.jdbc.resultset.FilterEngine;
import com.dbf.jdbc.resultset.RowProjector;

/**
 * STREAMING ResultSet - NEVER loads all rows into memory
 */
public class DBFResultSet implements ResultSet {
    // For DBFReader-based execution
    private DBFReader reader;
    private RowProjector projector;
    private FilterEngine filter;
    
    // For RowStream-based execution (streaming from operators)
    private RowStream rowStream;
    private boolean isStreamingFromOperator = false;
    
    private boolean closed = false;
    private boolean wasNull = false;
    private SQLWarning warnings = null;
    private int fetchSize = 100;
    private int fetchDirection = FETCH_FORWARD;
    
    // Streaming state
    private boolean streaming = true;
    private Object[] currentRow = null;
    private boolean hasMoreRows = true;
    private int currentRowNumber = 0;
    
    // For non-streaming (small result sets only)
    private List<Object[]> cachedRows = null;
    private int cachedIndex = -1;
    
    // Unsupported operations message cache
    private static final String UNSUPPORTED_UPDATE = "UPDATE operations not supported in read-only ResultSet";
    
    // ==================== Constructors ====================
    
    /**
     * Constructor for DBFReader-based execution (traditional)
     */
    public DBFResultSet(DBFReader reader, SelectNode selectNode) throws SQLException {
        this.reader = reader;
        this.rowStream = null;
        this.isStreamingFromOperator = false;
        this.streaming = true;
        
        this.projector = new RowProjector(reader.getHeader().getFields(), selectNode);
        
        if (selectNode != null && selectNode.getWhere() != null) {
            this.filter = new FilterEngine(selectNode.getWhere().getCondition(), projector);
        } else {
            this.filter = null;
        }
        
        // ORDER BY and GROUP BY require materialization
        boolean needsMaterialization = false;
        
        if (selectNode != null && selectNode.getOrderBy() != null) {
            needsMaterialization = true;
            System.err.println("WARNING: ORDER BY requires loading all rows into memory.");
        }
        
        if (selectNode != null && selectNode.getGroupBy() != null) {
            needsMaterialization = true;
            System.err.println("WARNING: GROUP BY requires loading all rows into memory.");
        }
        
        if (needsMaterialization) {
            this.streaming = false;
            materializeRowsFromReader();
        }
    }
    
    /**
     * Constructor for RowStream-based execution (streaming from operators)
     */
    public DBFResultSet(RowStream stream, SelectNode selectNode) throws SQLException {
        this.reader = null;
        this.rowStream = stream;
        this.isStreamingFromOperator = true;
        this.streaming = true;
        this.filter = null;
        
        // Build projector from stream column names
        String[] columnNames = stream.getColumnNames();
        List<DBFField> dummyFields = new ArrayList<>();
        for (int i = 0; i < columnNames.length; i++) {
            DBFField field = new DBFField();
            field.setName(columnNames[i]);
            field.setType('C');
            field.setLength(255);
            dummyFields.add(field);
        }
        
        this.projector = new RowProjector(dummyFields, selectNode);
        this.cachedRows = null;
        this.cachedIndex = -1;
        this.currentRow = null;
        this.currentRowNumber = 0;
        this.hasMoreRows = true;
    }
    
    // ==================== Materialization Methods ====================
    
    private void materializeRowsFromReader() throws SQLException {
        List<Object[]> allRows = new ArrayList<>();
        try {
            reader.beforeFirst();
            while (reader.next()) {
                Object[] fullRow = readFullRowFromReader();
                if (filter == null || filter.matches(fullRow)) {
                    allRows.add(projector.projectRow(fullRow));
                }
            }
            cachedRows = allRows;
            streaming = false;
        } catch (IOException e) {
            throw SQLExceptionFactory.ioError("Failed to read DBF file", e);
        }
    }
    
    private void materializeRowsFromStream() throws SQLException {
        List<Object[]> allRows = new ArrayList<>();
        try {
            Object[] row;
            while ((row = rowStream.next()) != null) {
                allRows.add(row);
            }
            cachedRows = allRows;
            streaming = false;
        } catch (IOException e) {
            throw SQLExceptionFactory.ioError("Failed to read from stream", e);
        }
    }
    
    private Object[] readFullRowFromReader() throws IOException {
        List<DBFField> fields = reader.getHeader().getFields();
        Object[] row = new Object[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            try {
				row[i] = reader.getValue(i);
			} catch (IOException | SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
        }
        return row;
    }
    
    // ==================== Core Navigation ====================
    
    @Override
    public boolean next() throws SQLException {
        checkClosed();
        
        if (streaming) {
            if (isStreamingFromOperator) {
                return nextFromStream();
            } else {
                return nextFromReader();
            }
        } else {
            return nextCached();
        }
    }
    
    private boolean nextFromStream() throws SQLException {
        try {
            if (rowStream == null) return false;
            
            currentRow = rowStream.next();
            if (currentRow != null) {
                currentRowNumber++;
                return true;
            }
            return false;
        } catch (IOException e) {
            throw SQLExceptionFactory.ioError("Error reading next row from stream", e);
        }
    }
    
 // In DBFResultSet.java, update nextFromReader() method:

    private boolean nextFromReader() throws SQLException {
        try {
            while (hasMoreRows) {
                if (!reader.next()) {
                    hasMoreRows = false;
                    currentRow = null;
                    return false;
                }
                
                Object[] fullRow = readFullRowFromReader();
                
                if (filter == null || filter.matches(fullRow)) {
                    // Pass the current record number (1-based) to projectRow
                    int recnoValue = reader.getCurrentRecord() + 1;
                    currentRow = projector.projectRow(fullRow, recnoValue);
                    currentRowNumber++;
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            throw SQLExceptionFactory.ioError("Error reading next row", e);
        }
    }
    
    private boolean nextCached() throws SQLException {
        if (cachedRows == null || cachedIndex + 1 >= cachedRows.size()) {
            currentRow = null;
            return false;
        }
        cachedIndex++;
        currentRow = cachedRows.get(cachedIndex);
        currentRowNumber++;
        return true;
    }
    
    @Override
    public void close() throws SQLException {
        if (!closed) {
            closed = true;
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    throw SQLExceptionFactory.ioError("Error closing reader", e);
                }
            }
            if (rowStream != null) {
                try {
                    rowStream.close();
                } catch (IOException e) {
                    throw SQLExceptionFactory.ioError("Error closing stream", e);
                }
            }
        }
    }
    
    @Override
    public boolean wasNull() throws SQLException { 
        return wasNull; 
    }
    
    // ==================== Data Getters ====================
    
 // In DBFResultSet.java, modify getValue() method:

    private Object getValue(int columnIndex) throws SQLException {
        if (currentRow == null) {
            throw SQLExceptionFactory.noCurrentRow();
        }
        
        int idx = columnIndex - 1;
        if (idx < 0) {
            throw SQLExceptionFactory.invalidColumnIndex(columnIndex);
        }
        
        // Check if this is the RECNO pseudo-column
        if (projector != null && projector.isRecnoColumn(columnIndex)) {
            // Return the current record number (1-based)
            return currentRowNumber;
        }
        
        if (idx >= currentRow.length) {
            throw SQLExceptionFactory.invalidColumnIndex(columnIndex);
        }
        
        Object value = currentRow[idx];
        wasNull = (value == null);
        return value;
    }

    private boolean isRecnoColumn(int columnIndex) {
        // Check if this is the RECNO() pseudo-column
        // You can implement this by having a special column name like "RECNO"
        if (projector != null && columnIndex <= projector.getColumnCount()) {
            String colName = projector.getColumnLabel(columnIndex);
            return "RECNO".equalsIgnoreCase(colName) || "RECNO()".equalsIgnoreCase(colName);
        }
        return false;
    }
    
    private Number getNumber(int columnIndex) throws SQLException {
        Object v = getValue(columnIndex);
        if (v == null) return null;
        if (v instanceof Number) return (Number) v;
        try {
            String s = v.toString().trim();
            if (s.isEmpty()) return null;
            return s.contains(".") ? Double.parseDouble(s) : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
    
    @Override public String getString(int col) throws SQLException { 
        Object v = getValue(col);
        return v != null ? v.toString() : null;
    }
    
    @Override public boolean getBoolean(int col) throws SQLException {
        Object v = getValue(col);
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        String s = v.toString();
        return s.equalsIgnoreCase("Y") || s.equalsIgnoreCase("T") || s.equalsIgnoreCase("TRUE");
    }
    
    @Override public byte getByte(int col) throws SQLException {
        Number n = getNumber(col);
        return n != null ? n.byteValue() : 0;
    }
    
    @Override public short getShort(int col) throws SQLException {
        Number n = getNumber(col);
        return n != null ? n.shortValue() : 0;
    }
    
    @Override public int getInt(int col) throws SQLException {
        Number n = getNumber(col);
        return n != null ? n.intValue() : 0;
    }
    
    @Override public long getLong(int col) throws SQLException {
        Number n = getNumber(col);
        return n != null ? n.longValue() : 0;
    }
    
    @Override public float getFloat(int col) throws SQLException {
        Number n = getNumber(col);
        return n != null ? n.floatValue() : 0;
    }
    
    @Override public double getDouble(int col) throws SQLException {
        Number n = getNumber(col);
        return n != null ? n.doubleValue() : 0;
    }
    
    @Override public BigDecimal getBigDecimal(int col, int scale) throws SQLException {
        Number n = getNumber(col);
        if (n == null) return null;
        return BigDecimal.valueOf(n.doubleValue()).setScale(scale, BigDecimal.ROUND_HALF_UP);
    }
    
    @Override public BigDecimal getBigDecimal(int col) throws SQLException {
        return getBigDecimal(col, 0);
    }
    
    @Override public byte[] getBytes(int col) throws SQLException {
        Object v = getValue(col);
        if (v == null) return null;
        if (v instanceof byte[]) return (byte[]) v;
        return v.toString().getBytes();
    }
    
    @Override public Date getDate(int col) throws SQLException {
        Object v = getValue(col);
        if (v == null) return null;
        if (v instanceof Date) return (Date) v;
        if (v instanceof java.util.Date) return new Date(((java.util.Date) v).getTime());
        try {
            return Date.valueOf(v.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
    
    @Override public Time getTime(int col) throws SQLException {
        Date d = getDate(col);
        return d != null ? new Time(d.getTime()) : null;
    }
    
    @Override public Timestamp getTimestamp(int col) throws SQLException {
        Date d = getDate(col);
        return d != null ? new Timestamp(d.getTime()) : null;
    }
    
    @Override public Object getObject(int col) throws SQLException {
        return getValue(col);
    }
    
    @Override public InputStream getAsciiStream(int col) throws SQLException {
        String s = getString(col);
        return s != null ? new java.io.ByteArrayInputStream(s.getBytes()) : null;
    }
    
    @Override public InputStream getBinaryStream(int col) throws SQLException {
        byte[] b = getBytes(col);
        return b != null ? new java.io.ByteArrayInputStream(b) : null;
    }
    
    @Override public Reader getCharacterStream(int col) throws SQLException {
        String s = getString(col);
        return s != null ? new java.io.StringReader(s) : null;
    }
    
    @Override public InputStream getUnicodeStream(int col) throws SQLException {
        return getAsciiStream(col);
    }
    
    // ==================== Column Label Methods ====================
    
    @Override public String getString(String label) throws SQLException { return getString(findColumn(label)); }
    @Override public boolean getBoolean(String label) throws SQLException { return getBoolean(findColumn(label)); }
    @Override public byte getByte(String label) throws SQLException { return getByte(findColumn(label)); }
    @Override public short getShort(String label) throws SQLException { return getShort(findColumn(label)); }
    @Override public int getInt(String label) throws SQLException { return getInt(findColumn(label)); }
    @Override public long getLong(String label) throws SQLException { return getLong(findColumn(label)); }
    @Override public float getFloat(String label) throws SQLException { return getFloat(findColumn(label)); }
    @Override public double getDouble(String label) throws SQLException { return getDouble(findColumn(label)); }
    @Override public BigDecimal getBigDecimal(String label) throws SQLException { return getBigDecimal(findColumn(label)); }
    @Override public BigDecimal getBigDecimal(String label, int scale) throws SQLException { return getBigDecimal(findColumn(label), scale); }
    @Override public byte[] getBytes(String label) throws SQLException { return getBytes(findColumn(label)); }
    @Override public Date getDate(String label) throws SQLException { return getDate(findColumn(label)); }
    @Override public Time getTime(String label) throws SQLException { return getTime(findColumn(label)); }
    @Override public Timestamp getTimestamp(String label) throws SQLException { return getTimestamp(findColumn(label)); }
    @Override public Object getObject(String label) throws SQLException { return getObject(findColumn(label)); }
    @Override public InputStream getAsciiStream(String label) throws SQLException { return getAsciiStream(findColumn(label)); }
    @Override public InputStream getBinaryStream(String label) throws SQLException { return getBinaryStream(findColumn(label)); }
    @Override public Reader getCharacterStream(String label) throws SQLException { return getCharacterStream(findColumn(label)); }
    @Override public InputStream getUnicodeStream(String label) throws SQLException { return getUnicodeStream(findColumn(label)); }
    
    @Override
    public int findColumn(String columnLabel) throws SQLException {
        if (projector == null) {
            throw SQLExceptionFactory.create("No column metadata available");
        }
        int idx = projector.getColumnIndex(columnLabel);
        if (idx == -1) {
            throw SQLExceptionFactory.invalidColumn(columnLabel);
        }
        return idx + 1;
    }
    
    // ==================== Navigation Status ====================
    
    @Override public boolean isBeforeFirst() throws SQLException { return currentRowNumber == 0 && hasMoreRows; }
    @Override public boolean isAfterLast() throws SQLException { return !hasMoreRows && currentRow == null; }
    @Override public boolean isFirst() throws SQLException { return currentRowNumber == 1; }
    @Override public boolean isLast() throws SQLException { return !hasMoreRows && currentRow != null; }
    @Override public void beforeFirst() throws SQLException { 
        if (reader != null) {
            reader.beforeFirst();
        }
        if (rowStream != null) {
            try { rowStream.reset(); } catch (IOException e) { throw SQLExceptionFactory.ioError("Reset failed", e); }
        }
        currentRow = null;
        currentRowNumber = 0;
        hasMoreRows = true;
        if (!streaming && cachedRows != null) {
            cachedIndex = -1;
        }
    }
    @Override public void afterLast() throws SQLException { 
        if (reader != null) {
            reader.afterLast();
        }
        currentRow = null;
        hasMoreRows = false;
    }
    @Override public boolean first() throws SQLException { beforeFirst(); return next(); }
    @Override public boolean last() throws SQLException { throw unsupported("last() in streaming mode"); }
    @Override public int getRow() throws SQLException { return currentRowNumber; }
    @Override public boolean absolute(int row) throws SQLException { throw unsupported("absolute() in streaming mode"); }
    @Override public boolean relative(int rows) throws SQLException { throw unsupported("relative() in streaming mode"); }
    @Override public boolean previous() throws SQLException { throw unsupported("previous() in streaming mode"); }
    
    // ==================== Metadata ====================
    
    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        if (reader != null) {
            return new DBFResultSetMetaData(this, reader.getHeader().getFields(), 
                projector.getSelectedColumnIndexes(), projector.getColumnLabels());
        } else if (rowStream != null) {
            // Build metadata from stream
            String[] colNames = rowStream.getColumnNames();
            List<DBFField> dummyFields = new ArrayList<>();
            for (String name : colNames) {
                DBFField field = new DBFField();
                field.setName(name);
                field.setType('C');
                dummyFields.add(field);
            }
            List<Integer> allIndexes = new ArrayList<>();
            for (int i = 0; i < colNames.length; i++) {
                allIndexes.add(i);
            }
            return new DBFResultSetMetaData(this, dummyFields, allIndexes, Arrays.asList(colNames));
        }
        throw SQLExceptionFactory.create("No metadata available");
    }
    
    @Override public String getCursorName() throws SQLException { return null; }
    @Override public Statement getStatement() throws SQLException { return null; }
    @Override public SQLWarning getWarnings() throws SQLException { return warnings; }
    @Override public void clearWarnings() throws SQLException { warnings = null; }
    @Override public boolean isClosed() throws SQLException { return closed; }
    @Override public void setFetchSize(int rows) throws SQLException { this.fetchSize = rows; }
    @Override public int getFetchSize() throws SQLException { return fetchSize; }
    @Override public void setFetchDirection(int d) throws SQLException { this.fetchDirection = d; }
    @Override public int getFetchDirection() throws SQLException { return fetchDirection; }
    @Override public int getType() throws SQLException { return TYPE_FORWARD_ONLY; }
    @Override public int getConcurrency() throws SQLException { return CONCUR_READ_ONLY; }
    @Override public int getHoldability() throws SQLException { return HOLD_CURSORS_OVER_COMMIT; }
    
    // ==================== Unsupported Methods ====================
    
    private SQLException unsupported(String operation) {
        return SQLExceptionFactory.featureNotSupported(operation);
    }
    
    private void throwUnsupported() throws SQLException {
        throw SQLExceptionFactory.featureNotSupported(UNSUPPORTED_UPDATE);
    }
    
    @Override public boolean rowUpdated() throws SQLException { return false; }
    @Override public boolean rowInserted() throws SQLException { return false; }
    @Override public boolean rowDeleted() throws SQLException { return false; }
    
    @Override public void updateNull(int col) throws SQLException { throwUnsupported(); }
    @Override public void updateBoolean(int col, boolean x) throws SQLException { throwUnsupported(); }
    @Override public void updateByte(int col, byte x) throws SQLException { throwUnsupported(); }
    @Override public void updateShort(int col, short x) throws SQLException { throwUnsupported(); }
    @Override public void updateInt(int col, int x) throws SQLException { throwUnsupported(); }
    @Override public void updateLong(int col, long x) throws SQLException { throwUnsupported(); }
    @Override public void updateFloat(int col, float x) throws SQLException { throwUnsupported(); }
    @Override public void updateDouble(int col, double x) throws SQLException { throwUnsupported(); }
    @Override public void updateBigDecimal(int col, BigDecimal x) throws SQLException { throwUnsupported(); }
    @Override public void updateString(int col, String x) throws SQLException { throwUnsupported(); }
    @Override public void updateBytes(int col, byte[] x) throws SQLException { throwUnsupported(); }
    @Override public void updateDate(int col, Date x) throws SQLException { throwUnsupported(); }
    @Override public void updateTime(int col, Time x) throws SQLException { throwUnsupported(); }
    @Override public void updateTimestamp(int col, Timestamp x) throws SQLException { throwUnsupported(); }
    @Override public void updateAsciiStream(int col, InputStream x, int l) throws SQLException { throwUnsupported(); }
    @Override public void updateBinaryStream(int col, InputStream x, int l) throws SQLException { throwUnsupported(); }
    @Override public void updateCharacterStream(int col, Reader x, int l) throws SQLException { throwUnsupported(); }
    @Override public void updateObject(int col, Object x, int s) throws SQLException { throwUnsupported(); }
    @Override public void updateObject(int col, Object x) throws SQLException { throwUnsupported(); }
    @Override public void updateNull(String label) throws SQLException { throwUnsupported(); }
    @Override public void updateBoolean(String label, boolean x) throws SQLException { throwUnsupported(); }
    @Override public void updateByte(String label, byte x) throws SQLException { throwUnsupported(); }
    @Override public void updateShort(String label, short x) throws SQLException { throwUnsupported(); }
    @Override public void updateInt(String label, int x) throws SQLException { throwUnsupported(); }
    @Override public void updateLong(String label, long x) throws SQLException { throwUnsupported(); }
    @Override public void updateFloat(String label, float x) throws SQLException { throwUnsupported(); }
    @Override public void updateDouble(String label, double x) throws SQLException { throwUnsupported(); }
    @Override public void updateBigDecimal(String label, BigDecimal x) throws SQLException { throwUnsupported(); }
    @Override public void updateString(String label, String x) throws SQLException { throwUnsupported(); }
    @Override public void updateBytes(String label, byte[] x) throws SQLException { throwUnsupported(); }
    @Override public void updateDate(String label, Date x) throws SQLException { throwUnsupported(); }
    @Override public void updateTime(String label, Time x) throws SQLException { throwUnsupported(); }
    @Override public void updateTimestamp(String label, Timestamp x) throws SQLException { throwUnsupported(); }
    @Override public void updateAsciiStream(String label, InputStream x, int l) throws SQLException { throwUnsupported(); }
    @Override public void updateBinaryStream(String label, InputStream x, int l) throws SQLException { throwUnsupported(); }
    @Override public void updateCharacterStream(String label, Reader x, int l) throws SQLException { throwUnsupported(); }
    @Override public void updateObject(String label, Object x, int s) throws SQLException { throwUnsupported(); }
    @Override public void updateObject(String label, Object x) throws SQLException { throwUnsupported(); }
    @Override public void insertRow() throws SQLException { throwUnsupported(); }
    @Override public void updateRow() throws SQLException { throwUnsupported(); }
    @Override public void deleteRow() throws SQLException { throwUnsupported(); }
    @Override public void refreshRow() throws SQLException { }
    @Override public void cancelRowUpdates() throws SQLException { }
    @Override public void moveToInsertRow() throws SQLException { throwUnsupported(); }
    @Override public void moveToCurrentRow() throws SQLException { }
    
    @Override public <T> T getObject(int col, Class<T> type) throws SQLException {
        Object obj = getObject(col);
        if (type.isInstance(obj)) return type.cast(obj);
        throw SQLExceptionFactory.dataConversionError("column_" + col, 
            obj != null ? obj.getClass().getSimpleName() : "null", type.getSimpleName());
    }
    @Override public <T> T getObject(String label, Class<T> type) throws SQLException { 
        return getObject(findColumn(label), type); 
    }
    
    @Override public RowId getRowId(int col) throws SQLException { throw unsupported("getRowId"); }
    @Override public RowId getRowId(String label) throws SQLException { throw unsupported("getRowId"); }
    @Override public void updateRowId(int col, RowId x) throws SQLException { throwUnsupported(); }
    @Override public void updateRowId(String label, RowId x) throws SQLException { throwUnsupported(); }
    @Override public void updateNString(int col, String s) throws SQLException { throwUnsupported(); }
    @Override public void updateNString(String label, String s) throws SQLException { throwUnsupported(); }
    @Override public void updateNClob(int col, NClob n) throws SQLException { throwUnsupported(); }
    @Override public void updateNClob(String label, NClob n) throws SQLException { throwUnsupported(); }
    @Override public NClob getNClob(int col) throws SQLException { return null; }
    @Override public NClob getNClob(String label) throws SQLException { return null; }
    @Override public SQLXML getSQLXML(int col) throws SQLException { return null; }
    @Override public SQLXML getSQLXML(String label) throws SQLException { return null; }
    @Override public void updateSQLXML(int col, SQLXML x) throws SQLException { throwUnsupported(); }
    @Override public void updateSQLXML(String label, SQLXML x) throws SQLException { throwUnsupported(); }
    @Override public String getNString(int col) throws SQLException { return getString(col); }
    @Override public String getNString(String label) throws SQLException { return getString(label); }
    @Override public Reader getNCharacterStream(int col) throws SQLException { return getCharacterStream(col); }
    @Override public Reader getNCharacterStream(String label) throws SQLException { return getCharacterStream(label); }
    @Override public void updateNCharacterStream(int col, Reader x, long l) throws SQLException { throwUnsupported(); }
    @Override public void updateNCharacterStream(String label, Reader x, long l) throws SQLException { throwUnsupported(); }
    @Override public void updateAsciiStream(int col, InputStream x, long l) throws SQLException { throwUnsupported(); }
    @Override public void updateBinaryStream(int col, InputStream x, long l) throws SQLException { throwUnsupported(); }
    @Override public void updateCharacterStream(int col, Reader x, long l) throws SQLException { throwUnsupported(); }
    @Override public void updateAsciiStream(String label, InputStream x, long l) throws SQLException { throwUnsupported(); }
    @Override public void updateBinaryStream(String label, InputStream x, long l) throws SQLException { throwUnsupported(); }
    @Override public void updateCharacterStream(String label, Reader x, long l) throws SQLException { throwUnsupported(); }
    @Override public void updateBlob(int col, InputStream x, long l) throws SQLException { throwUnsupported(); }
    @Override public void updateBlob(String label, InputStream x, long l) throws SQLException { throwUnsupported(); }
    @Override public void updateClob(int col, Reader x, long l) throws SQLException { throwUnsupported(); }
    @Override public void updateClob(String label, Reader x, long l) throws SQLException { throwUnsupported(); }
    @Override public void updateNClob(int col, Reader x, long l) throws SQLException { throwUnsupported(); }
    @Override public void updateNClob(String label, Reader x, long l) throws SQLException { throwUnsupported(); }
    @Override public void updateNCharacterStream(int col, Reader x) throws SQLException { throwUnsupported(); }
    @Override public void updateNCharacterStream(String label, Reader x) throws SQLException { throwUnsupported(); }
    @Override public void updateAsciiStream(int col, InputStream x) throws SQLException { throwUnsupported(); }
    @Override public void updateBinaryStream(int col, InputStream x) throws SQLException { throwUnsupported(); }
    @Override public void updateCharacterStream(int col, Reader x) throws SQLException { throwUnsupported(); }
    @Override public void updateAsciiStream(String label, InputStream x) throws SQLException { throwUnsupported(); }
    @Override public void updateBinaryStream(String label, InputStream x) throws SQLException { throwUnsupported(); }
    @Override public void updateCharacterStream(String label, Reader x) throws SQLException { throwUnsupported(); }
    @Override public void updateBlob(int col, InputStream x) throws SQLException { throwUnsupported(); }
    @Override public void updateBlob(String label, InputStream x) throws SQLException { throwUnsupported(); }
    @Override public void updateClob(int col, Reader x) throws SQLException { throwUnsupported(); }
    @Override public void updateClob(String label, Reader x) throws SQLException { throwUnsupported(); }
    @Override public void updateNClob(int col, Reader x) throws SQLException { throwUnsupported(); }
    @Override public void updateNClob(String label, Reader x) throws SQLException { throwUnsupported(); }
    
    @Override public Date getDate(int col, Calendar cal) throws SQLException { return getDate(col); }
    @Override public Date getDate(String label, Calendar cal) throws SQLException { return getDate(label); }
    @Override public Time getTime(int col, Calendar cal) throws SQLException { return getTime(col); }
    @Override public Time getTime(String label, Calendar cal) throws SQLException { return getTime(label); }
    @Override public Timestamp getTimestamp(int col, Calendar cal) throws SQLException { return getTimestamp(col); }
    @Override public Timestamp getTimestamp(String label, Calendar cal) throws SQLException { return getTimestamp(label); }
    
    public void closeOnCompletion() throws SQLException { }
    public boolean isCloseOnCompletion() throws SQLException { return false; }
    @Override public Object getObject(int col, Map<String, Class<?>> map) throws SQLException { return getObject(col); }
    @Override public Object getObject(String label, Map<String, Class<?>> map) throws SQLException { return getObject(label); }
    @Override public Ref getRef(int col) throws SQLException { return null; }
    @Override public Ref getRef(String label) throws SQLException { return null; }
    @Override public Blob getBlob(int col) throws SQLException { return null; }
    @Override public Blob getBlob(String label) throws SQLException { return null; }
    @Override public Clob getClob(int col) throws SQLException { return null; }
    @Override public Clob getClob(String label) throws SQLException { return null; }
    @Override public Array getArray(int col) throws SQLException { return null; }
    @Override public Array getArray(String label) throws SQLException { return null; }
    @Override public URL getURL(int col) throws SQLException { return null; }
    @Override public URL getURL(String label) throws SQLException { return null; }
    @Override public void updateRef(int col, Ref x) throws SQLException { throwUnsupported(); }
    @Override public void updateRef(String label, Ref x) throws SQLException { throwUnsupported(); }
    @Override public void updateBlob(int col, Blob x) throws SQLException { throwUnsupported(); }
    @Override public void updateBlob(String label, Blob x) throws SQLException { throwUnsupported(); }
    @Override public void updateClob(int col, Clob x) throws SQLException { throwUnsupported(); }
    @Override public void updateClob(String label, Clob x) throws SQLException { throwUnsupported(); }
    @Override public void updateArray(int col, Array x) throws SQLException { throwUnsupported(); }
    @Override public void updateArray(String label, Array x) throws SQLException { throwUnsupported(); }
    
    @Override @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return (T) this;
        throw SQLExceptionFactory.create("Cannot unwrap to " + iface);
    }
    
    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
    
    private void checkClosed() throws SQLException {
        if (closed) {
            throw SQLExceptionFactory.resultSetClosed();
        }
    }
    
    public RowProjector getProjector() { return projector; }
}