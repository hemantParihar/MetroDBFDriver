package com.dbf.jdbc.resultset;

import com.dbf.jdbc.parser.ast.OrderByNode;
import java.util.*;

/**
 * Sort Engine - WARNING: This requires loading all rows into memory
 * For large datasets, use index-based ordering
 */
public class SortEngine {
    private final List<Integer> sortColumnIndexes;
    private final List<Boolean> sortAscending;
    private final RowProjector projector;
    
    public SortEngine(OrderByNode orderBy, RowProjector projector) {
        this.projector = projector;
        this.sortColumnIndexes = new ArrayList<>();
        this.sortAscending = new ArrayList<>();
        
        if (orderBy != null) {
            for (OrderByNode.OrderItem item : orderBy.getItems()) {
                int colIdx = projector.getColumnIndex(item.getColumnName());
                if (colIdx >= 0) {
                    sortColumnIndexes.add(colIdx);
                    sortAscending.add(item.isAscending());
                }
            }
        }
        
        if (hasSort()) {
            System.err.println("WARNING: ORDER BY requires loading all rows into memory. " +
                "For large datasets, consider creating an index on the column(s): " + 
                orderBy != null ? orderBy.getItems() : "unknown");
        }
    }
    
    public boolean hasSort() {
        return !sortColumnIndexes.isEmpty();
    }
    
    public List<Object[]> sort(List<Object[]> rows) {
        if (!hasSort()) {
            return rows;
        }
        
        System.err.println("Sorting " + rows.size() + " rows in memory...");
        long startTime = System.currentTimeMillis();
        
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
        
        long elapsed = System.currentTimeMillis() - startTime;
        System.err.println("Sort completed in " + elapsed + "ms");
        
        return rows;
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
}