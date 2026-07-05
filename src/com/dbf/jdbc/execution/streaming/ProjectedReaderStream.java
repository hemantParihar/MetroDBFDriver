package com.dbf.jdbc.execution.streaming;

import com.dbf.jdbc.dbf.DBFField;
import com.dbf.jdbc.dbf.DBFReader;
import com.dbf.jdbc.parser.ast.SelectNode;
import com.dbf.jdbc.resultset.FilterEngine;
import com.dbf.jdbc.resultset.RowProjector;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/**
 * Streams projected (and WHERE-filtered) rows from a DBFReader one at a
 * time. Deleted records are skipped and RECNO() values are filled in.
 * Memory: O(1) rows.
 */
public class ProjectedReaderStream implements RowStream, FieldAwareStream {
    private final DBFReader reader;
    private final RowProjector projector;
    private final FilterEngine filter;
    private final String[] columnNames;
    private final int[] columnTypes;
    private final List<DBFField> projectedFields;
    // Column pruning: decode only the output + WHERE columns; null = decode all.
    private final boolean[] neededColumns;

    public ProjectedReaderStream(DBFReader reader, SelectNode selectNode) throws SQLException {
        this.reader = reader;
        List<DBFField> fields = reader.getHeader().getFields();
        this.projector = new RowProjector(fields, selectNode);
        this.filter = (selectNode != null && selectNode.getWhere() != null)
            ? new FilterEngine(selectNode.getWhere().getCondition(), fields)
            : null;
        this.neededColumns = computeNeeded(fields, selectNode, projector);

        List<String> labels = projector.getColumnLabels();
        this.columnNames = labels.toArray(new String[0]);
        this.columnTypes = new int[labels.size()];
        for (int i = 0; i < columnTypes.length; i++) {
            columnTypes[i] = Types.OTHER;
        }

        // One DBFField per output column so metadata keeps real types.
        // RECNO (marker index -1) gets a synthetic integer field.
        this.projectedFields = new java.util.ArrayList<>();
        List<Integer> selected = projector.getSelectedColumnIndexes();
        for (int i = 0; i < selected.size(); i++) {
            int idx = selected.get(i);
            if (idx >= 0 && idx < fields.size()) {
                projectedFields.add(fields.get(idx));
            } else {
                DBFField recno = new DBFField();
                recno.setName("RECNO");
                recno.setType('I');
                recno.setLength(10);
                projectedFields.add(recno);
            }
        }
        reader.beforeFirst();
    }

    @Override
    public List<DBFField> getFields() {
        return projectedFields;
    }

    @Override
    public Object[] next() throws IOException, SQLException {
        while (reader.next()) {
            if (reader.isDeleted()) continue;

            Object[] fullRow;
            if (neededColumns != null) {
                fullRow = reader.getCurrentRowPruned(neededColumns);
            } else {
                List<DBFField> fields = reader.getHeader().getFields();
                fullRow = new Object[fields.size()];
                for (int i = 0; i < fields.size(); i++) {
                    fullRow[i] = reader.getValue(i);
                }
            }

            int recno = reader.getCurrentRecord() + 1;
            if (filter != null && !filter.matches(fullRow, recno)) continue;

            return projector.projectRow(fullRow, recno);
        }
        return null;
    }

    /**
     * Builds the decode mask: the projected output columns plus any column the
     * WHERE references. Returns null (decode everything) for {@code SELECT *} or
     * if a WHERE column can't be resolved -- so pruning never drops a needed value.
     * Public so DBFResultSet's plain reader path can prune with the same rules.
     */
    public static boolean[] computeNeeded(List<DBFField> fields, SelectNode selectNode,
            RowProjector projector) {
        if (selectNode == null) {
            return null;
        }
        for (com.dbf.jdbc.parser.ast.ColumnNode col : selectNode.getColumns()) {
            if (col.isStar()) {
                return null; // SELECT * needs every column
            }
        }
        boolean[] needed = new boolean[fields.size()];
        // Output columns (already resolved to absolute indexes by the projector).
        for (int idx : projector.getSelectedColumnIndexes()) {
            if (idx >= 0 && idx < needed.length) {
                needed[idx] = true;
            }
        }
        // WHERE columns.
        if (selectNode.getWhere() != null) {
            java.util.Set<String> names = new java.util.HashSet<>();
            collectColumnNames(selectNode.getWhere().getCondition(), names);
            for (String n : names) {
                int idx = indexOfField(fields, n);
                if (idx < 0) {
                    return null; // unknown WHERE column -> be safe, decode all
                }
                needed[idx] = true;
            }
        }
        return needed;
    }

    private static int indexOfField(List<DBFField> fields, String name) {
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).getName().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    /** Collects column names referenced in an expression tree (excludes RECNO). */
    private static void collectColumnNames(com.dbf.jdbc.parser.ast.ExpressionNode n,
            java.util.Set<String> out) {
        if (n == null) {
            return;
        }
        if (n.isColumn()) {
            if (!n.isRecno() && n.getColumnName() != null) {
                out.add(n.getColumnName().toUpperCase());
            }
            return;
        }
        if (n.isAggregate()) {
            collectColumnNames(n.getAggregateArg(), out);
        }
        collectColumnNames(n.getLeft(), out);
        collectColumnNames(n.getRight(), out);
        if (n.getArguments() != null) {
            for (com.dbf.jdbc.parser.ast.ExpressionNode a : n.getArguments()) {
                collectColumnNames(a, out);
            }
        }
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
        reader.beforeFirst();
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
        reader.close();
    }
}
