package com.dbf.jdbc.execution;

import com.dbf.jdbc.dbf.DBFField;
import com.dbf.jdbc.parser.ast.ColumnNode;
import com.dbf.jdbc.parser.ast.SelectNode;
import com.dbf.jdbc.resultset.TypeConverter;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;

/**
 * Projects only selected columns from input rows
 */
public class ProjectionOperator extends Operator {
    private final List<Integer> selectedColumnIndexes;
    private final List<String> columnNames;
    private final List<String> columnLabels;
    private final Map<String, Integer> columnNameToIndex;
    private final List<String> sourceColumnNames;
    
    public ProjectionOperator(List<DBFField> sourceFields, SelectNode selectNode) throws SQLException {
        this.sourceColumnNames = new ArrayList<>();
        for (DBFField field : sourceFields) {
            sourceColumnNames.add(field.getName());
        }
        
        this.selectedColumnIndexes = new ArrayList<>();
        this.columnNames = new ArrayList<>();
        this.columnLabels = new ArrayList<>();
        this.columnNameToIndex = new HashMap<>();
        
        if (selectNode == null) {
            // Select all columns
            for (int i = 0; i < sourceFields.size(); i++) {
                selectedColumnIndexes.add(i);
                columnNames.add(sourceFields.get(i).getName());
                columnLabels.add(sourceFields.get(i).getName());
                columnNameToIndex.put(sourceFields.get(i).getName().toLowerCase(), i);
            }
        } else if (selectNode.getColumns().size() == 1 && selectNode.getColumns().get(0).isStar()) {
            // SELECT *
            for (int i = 0; i < sourceFields.size(); i++) {
                selectedColumnIndexes.add(i);
                columnNames.add(sourceFields.get(i).getName());
                columnLabels.add(sourceFields.get(i).getName());
                columnNameToIndex.put(sourceFields.get(i).getName().toLowerCase(), i);
            }
        } else {
            // Select specific columns
            for (ColumnNode col : selectNode.getColumns()) {
                String colName = col.getColumnName();
                int idx = findColumnIndex(colName, sourceFields);
                if (idx == -1) {
                    throw new SQLException("Column not found: " + colName);
                }
                selectedColumnIndexes.add(idx);
                columnNames.add(sourceFields.get(idx).getName());
                String label = col.getAlias() != null ? col.getAlias() : sourceFields.get(idx).getName();
                columnLabels.add(label);
                columnNameToIndex.put(label.toLowerCase(), selectedColumnIndexes.size() - 1);
            }
        }
    }
    
    private int findColumnIndex(String columnName, List<DBFField> fields) {
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).getName().equalsIgnoreCase(columnName)) {
                return i;
            }
        }
        return -1;
    }
    
    @Override
    public void open() throws SQLException, IOException {
        super.open();
        if (children.isEmpty()) {
            throw new SQLException("ProjectionOperator requires a child operator");
        }
    }
    
    @Override
    public Object[] next() throws SQLException, IOException {
        Object[] row = children.get(0).next();
        if (row == null) return null;
        
        Object[] projected = new Object[selectedColumnIndexes.size()];
        for (int i = 0; i < selectedColumnIndexes.size(); i++) {
            int idx = selectedColumnIndexes.get(i);
            projected[i] = (idx < row.length) ? row[idx] : null;
        }
        
        incrementRowsProcessed();
        return projected;
    }
    
    @Override
    public String getOperatorName() {
        return "Projection";
    }
    
    @Override
    public String getOperatorDetails() {
        return "columns=" + columnLabels;
    }
    
    @Override
    public long estimateCost() throws SQLException, IOException {
        if (children.isEmpty()) return 0;
        return children.get(0).estimateCost();
    }
    
    public int getColumnCount() {
        return selectedColumnIndexes.size();
    }
    
    public List<String> getColumnNames() {
        return Collections.unmodifiableList(columnNames);
    }
    
    public List<String> getColumnLabels() {
        return Collections.unmodifiableList(columnLabels);
    }
    
    public int getColumnIndex(String columnLabel) {
        Integer idx = columnNameToIndex.get(columnLabel.toLowerCase());
        return idx != null ? idx : -1;
    }
    
    public String getColumnLabel(int columnIndex) {
        if (columnIndex < 1 || columnIndex > columnLabels.size()) {
            return null;
        }
        return columnLabels.get(columnIndex - 1);
    }
}