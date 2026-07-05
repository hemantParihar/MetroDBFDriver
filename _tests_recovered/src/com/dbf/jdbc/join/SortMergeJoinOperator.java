package com.dbf.jdbc.join;

import com.dbf.jdbc.execution.streaming.RowStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;

/**
 * Sort Merge Join - O(n log n + m log m) 
 * Best for large sorted or sortable datasets
 * Memory: O(1) - streaming
 */
public class SortMergeJoinOperator implements RowStream {
    private final RowStream left;
    private final RowStream right;
    private final String leftKey;
    private final String rightKey;
    private final JoinType type;
    
    private Object[] leftRow;
    private Object[] rightRow;
    private List<Object[]> leftMatches;
    private int leftMatchIndex = 0;
    private boolean leftExhausted = false;
    private boolean rightExhausted = false;
    private boolean initialized = false;
    
    private final String[] leftColumns;
    private final String[] rightColumns;
    private final String[] outputColumns;
    private final int[] outputTypes;
    
    public enum JoinType { INNER, LEFT, RIGHT, FULL }
    
    public SortMergeJoinOperator(RowStream left, RowStream right, String leftKey, String rightKey, JoinType type) {
        this.left = left;
        this.right = right;
        this.leftKey = leftKey;
        this.rightKey = rightKey;
        this.type = type;
        
        this.leftColumns = left.getColumnNames();
        this.rightColumns = right.getColumnNames();
        
        List<String> cols = new ArrayList<>();
        cols.addAll(Arrays.asList(leftColumns));
        cols.addAll(Arrays.asList(rightColumns));
        this.outputColumns = cols.toArray(new String[0]);
        this.outputTypes = new int[outputColumns.length];
        Arrays.fill(outputTypes, java.sql.Types.VARCHAR);
    }
    
    @Override
    public Object[] next() throws IOException, SQLException {
        if (!initialized) {
            initialize();
            initialized = true;
        }
        
        while (true) {
            // Return pending matches from current left row
            if (leftMatches != null && leftMatchIndex < leftMatches.size()) {
                return combineRows(leftRow, leftMatches.get(leftMatchIndex++));
            }
            leftMatches = null;
            leftMatchIndex = 0;
            
            if (leftExhausted) {
                if (type == JoinType.RIGHT || type == JoinType.FULL) {
                    // Return remaining right rows
                    if (rightRow != null) {
                        Object[] result = combineRows(null, rightRow);
                        rightRow = right.next();
                        if (rightRow == null) rightExhausted = true;
                        return result;
                    }
                }
                return null;
            }
            
            // Advance left row
            if (leftRow == null || (leftMatches == null && leftMatchIndex == 0)) {
                leftRow = left.next();
                if (leftRow == null) {
                    leftExhausted = true;
                    if (type == JoinType.RIGHT || type == JoinType.FULL) {
                        continue;
                    }
                    return null;
                }
            }
            
            // Advance right row to match or exceed left key
            Object leftKeyVal = getKey(leftRow, leftColumns, leftKey);
            while (rightRow != null && compareKeys(leftKeyVal, getKey(rightRow, rightColumns, rightKey)) > 0) {
                rightRow = right.next();
                if (rightRow == null) rightExhausted = true;
            }
            
            if (rightExhausted) {
                if (type == JoinType.LEFT || type == JoinType.FULL) {
                    // Return left row with nulls
                    Object[] result = combineRows(leftRow, null);
                    leftRow = null;
                    return result;
                }
                leftExhausted = true;
                continue;
            }
            
            // Collect all matching right rows
            if (rightRow != null && compareKeys(leftKeyVal, getKey(rightRow, rightColumns, rightKey)) == 0) {
                leftMatches = new ArrayList<>();
                while (rightRow != null && compareKeys(leftKeyVal, getKey(rightRow, rightColumns, rightKey)) == 0) {
                    leftMatches.add(rightRow);
                    rightRow = right.next();
                }
                if (leftMatches.size() > 0) {
                    leftMatchIndex = 0;
                    return combineRows(leftRow, leftMatches.get(leftMatchIndex++));
                }
                leftRow = null;
            } else {
                // No match - handle based on join type
                if (type == JoinType.LEFT || type == JoinType.FULL) {
                    Object[] result = combineRows(leftRow, null);
                    leftRow = null;
                    return result;
                }
                leftRow = null;
            }
        }
    }
    
    private void initialize() throws IOException, SQLException {
        leftRow = left.next();
        rightRow = right.next();
        if (leftRow == null) leftExhausted = true;
        if (rightRow == null) rightExhausted = true;
    }
    
    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compareKeys(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a instanceof Comparable && b instanceof Comparable) {
            return ((Comparable) a).compareTo(b);
        }
        return a.toString().compareTo(b.toString());
    }
    
    private Object getKey(Object[] row, String[] columns, String keyName) {
        for (int i = 0; i < columns.length; i++) {
            if (columns[i].equalsIgnoreCase(keyName)) {
                return row[i];
            }
        }
        return null;
    }
    
    private Object[] combineRows(Object[] leftRow, Object[] rightRow) {
        Object[] result = new Object[leftColumns.length + rightColumns.length];
        if (leftRow != null) {
            System.arraycopy(leftRow, 0, result, 0, leftColumns.length);
        }
        if (rightRow != null) {
            System.arraycopy(rightRow, 0, result, leftColumns.length, rightColumns.length);
        }
        return result;
    }
    
    @Override public String[] getColumnNames() { return outputColumns; }
    @Override public int[] getColumnTypes() { return outputTypes; }
    @Override public void reset() throws IOException, SQLException { 
        initialized = false; 
        left.reset(); 
        right.reset(); 
        leftMatches = null;
        leftRow = null;
        rightRow = null;
        leftExhausted = false;
        rightExhausted = false;
    }
    @Override public boolean supportsReset() { return left.supportsReset() && right.supportsReset(); }
    @Override public long estimateRowCount() { return left.estimateRowCount() + right.estimateRowCount(); }
    @Override public void close() throws IOException { left.close(); right.close(); }
}