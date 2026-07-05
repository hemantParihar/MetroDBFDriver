package com.dbf.jdbc.resultset;

import com.dbf.jdbc.dbf.DBFField;
import com.dbf.jdbc.parser.ast.ColumnNode;
import com.dbf.jdbc.parser.ast.SelectNode;
import java.sql.SQLException;
import java.util.*;

/**
 * Handles column selection and projection from DBF records
 * Supports real columns and pseudo-columns like RECNO()
 */
public class RowProjector {
    private final List<DBFField> allFields;
    private final List<Integer> selectedColumnIndexes;
    private final List<String> columnNames;
    private final List<String> columnLabels;
    private final Map<String, Integer> columnNameToIndex;
    private final boolean hasRecnoColumn;
    private int recnoColumnIndex = -1;
    
    // Special column markers
    private static final int RECNO_MARKER = -1;
    
    public RowProjector(List<DBFField> fields, SelectNode selectNode) throws SQLException {
        this.allFields = fields;
        this.selectedColumnIndexes = new ArrayList<>();
        this.columnNames = new ArrayList<>();
        this.columnLabels = new ArrayList<>();
        this.columnNameToIndex = new HashMap<>();
        
        boolean hasRecno = false;
        
        if (selectNode == null) {
            // Default: select all columns (no RECNO)
            for (int i = 0; i < fields.size(); i++) {
                selectedColumnIndexes.add(i);
                columnNames.add(fields.get(i).getName());
                columnLabels.add(fields.get(i).getName());
                columnNameToIndex.put(fields.get(i).getName().toLowerCase(), i);
            }
            hasRecno = false;
            
        } else if (selectNode.getColumns().size() == 1 && selectNode.getColumns().get(0).isStar()) {
            // SELECT * - all real columns (no RECNO)
            for (int i = 0; i < fields.size(); i++) {
                selectedColumnIndexes.add(i);
                columnNames.add(fields.get(i).getName());
                columnLabels.add(fields.get(i).getName());
                columnNameToIndex.put(fields.get(i).getName().toLowerCase(), i);
            }
            hasRecno = false;
            
        } else {
            // SELECT specific columns
            int columnPosition = 0;
            for (ColumnNode col : selectNode.getColumns()) {
                String colName = col.getColumnName();
                
                // Check for RECNO() pseudo-column
                if (colName != null && ("RECNO".equalsIgnoreCase(colName) || "RECNO()".equalsIgnoreCase(colName))) {
                    // RECNO pseudo-column
                    selectedColumnIndexes.add(RECNO_MARKER);
                    columnNames.add("RECNO");
                    String label = col.getAlias() != null ? col.getAlias() : "RECNO";
                    columnLabels.add(label);
                    columnNameToIndex.put(label.toLowerCase(), columnPosition);
                    hasRecno = true;
                    recnoColumnIndex = columnPosition;
                    columnPosition++;
                    
                } else {
                    // Regular column
                    int idx = findColumnIndex(colName);
                    if (idx == -1) {
                        throw new SQLException("Column not found: " + colName);
                    }
                    selectedColumnIndexes.add(idx);
                    columnNames.add(fields.get(idx).getName());
                    String label = col.getAlias() != null ? col.getAlias() : fields.get(idx).getName();
                    columnLabels.add(label);
                    columnNameToIndex.put(label.toLowerCase(), columnPosition);
                    columnPosition++;
                }
            }
        }
        
        this.hasRecnoColumn = hasRecno;
    }
    
    private int findColumnIndex(String columnName) {
        for (int i = 0; i < allFields.size(); i++) {
            if (allFields.get(i).getName().equalsIgnoreCase(columnName)) {
                return i;
            }
        }
        return -1;
    }
    
    /**
     * Project a full row to selected columns
     * RECNO() columns get null placeholder - actual value filled by ResultSet
     */
    public Object[] projectRow(Object[] fullRow) {
        return projectRow(fullRow, -1);
    }
    
    /**
     * Project a full row to selected columns with RECNO value
     * @param fullRow The complete row data
     * @param recnoValue The current record number (1-based), ignored if no RECNO column
     */
    public Object[] projectRow(Object[] fullRow, int recnoValue) {
        Object[] projected = new Object[selectedColumnIndexes.size()];
        
        for (int i = 0; i < selectedColumnIndexes.size(); i++) {
            int idx = selectedColumnIndexes.get(i);
            
            if (idx == RECNO_MARKER) {
                // RECNO() pseudo-column - use provided recnoValue
                projected[i] = recnoValue > 0 ? recnoValue : null;
            } else if (idx < fullRow.length) {
                projected[i] = fullRow[idx];
            } else {
                projected[i] = null;
            }
        }
        
        return projected;
    }
    
    /**
     * Project a full row without RECNO (for backward compatibility)
     */
    public Object[] projectRowWithoutRecno(Object[] fullRow) {
        return projectRow(fullRow, -1);
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
    
    public String getColumnName(int columnIndex) {
        if (columnIndex < 1 || columnIndex > columnNames.size()) {
            return null;
        }
        return columnNames.get(columnIndex - 1);
    }
    
    public List<Integer> getSelectedColumnIndexes() {
        return Collections.unmodifiableList(selectedColumnIndexes);
    }
    
    public boolean hasRecnoColumn() {
        return hasRecnoColumn;
    }
    
    public int getRecnoColumnIndex() {
        return recnoColumnIndex;
    }
    
    /**
     * Get the position of RECNO column (0-based) or -1 if not present
     */
    public int getRecnoPosition() {
        return recnoColumnIndex;
    }
    
    /**
     * Check if a specific column index is the RECNO pseudo-column
     */
    public boolean isRecnoColumn(int columnIndex) {
        if (columnIndex < 1 || columnIndex > selectedColumnIndexes.size()) {
            return false;
        }
        int idx = selectedColumnIndexes.get(columnIndex - 1);
        return idx == RECNO_MARKER;
    }
    
    /**
     * Get the original DBF field index for a projected column
     * Returns -1 for RECNO pseudo-column
     */
    public int getOriginalFieldIndex(int projectedColumnIndex) {
        if (projectedColumnIndex < 1 || projectedColumnIndex > selectedColumnIndexes.size()) {
            return -1;
        }
        int idx = selectedColumnIndexes.get(projectedColumnIndex - 1);
        return idx == RECNO_MARKER ? -1 : idx;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("RowProjector{\n");
        for (int i = 0; i < selectedColumnIndexes.size(); i++) {
            int idx = selectedColumnIndexes.get(i);
            sb.append("  ").append(i).append(": ");
            if (idx == RECNO_MARKER) {
                sb.append("RECNO()");
            } else {
                sb.append(allFields.get(idx).getName());
            }
            sb.append(" -> ").append(columnLabels.get(i));
            sb.append("\n");
        }
        sb.append("}");
        return sb.toString();
    }
}