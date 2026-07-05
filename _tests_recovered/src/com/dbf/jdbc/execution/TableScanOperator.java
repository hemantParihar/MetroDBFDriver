package com.dbf.jdbc.execution;

import com.dbf.jdbc.dbf.DBFReader;
import com.dbf.jdbc.dbf.DBFField;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

/**
 * Scans a DBF table and returns rows
 */
public class TableScanOperator extends Operator {
    private final DBFReader reader;
    private final List<DBFField> fields;
    private final String tableName;
    private final String alias;
    private boolean hasMoreRows = true;
    
    public TableScanOperator(DBFReader reader, String tableName, String alias) {
        this.reader = reader;
        this.tableName = tableName;
        this.alias = alias;
        this.fields = reader.getHeader().getFields();
    }
    
    @Override
    public void open() throws SQLException, IOException {
        super.open();
        reader.beforeFirst();
        hasMoreRows = true;
    }
    
    @Override
    public Object[] next() throws SQLException, IOException {
        if (!hasMoreRows) return null;
        
        if (reader.next()) {
            Object[] row = new Object[fields.size()];
            for (int i = 0; i < fields.size(); i++) {
                row[i] = reader.getValue(i);
            }
            incrementRowsProcessed();
            return row;
        }
        
        hasMoreRows = false;
        return null;
    }
    
    @Override
    public void reset() throws SQLException, IOException {
        super.reset();
        reader.beforeFirst();
        hasMoreRows = true;
    }
    
    @Override
    public void close() throws SQLException, IOException {
        super.close();
        reader.close();
    }
    
    @Override
    public String getOperatorName() {
        return "TableScan";
    }
    
    @Override
    public String getOperatorDetails() {
        return "table=" + tableName + (alias != null ? " (as " + alias + ")" : "");
    }
    
    @Override
    public long estimateCost() throws SQLException, IOException {
        return reader.getHeader().getRecordCount();
    }
    
    public DBFReader getReader() {
        return reader;
    }
    
    public List<DBFField> getFields() {
        return fields;
    }
}