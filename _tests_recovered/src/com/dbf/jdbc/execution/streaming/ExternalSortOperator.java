package com.dbf.jdbc.execution.streaming;

import com.dbf.jdbc.execution.buffer.RowBuffer;
import com.dbf.jdbc.parser.ast.OrderByNode;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;

/**
 * External Sort using RowBuffer that automatically spills to disk
 * RowBuffer handles memory management and disk spilling internally
 */
public class ExternalSortOperator implements RowStream {
    private final RowStream input;
    private final int[] sortColumnIndexes;
    private final boolean[] sortAscending;
    private final String[] columnNames;
    private final int[] columnTypes;
    
    private RowBuffer buffer;
    private RowBuffer.RowIterator iterator;
    private boolean sorted = false;
    private boolean closed = false;
    
    private static final long DEFAULT_MEMORY_LIMIT = 50 * 1024 * 1024; // 50MB
    private final long memoryLimit;
    
    public ExternalSortOperator(RowStream input, OrderByNode orderBy, String[] columnNames) {
        this(input, orderBy, columnNames, DEFAULT_MEMORY_LIMIT);
    }
    
    public ExternalSortOperator(RowStream input, OrderByNode orderBy, String[] columnNames, long memoryLimit) {
        this.input = input;
        this.columnNames = columnNames;
        this.columnTypes = input.getColumnTypes();
        this.memoryLimit = memoryLimit;
        
        // Parse sort columns
        List<Integer> sortCols = new ArrayList<>();
        List<Boolean> sortAsc = new ArrayList<>();
        
        if (orderBy != null) {
            for (OrderByNode.OrderItem item : orderBy.getItems()) {
                String colName = item.getColumnName();
                int idx = findColumnIndex(colName);
                if (idx >= 0) {
                    sortCols.add(idx);
                    sortAsc.add(item.isAscending());
                }
            }
        }
        
        // Convert to arrays - Java 8 compatible
        this.sortColumnIndexes = new int[sortCols.size()];
        for (int i = 0; i < sortCols.size(); i++) {
            this.sortColumnIndexes[i] = sortCols.get(i);
        }
        
        this.sortAscending = new boolean[sortAsc.size()];
        for (int i = 0; i < sortAsc.size(); i++) {
            this.sortAscending[i] = sortAsc.get(i);
        }
    }
    
    private int findColumnIndex(String columnName) {
        for (int i = 0; i < columnNames.length; i++) {
            if (columnNames[i].equalsIgnoreCase(columnName)) {
                return i;
            }
        }
        return -1;
    }
    
    @Override
    public Object[] next() throws IOException, SQLException {
        if (!sorted) {
            performExternalSort();
        }
        
        if (iterator == null || !iterator.hasNext()) {
            return null;
        }
        
        return iterator.next();
    }
    
    private void performExternalSort() throws IOException, SQLException {
        // RowBuffer automatically spills to disk when memory limit is exceeded
        buffer = new RowBuffer(memoryLimit, 10000);
        
        // Collect all rows (RowBuffer handles memory by spilling to disk)
        Object[] row;
        while ((row = input.next()) != null) {
            buffer.add(row);
        }
        
        // Collect all rows from buffer (this still loads all into memory for sorting)
        // TODO: For true external sort, we'd need to sort each spilled run individually
        // and then merge them. This is a simplified version.
        List<Object[]> allRows = new ArrayList<>();
        try (RowBuffer.RowIterator it = buffer.iterator()) {
            while (it.hasNext()) {
                allRows.add(it.next());
            }
        }
        
        // Sort all rows
        allRows.sort(new Comparator<Object[]>() {
            @Override
            public int compare(Object[] a, Object[] b) {
                for (int i = 0; i < sortColumnIndexes.length; i++) {
                    int idx = sortColumnIndexes[i];
                    Object valA = (idx < a.length) ? a[idx] : null;
                    Object valB = (idx < b.length) ? b[idx] : null;
                    int cmp = compareValues(valA, valB);
                    if (cmp != 0) {
                        return sortAscending[i] ? cmp : -cmp;
                    }
                }
                return 0;
            }
        });
        
        // Create a new buffer with sorted rows
        RowBuffer sortedBuffer = new RowBuffer(memoryLimit, 10000);
        for (Object[] sortedRow : allRows) {
            sortedBuffer.add(sortedRow);
        }
        
        buffer.close();
        buffer = sortedBuffer;
        iterator = buffer.iterator();
        sorted = true;
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
    public String[] getColumnNames() {
        return columnNames;
    }
    
    @Override
    public int[] getColumnTypes() {
        return columnTypes;
    }
    
    @Override
    public void reset() throws IOException, SQLException {
        if (sorted) {
            sorted = false;
            if (iterator != null) {
                iterator.close();
            }
            if (buffer != null) {
                buffer.close();
            }
        }
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
            if (iterator != null) {
                iterator.close();
            }
            if (buffer != null) {
                buffer.close();
            }
            input.close();
        }
    }
}