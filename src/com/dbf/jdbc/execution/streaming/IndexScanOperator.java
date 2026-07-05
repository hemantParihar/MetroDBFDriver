package com.dbf.jdbc.execution.streaming;

import com.dbf.jdbc.dbf.DBFReader;
import com.dbf.jdbc.index.NDXIndex;
import com.dbf.jdbc.index.MDXIndex;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;

/**
 * Index Scan Operator - Uses NDX/MDX index to directly locate records
 * Avoids full table scan for equality and range queries
 * 
 * Performance: O(log n) instead of O(n)
 */
public class IndexScanOperator implements RowStream {
    private final DBFReader reader;
    private final String tableName;
    private final String alias;
    private final String indexName;
    private final String indexedColumn;
    private final NDXIndex ndxIndex;
    private final MDXIndex mdxIndex;
    private final boolean isRangeScan;
    private final Object startKey;
    private final Object endKey;
    private final boolean descending;
    
    private List<Integer> recordNumbers;
    private int currentPosition = -1;
    private boolean closed = false;
    private String[] columnNames;
    private int[] columnTypes;
    private boolean initialized = false;
    
    // For single key lookup
    private boolean singleLookupDone = false;
    private Object[] singleRow = null;
    
    public IndexScanOperator(DBFReader reader, String tableName, String alias, 
                             NDXIndex ndxIndex, String indexedColumn, Object key) {
        this.reader = reader;
        this.tableName = tableName;
        this.alias = alias;
        this.ndxIndex = ndxIndex;
        this.mdxIndex = null;
        this.indexedColumn = indexedColumn;
        this.indexName = ndxIndex.getIndexFilePath();
        this.isRangeScan = false;
        this.startKey = key;
        this.endKey = null;
        this.descending = false;
        initializeColumnInfo();
    }
    
    public IndexScanOperator(DBFReader reader, String tableName, String alias,
                             MDXIndex mdxIndex, String tagName, String indexedColumn, Object key) {
        this.reader = reader;
        this.tableName = tableName;
        this.alias = alias;
        this.ndxIndex = null;
        this.mdxIndex = mdxIndex;
        this.indexedColumn = indexedColumn;
        this.indexName = tagName;
        this.isRangeScan = false;
        this.startKey = key;
        this.endKey = null;
        this.descending = false;
        initializeColumnInfo();
    }
    
    public IndexScanOperator(DBFReader reader, String tableName, String alias,
                             NDXIndex ndxIndex, String indexedColumn, 
                             Object startKey, Object endKey, boolean descending) {
        this.reader = reader;
        this.tableName = tableName;
        this.alias = alias;
        this.ndxIndex = ndxIndex;
        this.mdxIndex = null;
        this.indexedColumn = indexedColumn;
        this.indexName = ndxIndex.getIndexFilePath();
        this.isRangeScan = true;
        this.startKey = startKey;
        this.endKey = endKey;
        this.descending = descending;
        initializeColumnInfo();
    }
    
    private void initializeColumnInfo() {
        List<com.dbf.jdbc.dbf.DBFField> fields = reader.getHeader().getFields();
        this.columnNames = new String[fields.size()];
        this.columnTypes = new int[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            columnNames[i] = fields.get(i).getName();
            columnTypes[i] = java.sql.Types.VARCHAR;
        }
    }
    
    private void initialize() throws IOException, SQLException {
        if (initialized) return;
        
        if (isRangeScan) {
            performRangeLookup();
        } else {
            performSingleLookup();
        }
        
        initialized = true;
    }
    
    private void performSingleLookup() throws IOException, SQLException {
        int recordNumber = -1;
        String keyStr = startKey != null ? startKey.toString() : null;
        
        if (ndxIndex != null) {
            recordNumber = ndxIndex.findRecord(keyStr);
        } else if (mdxIndex != null) {
            recordNumber = mdxIndex.findRecord(indexName, keyStr);
        }
        
        if (recordNumber > 0) {
            reader.absolute(recordNumber);
            singleRow = readCurrentRow();
        }
        singleLookupDone = true;
    }
    
    private void performRangeLookup() throws IOException, SQLException {
        List<Integer> records = new ArrayList<>();
        String startStr = startKey != null ? startKey.toString() : null;
        String endStr = endKey != null ? endKey.toString() : null;
        
        if (ndxIndex != null) {
            if (startStr != null && endStr != null) {
                records = ndxIndex.findRange(startStr, endStr);
            } else if (startStr != null) {
                records = ndxIndex.findRange(startStr, "ZZZZZZZZZ");
            } else if (endStr != null) {
                records = ndxIndex.findRange("", endStr);
            }
        } else if (mdxIndex != null) {
            if (startStr != null && endStr != null) {
                records = mdxIndex.findRange(indexName, startStr, endStr);
            } else if (startStr != null) {
                records = mdxIndex.findRange(indexName, startStr, "ZZZZZZZZZ");
            } else if (endStr != null) {
                records = mdxIndex.findRange(indexName, "", endStr);
            }
        }
        
        if (descending) {
            Collections.reverse(records);
        }
        
        this.recordNumbers = records;
        this.currentPosition = -1;
    }
    
    private Object[] readCurrentRow() throws IOException {
        Object[] row = new Object[columnNames.length];
        for (int i = 0; i < columnNames.length; i++) {
            try {
				row[i] = reader.getValue(i);
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
        }
        return row;
    }
    
    @Override
    public Object[] next() throws IOException, SQLException {
        if (closed) return null;
        
        if (!initialized) {
            initialize();
        }
        
        if (!isRangeScan) {
            // Single lookup mode
            if (singleRow != null) {
                Object[] result = singleRow;
                singleRow = null;
                return result;
            }
            return null;
        }
        
        // Range scan mode
        if (recordNumbers == null || currentPosition + 1 >= recordNumbers.size()) {
            return null;
        }
        
        currentPosition++;
        int recordNumber = recordNumbers.get(currentPosition);
        reader.absolute(recordNumber);
        return readCurrentRow();
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
        if (isRangeScan) {
            recordNumbers = null;
            currentPosition = -1;
        } else {
            singleLookupDone = false;
            singleRow = null;
        }
        initialized = false;
    }
    
    @Override
    public boolean supportsReset() {
        return true;
    }
    
    @Override
    public long estimateRowCount() {
        if (isRangeScan) {
            if (recordNumbers != null) return recordNumbers.size();
            return reader.getHeader().getRecordCount() / 100;
        }
        return 1;
    }
    
    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            if (reader != null) {
                reader.close();
            }
        }
    }
    
    public String getIndexedColumn() {
        return indexedColumn;
    }
    
    public String getIndexName() {
        return indexName;
    }
}