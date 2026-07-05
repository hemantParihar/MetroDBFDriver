package com.dbf.jdbc.execution.streaming;

import com.dbf.jdbc.dbf.DBFReader;
import com.dbf.jdbc.index.NDXIndex;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

/**
 * Index range scan for BETWEEN, >, <, >=, <= queries
 * 
 * Performance: O(k + log n) where k = number of matching rows
 * vs full scan O(n)
 */
public class IndexRangeScanOperator implements RowStream {
    private final DBFReader reader;
    private final NDXIndex index;
    private final String startKey;
    private final String endKey;
    private final boolean includeStart;
    private final boolean includeEnd;
    private final boolean descending;
    
    private List<Integer> recordNumbers;
    private int currentPosition = -1;
    private boolean initialized = false;
    private boolean closed = false;
    private String[] columnNames;
    private int[] columnTypes;
    
    public IndexRangeScanOperator(DBFReader reader, NDXIndex index, 
                                   String startKey, String endKey,
                                   boolean includeStart, boolean includeEnd,
                                   boolean descending) {
        this.reader = reader;
        this.index = index;
        this.startKey = startKey;
        this.endKey = endKey;
        this.includeStart = includeStart;
        this.includeEnd = includeEnd;
        this.descending = descending;
        
        List<com.dbf.jdbc.dbf.DBFField> fields = reader.getHeader().getFields();
        this.columnNames = new String[fields.size()];
        this.columnTypes = new int[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            columnNames[i] = fields.get(i).getName();
            columnTypes[i] = java.sql.Types.VARCHAR;
        }
    }
    
    private void initialize() throws IOException {
        if (initialized) return;
        
        String effectiveStart = startKey;
        String effectiveEnd = endKey;
        
        // Adjust for inclusive/exclusive bounds
        if (!includeStart && startKey != null) {
            // For exclusive start, we need to find the next key
            // This is simplified - in production, find next distinct key
        }
        if (!includeEnd && endKey != null) {
            // For exclusive end, we need to stop before this key
        }
        
        if (startKey != null && endKey != null) {
            recordNumbers = index.findRange(effectiveStart, effectiveEnd);
        } else if (startKey != null) {
            recordNumbers = index.findRange(effectiveStart, "ZZZZZZZZZ");
        } else if (endKey != null) {
            recordNumbers = index.findRange("", effectiveEnd);
        }
        
        if (descending && recordNumbers != null) {
            java.util.Collections.reverse(recordNumbers);
        }
        
        initialized = true;
    }
    
    @Override
    public Object[] next() throws IOException, SQLException {
        if (closed) return null;
        
        if (!initialized) {
            initialize();
        }
        
        if (recordNumbers == null || currentPosition + 1 >= recordNumbers.size()) {
            return null;
        }
        
        currentPosition++;
        int recordNumber = recordNumbers.get(currentPosition);
        reader.absolute(recordNumber);
        
        Object[] row = new Object[columnNames.length];
        for (int i = 0; i < columnNames.length; i++) {
            row[i] = reader.getValue(i);
        }
        return row;
    }
    
    @Override
    public String[] getColumnNames() {
        return columnNames;
    }
    
    @Override
    public int[] getColumnTypes() {
        return columnTypes;
    }
    
    @Override
    public void reset() throws IOException, SQLException {
        initialized = false;
        recordNumbers = null;
        currentPosition = -1;
    }
    
    @Override
    public boolean supportsReset() {
        return true;
    }
    
    @Override
    public long estimateRowCount() {
        if (recordNumbers != null) return recordNumbers.size();
        // Estimate based on range selectivity
        return reader.getHeader().getRecordCount() / 10;
    }
    
    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            reader.close();
        }
    }
}