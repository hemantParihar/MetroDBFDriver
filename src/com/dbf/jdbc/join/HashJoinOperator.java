package com.dbf.jdbc.join;

import com.dbf.jdbc.execution.streaming.RowStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;

/**
 * Streaming Hash Join - O(n + m) complexity
 * Builds hash on smaller table, probes with larger
 * Memory: O(min(n, m))
 */
public class HashJoinOperator implements RowStream {
    private final RowStream left;
    private final RowStream right;
    private final String leftKey;
    private final String rightKey;
    private final JoinType type;
    
    private Map<Object, List<Object[]>> hashTable;
    private RowStream probeStream;
    private List<Object[]> currentMatches;
    private Object[] currentProbeRow;
    private int matchIndex = 0;
    private boolean built = false;
    private boolean leftBuild = true;
    
    private final String[] leftColumns;
    private final String[] rightColumns;
    private final String[] outputColumns;
    private final int[] outputTypes;
    
    public enum JoinType { INNER, LEFT, RIGHT, FULL }
    
    public HashJoinOperator(RowStream left, RowStream right, String leftKey, String rightKey, JoinType type) {
        this.left = left;
        this.right = right;
        this.leftKey = leftKey;
        this.rightKey = rightKey;
        this.type = type;
        
        this.leftColumns = left.getColumnNames();
        this.rightColumns = right.getColumnNames();
        
        // Build output schema
        List<String> cols = new ArrayList<>();
        cols.addAll(Arrays.asList(leftColumns));
        cols.addAll(Arrays.asList(rightColumns));
        this.outputColumns = cols.toArray(new String[0]);
        
        this.outputTypes = new int[outputColumns.length];
        Arrays.fill(outputTypes, java.sql.Types.VARCHAR);
    }
    
    @Override
    public Object[] next() throws IOException, SQLException {
        if (!built) {
            buildHashTable();
            built = true;
        }
        
        while (true) {
            // Return current matches if any
            if (currentMatches != null && matchIndex < currentMatches.size()) {
                return combineRows(currentProbeRow, currentMatches.get(matchIndex++));
            }
            currentMatches = null;
            matchIndex = 0;
            
            // Get next probe row
            currentProbeRow = probeStream.next();
            if (currentProbeRow == null) {
                // End of probe stream
                if (type == JoinType.LEFT && leftBuild) {
                    // Switch to RIGHT as probe for unmatched
                    leftBuild = false;
                    probeStream = right;
                    continue;
                }
                return null;
            }
            
            // Find matches
            Object key = getKey(currentProbeRow, leftBuild);
            if (key != null && hashTable.containsKey(key)) {
                currentMatches = hashTable.get(key);
                matchIndex = 0;
                if (currentMatches.size() > 0) {
                    return combineRows(currentProbeRow, currentMatches.get(matchIndex++));
                }
            } else if (type == JoinType.LEFT && leftBuild) {
                // Left join - return with nulls on right
                return combineRows(currentProbeRow, null);
            }
        }
    }
    
    private void buildHashTable() throws IOException, SQLException {
        // Determine smaller side for hash build
        long leftEstimate = left.estimateRowCount();
        long rightEstimate = right.estimateRowCount();
        
        RowStream buildStream;
        boolean buildIsLeft;
        
        if (leftEstimate <= rightEstimate) {
            buildStream = left;
            probeStream = right;
            leftBuild = true;
            buildIsLeft = true;
        } else {
            buildStream = right;
            probeStream = left;
            leftBuild = false;
            buildIsLeft = false;
        }
        
        hashTable = new HashMap<>();
        
        Object[] row;
        while ((row = buildStream.next()) != null) {
            Object key = getKey(row, buildIsLeft);
            if (key != null) {
                hashTable.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            }
        }
        
        // Reset build stream if needed for RIGHT/FULL joins
        if (type == JoinType.RIGHT || type == JoinType.FULL) {
            buildStream.reset();
        }
    }
    
    private Object getKey(Object[] row, boolean isLeft) {
        String[] cols = isLeft ? leftColumns : rightColumns;
        String keyName = isLeft ? leftKey : rightKey;
        for (int i = 0; i < cols.length; i++) {
            if (cols[i].equalsIgnoreCase(keyName)) {
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
    @Override public void reset() throws IOException, SQLException { built = false; hashTable = null; left.reset(); right.reset(); }
    @Override public boolean supportsReset() { return false; }
    @Override public long estimateRowCount() { return Math.min(left.estimateRowCount(), right.estimateRowCount()) * 10; }
    @Override public void close() throws IOException { left.close(); right.close(); if (hashTable != null) hashTable.clear(); }
}