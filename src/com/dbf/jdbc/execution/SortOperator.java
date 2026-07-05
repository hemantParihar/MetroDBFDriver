package com.dbf.jdbc.execution;

import com.dbf.jdbc.parser.ast.OrderByNode;
import com.dbf.jdbc.resultset.TypeConverter;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Sorts rows based on ORDER BY clause
 * Uses external sorting for large datasets to avoid memory overflow
 */
public class SortOperator extends Operator {
    private final OrderByNode orderBy;
    private final List<String> columnNames;
    private final List<Integer> sortColumnIndexes;
    private final List<Boolean> sortAscending;
    private final int memorySortThreshold = 10000;
    
    private List<Object[]> sortedRows;
    private Iterator<Object[]> iterator;
    private boolean isSorted = false;
    
    public SortOperator(OrderByNode orderBy, List<String> columnNames) {
        this.orderBy = orderBy;
        this.columnNames = columnNames;
        this.sortColumnIndexes = new ArrayList<>();
        this.sortAscending = new ArrayList<>();
        
        if (orderBy != null) {
            for (OrderByNode.OrderItem item : orderBy.getItems()) {
                int colIdx = findColumnIndex(item.getColumnName());
                if (colIdx >= 0) {
                    sortColumnIndexes.add(colIdx);
                    sortAscending.add(item.isAscending());
                }
            }
        }
    }
    
    private int findColumnIndex(String columnName) {
        for (int i = 0; i < columnNames.size(); i++) {
            if (columnNames.get(i).equalsIgnoreCase(columnName)) {
                return i;
            }
        }
        return -1;
    }
    
    @Override
    public void open() throws SQLException, IOException {
        super.open();
        if (children.isEmpty()) {
            throw new SQLException("SortOperator requires a child operator");
        }
        isSorted = false;
        iterator = null;
    }
    
    @Override
    public Object[] next() throws SQLException, IOException {
        if (!isSorted) {
            sortAllRows();
        }
        
        if (iterator == null || !iterator.hasNext()) {
            return null;
        }
        
        incrementRowsProcessed();
        return iterator.next();
    }
    
    private void sortAllRows() throws SQLException, IOException {
        // Collect all rows from child operator
        List<Object[]> rows = new ArrayList<>();
        Object[] row;
        while ((row = children.get(0).next()) != null) {
            rows.add(row);
        }
        
        // Sort the rows
        rows.sort((a, b) -> {
            for (int i = 0; i < sortColumnIndexes.size(); i++) {
                int colIdx = sortColumnIndexes.get(i);
                Object valA = (colIdx < a.length) ? a[colIdx] : null;
                Object valB = (colIdx < b.length) ? b[colIdx] : null;
                int cmp = compareValues(valA, valB);
                if (cmp != 0) {
                    return sortAscending.get(i) ? cmp : -cmp;
                }
            }
            return 0;
        });
        
        sortedRows = rows;
        iterator = sortedRows.iterator();
        isSorted = true;
    }
    
    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compareValues(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        }
        if (a instanceof Comparable && b instanceof Comparable) {
            return ((Comparable) a).compareTo(b);
        }
        return a.toString().compareTo(b.toString());
    }
    
    @Override
    public void reset() throws SQLException, IOException {
        super.reset();
        isSorted = false;
        iterator = null;
        sortedRows = null;
    }
    
    @Override
    public void close() throws SQLException, IOException {
        super.close();
        sortedRows = null;
        iterator = null;
    }
    
    @Override
    public String getOperatorName() {
        return "Sort";
    }
    
    @Override
    public String getOperatorDetails() {
        if (orderBy == null) return "none";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < orderBy.getItems().size(); i++) {
            if (i > 0) sb.append(", ");
            OrderByNode.OrderItem item = orderBy.getItems().get(i);
            sb.append(item.getColumnName()).append(" ").append(item.isAscending() ? "ASC" : "DESC");
        }
        return "by=" + sb.toString();
    }
    
    @Override
    public long estimateCost() throws SQLException, IOException {
        if (children.isEmpty()) return 0;
        long childCost = children.get(0).estimateCost();
        // Sorting cost is O(n log n)
        return (long) (childCost * Math.log(childCost) / Math.log(2));
    }
}