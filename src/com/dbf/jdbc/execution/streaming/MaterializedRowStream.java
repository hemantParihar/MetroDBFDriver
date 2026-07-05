package com.dbf.jdbc.execution.streaming;

import com.dbf.jdbc.dbf.DBFField;
import java.io.IOException;
import java.sql.Types;
import java.util.List;

/**
 * RowStream over an already-materialized list of rows. Used for aggregate
 * results and generated keys, which are small. When constructed with field
 * definitions it is {@link FieldAwareStream}, so ResultSetMetaData reports
 * real JDBC types instead of OTHER.
 */
public class MaterializedRowStream implements RowStream, FieldAwareStream {
    private final List<Object[]> rows;
    private final String[] columnNames;
    private final int[] columnTypes;
    private final List<DBFField> fields; // null when types are unknown
    private int index = 0;

    public MaterializedRowStream(List<Object[]> rows, String[] columnNames) {
        this(rows, columnNames, null);
    }

    /**
     * @param fields one DBFField per output column (for accurate metadata),
     *               or null to report OTHER
     */
    public MaterializedRowStream(List<Object[]> rows, String[] columnNames,
            List<DBFField> fields) {
        this.rows = rows;
        this.columnNames = columnNames;
        this.fields = (fields != null && fields.size() == columnNames.length) ? fields : null;
        this.columnTypes = new int[columnNames.length];
        for (int i = 0; i < columnTypes.length; i++) {
            columnTypes[i] = this.fields != null ? sqlType(this.fields.get(i).getType()) : Types.OTHER;
        }
    }

    @Override
    public Object[] next() {
        if (index >= rows.size()) return null;
        return rows.get(index++);
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
    public List<DBFField> getFields() {
        return fields;
    }

    @Override
    public void reset() {
        index = 0;
    }

    @Override
    public long estimateRowCount() {
        return rows.size();
    }

    @Override
    public boolean supportsReset() {
        return true;
    }

    @Override
    public void close() throws IOException {
        // Nothing to release
    }

    private static int sqlType(char dbfType) {
        // Single source of truth: see com.dbf.jdbc.dbf.DbfType
        return com.dbf.jdbc.dbf.DbfType.sqlType(dbfType);
    }
}
