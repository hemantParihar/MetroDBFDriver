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

    private final List<DBFField> sourceFields;
    // When non-null, only these field positions are decoded (column pruning);
    // the rest are left null. Row width/indexes are unchanged.
    private final boolean[] neededColumns;

    public StreamingTableScanOperator(DBFReader reader, String tableName, String alias) {
        this(reader, tableName, alias, null);
    }

    /**
     * @param neededNames upper-cased set of column names to decode (column
     *        pruning); null decodes every column. Names not present in this
     *        table are simply ignored.
     */
    public StreamingTableScanOperator(DBFReader reader, String tableName, String alias,
            java.util.Set<String> neededNames) {
        this.reader = reader;
        this.tableName = tableName;
        this.alias = alias;

        // Build column metadata. When a qualifier (alias, else table name) is
        // available, emit names as "QUALIFIER.COL" so multi-table joins and
        // self-joins can disambiguate columns; otherwise emit plain names.
        String qualifier = alias != null ? alias : tableName;
        this.sourceFields = reader.getHeader().getFields();
        this.columnNames = new String[sourceFields.size()];
        this.columnTypes = new int[sourceFields.size()];
        boolean[] needed = null;
        if (neededNames != null) {
            needed = new boolean[sourceFields.size()];
        }
        for (int i = 0; i < sourceFields.size(); i++) {
            String name = sourceFields.get(i).getName();
            columnNames[i] = qualifier != null ? qualifier.toUpperCase() + "." + name : name;
            columnTypes[i] = mapFieldTypeToSqlType(sourceFields.get(i).getType());
            if (needed != null) {
                needed[i] = neededNames.contains(name.toUpperCase());
            }
        }
        this.neededColumns = needed;
    }

    /** Source DBF field definitions, in column order (for type metadata). */
    public List<DBFField> getSourceFields() {
        return sourceFields;
    }
    
    private int mapFieldTypeToSqlType(char dbfType) {
        // Single source of truth: see com.dbf.jdbc.dbf.DbfType
        return com.dbf.jdbc.dbf.DbfType.sqlType(dbfType);
    }
    
    @Override
    public Object[] next() throws IOException, SQLException {
        if (!hasNextRow || closed) return null;

        while (reader.next()) {
            if (reader.isDeleted()) continue;

            if (neededColumns != null) {
                return reader.getCurrentRowPruned(neededColumns);
            }
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