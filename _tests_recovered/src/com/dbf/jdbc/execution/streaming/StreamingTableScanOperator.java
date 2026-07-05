package com.dbf.jdbc.execution.streaming;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

import com.dbf.jdbc.dbf.DBFField;
import com.dbf.jdbc.dbf.DBFReader;

/**
 * Streaming table scan - yields rows one by one, never caches
 */
public class StreamingTableScanOperator implements RowStream {
    private final DBFReader reader;
    private final String tableName;
    private final String alias;
    private final String[] columnNames;
    private final int[] columnTypes;
    private boolean closed = false;
    private boolean hasNextRow = true;
    
    public StreamingTableScanOperator(DBFReader reader, String tableName, String alias) {
        this.reader = reader;
        this.tableName = tableName;
        this.alias = alias;
        
        // Build column metadata
        List<DBFField> fields = reader.getHeader().getFields();
        this.columnNames = new String[fields.size()];
        this.columnTypes = new int[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            columnNames[i] = fields.get(i).getName();
            columnTypes[i] = mapFieldTypeToSqlType(fields.get(i).getType());
        }
    }
    
    private int mapFieldTypeToSqlType(char dbfType) {
        switch (dbfType) {
            case 'C': return java.sql.Types.VARCHAR;
            case 'N': return java.sql.Types.DOUBLE;
            case 'D': return java.sql.Types.DATE;
            case 'L': return java.sql.Types.BOOLEAN;
            case 'I': return java.sql.Types.INTEGER;
            default: return java.sql.Types.VARCHAR;
        }
    }
    
    @Override
    public Object[] next() throws IOException, SQLException {
        if (!hasNextRow || closed) return null;
        
        if (reader.next()) {
            Object[] row = new Object[columnNames.length];
            for (int i = 0; i < columnNames.length; i++) {
                row[i] = reader.getValue(i);
            }
            return row;
        }
        
        hasNextRow = false;
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
        reader.beforeFirst();
        hasNextRow = true;
    }
    
    @Override
    public boolean supportsReset() {
        return true;
    }
    
    @Override
    public long estimateRowCount() {
        return reader.getHeader().getRecordCount();
    }
    
    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            reader.close();
        }
    }
}