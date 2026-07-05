package com.dbf.jdbc.execution.streaming;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

import com.dbf.jdbc.dbf.DBFField;
import com.dbf.jdbc.dbf.DBFReader;
import com.dbf.jdbc.dbf.DbfType;

/**
 * Scans only a given list of 1-based record numbers (the hits from an index
 * seek), in the order supplied. Column metadata is identical to
 * {@link StreamingTableScanOperator} -- alias-qualified names and the same
 * field list -- so it can be dropped in as the base of the expression pipeline
 * and every downstream operator (WHERE filter, ORDER BY, projection) behaves
 * exactly as it would over a full scan. Deleted records are skipped.
 */
public class RecnoListScanOperator implements RowStream, FieldAwareStream {
    private final DBFReader reader;
    private final List<Long> recnos;
    private final String[] columnNames;
    private final int[] columnTypes;
    private final List<DBFField> sourceFields;
    private int pos = 0;
    private boolean closed = false;

    public RecnoListScanOperator(DBFReader reader, String tableName, String alias,
            List<Long> recnos) {
        this.reader = reader;
        this.recnos = recnos;
        this.sourceFields = reader.getHeader().getFields();

        String qualifier = alias != null ? alias : tableName;
        this.columnNames = new String[sourceFields.size()];
        this.columnTypes = new int[sourceFields.size()];
        for (int i = 0; i < sourceFields.size(); i++) {
            String name = sourceFields.get(i).getName();
            columnNames[i] = qualifier != null ? qualifier.toUpperCase() + "." + name : name;
            columnTypes[i] = DbfType.sqlType(sourceFields.get(i).getType());
        }
    }

    @Override
    public Object[] next() throws IOException, SQLException {
        while (!closed && pos < recnos.size()) {
            long recno = recnos.get(pos++);
            if (!reader.absolute((int) recno)) {
                continue;
            }
            if (reader.isDeleted()) {
                continue;
            }
            Object[] row = new Object[columnNames.length];
            for (int i = 0; i < columnNames.length; i++) {
                row[i] = reader.getValue(i);
            }
            return row;
        }
        return null;
    }

    @Override
    public List<DBFField> getFields() {
        return sourceFields;
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
    public void reset() {
        pos = 0;
    }

    @Override
    public boolean supportsReset() {
        return true;
    }

    @Override
    public long estimateRowCount() {
        return recnos.size();
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            reader.close();
        }
    }
}
