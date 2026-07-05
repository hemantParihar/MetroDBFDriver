package com.dbf.jdbc.execution.streaming;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

import com.dbf.jdbc.dbf.DBFReader;
import com.dbf.jdbc.index.NDXIndex;

/**
 * Optimized point lookup using index
 * Best for queries like: WHERE id = 100
 * 
 * Performance: O(log n) - single index seek + file seek
 */
public class IndexSeekOperator implements RowStream {
    private final DBFReader reader;
    private final NDXIndex index;
    private final String key;
    private final String[] columnNames;
    private final int[] columnTypes;
    private Object[] resultRow;
    private boolean fetched = false;
    private boolean closed = false;
    
    public IndexSeekOperator(DBFReader reader, NDXIndex index, String key) {
        this.reader = reader;
        this.index = index;
        this.key = key;
        
        List<com.dbf.jdbc.dbf.DBFField> fields = reader.getHeader().getFields();
        this.columnNames = new String[fields.size()];
        this.columnTypes = new int[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            columnNames[i] = fields.get(i).getName();
            columnTypes[i] = java.sql.Types.VARCHAR;
        }
    }
    
    @Override
    public Object[] next() throws IOException, SQLException {
        if (closed) return null;
        
        if (!fetched) {
            fetched = true;
            
            // Look up in index - O(log n)
            int recordNumber = index.findRecord(key);
            
            if (recordNumber > 0) {
                // Direct seek to record - O(1)
                reader.absolute(recordNumber);
                
                // Read the row
                resultRow = new Object[columnNames.length];
                for (int i = 0; i < columnNames.length; i++) {
                    resultRow[i] = reader.getValue(i);
                }
                return resultRow;
            }
        }
        
        return null;
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
        fetched = false;
        resultRow = null;
    }
    
    @Override
    public boolean supportsReset() {
        return true;
    }
    
    @Override
    public long estimateRowCount() {
        return 1;
    }
    
    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            reader.close();
        }
    }
}