package com.dbf.jdbc.execution.streaming;

import com.dbf.jdbc.dbf.DBFField;
import com.dbf.jdbc.parser.ast.ColumnNode;
import com.dbf.jdbc.parser.ast.SelectNode;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;

/**
 * Streaming projection - transforms rows on the fly
 */
public class StreamingProjectionOperator implements RowStream {
    private final RowStream input;
    private final int[] selectedIndexes;
    private final String[] outputColumnNames;
    private final int[] outputColumnTypes;
    private final Map<String, Integer> columnNameToIndex;
    private boolean closed = false;
    
    public StreamingProjectionOperator(RowStream input, List<DBFField> sourceFields, SelectNode selectNode) throws SQLException {
        this.input = input;
        
        List<String> sourceNames = new ArrayList<>();
        for (DBFField field : sourceFields) {
            sourceNames.add(field.getName());
        }
        
        List<Integer> selected = new ArrayList<>();
        List<String> outNames = new ArrayList<>();
        List<String> outLabels = new ArrayList<>();
        columnNameToIndex = new HashMap<>();
        
        if (selectNode == null || (selectNode.getColumns().size() == 1 && selectNode.getColumns().get(0).isStar())) {
            // SELECT * - all columns
            for (int i = 0; i < sourceFields.size(); i++) {
                selected.add(i);
                outNames.add(sourceFields.get(i).getName());
                outLabels.add(sourceFields.get(i).getName());
                columnNameToIndex.put(sourceFields.get(i).getName().toLowerCase(), i);
            }
        } else {
            // SELECT specific columns
            for (ColumnNode col : selectNode.getColumns()) {
                String colName = col.getColumnName();
                int idx = -1;
                for (int i = 0; i < sourceFields.size(); i++) {
                    if (sourceFields.get(i).getName().equalsIgnoreCase(colName)) {
                        idx = i;
                        break;
                    }
                }
                if (idx == -1) {
                    throw new SQLException("Column not found: " + colName);
                }
                selected.add(idx);
                outNames.add(sourceFields.get(idx).getName());
                String label = col.getAlias() != null ? col.getAlias() : sourceFields.get(idx).getName();
                outLabels.add(label);
                columnNameToIndex.put(label.toLowerCase(), selected.size() - 1);
            }
        }
        
        this.selectedIndexes = selected.stream().mapToInt(i -> i).toArray();
        this.outputColumnNames = outLabels.toArray(new String[0]);
        this.outputColumnTypes = new int[selectedIndexes.length];
        for (int i = 0; i < selectedIndexes.length; i++) {
            outputColumnTypes[i] = input.getColumnTypes()[selectedIndexes[i]];
        }
    }
    
    @Override
    public Object[] next() throws IOException, SQLException {
        Object[] row = input.next();
        if (row == null) return null;
        
        Object[] projected = new Object[selectedIndexes.length];
        for (int i = 0; i < selectedIndexes.length; i++) {
            projected[i] = row[selectedIndexes[i]];
        }
        return projected;
    }
    
    @Override
    public String[] getColumnNames() {
        return outputColumnNames;
    }
    
    @Override
    public int[] getColumnTypes() {
        return outputColumnTypes;
    }
    
    public int getColumnIndex(String columnLabel) {
        Integer idx = columnNameToIndex.get(columnLabel.toLowerCase());
        return idx != null ? idx : -1;
    }
    
    @Override
    public void reset() throws IOException, SQLException {
        input.reset();
    }
    
    @Override
    public boolean supportsReset() {
        return input.supportsReset();
    }
    
    @Override
    public long estimateRowCount() {
        return input.estimateRowCount();
    }
    
    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            input.close();
        }
    }
}