package com.dbf.jdbc.execution.streaming;

import com.dbf.jdbc.execution.buffer.RowBuffer;
import com.dbf.jdbc.parser.ast.JoinNode;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;

/**
 * Streaming hash join - builds hash on smaller table, streams larger table
 * NEVER materializes the entire result set
 */
public class StreamingHashJoinOperator implements RowStream {
    private final RowStream leftInput;
    private final RowStream rightInput;
    private final String leftJoinColumn;
    private final String rightJoinColumn;
    private final JoinType joinType;
    private final String[] leftColumnNames;
    private final String[] rightColumnNames;
    private final String[] outputColumnNames;
    private final int[] outputColumnTypes;
    
    private Map<Object, List<Object[]>> hashTable;
    private RowStream probeStream;
    private Object[] currentProbeRow;
    private Iterator<Object[]> currentMatches;
    private boolean leftJoined = false;
    private boolean rightBuildComplete = false;
    private boolean leftBuildMode = true;
    private Set<Object> matchedRightKeys;
    private RowStream leftoverStream;
    
    public enum JoinType {
        INNER, LEFT, RIGHT, FULL
    }
    
    public StreamingHashJoinOperator(RowStream left, RowStream right, JoinNode joinNode) {
        this.leftInput = left;
        this.rightInput = right;
        this.joinType = convertJoinType(joinNode.getJoinType());
        
        // Extract join columns
        if (joinNode.getCondition() != null && joinNode.getCondition().isBinaryOp()) {
            this.leftJoinColumn = joinNode.getCondition().getLeft().getValue();
            this.rightJoinColumn = joinNode.getCondition().getRight().getValue();
        } else {
            this.leftJoinColumn = null;
            this.rightJoinColumn = null;
        }
        
        this.leftColumnNames = left.getColumnNames();
        this.rightColumnNames = right.getColumnNames();
        
        // Build output column names
        List<String> outNames = new ArrayList<>();
        List<Integer> outTypes = new ArrayList<>();
        
        for (String name : leftColumnNames) {
            outNames.add(name);
            outTypes.add(java.sql.Types.VARCHAR);
        }
        for (String name : rightColumnNames) {
            outNames.add(name);
            outTypes.add(java.sql.Types.VARCHAR);
        }
        
        this.outputColumnNames = outNames.toArray(new String[0]);
        this.outputColumnTypes = outTypes.stream().mapToInt(i -> i).toArray();
    }
    
    private JoinType convertJoinType(com.dbf.jdbc.parser.ast.JoinType type) {
        if (type == null) return JoinType.INNER;
        switch (type) {
            case INNER: return JoinType.INNER;
            case LEFT: return JoinType.LEFT;
            case RIGHT: return JoinType.RIGHT;
            case FULL: return JoinType.FULL;
            default: return JoinType.INNER;
        }
    }
    
    @Override
    public Object[] next() throws IOException, SQLException {
        // Lazy initialization: build hash from smaller input
        if (!rightBuildComplete) {
            buildHashTable();
            rightBuildComplete = true;
            
            // Determine probe stream (larger input)
            probeStream = leftInput;
        }
        
        // Process join
        while (true) {
            // If we have matches from current probe row, yield them
            if (currentMatches != null && currentMatches.hasNext()) {
                return combineRows(currentProbeRow, currentMatches.next());
            }
            
            // Get next probe row
            currentProbeRow = probeStream.next();
            if (currentProbeRow == null) {
                // End of probe stream
                if (joinType == JoinType.LEFT && leftBuildMode) {
                    // Switch to process unmatched left rows
                    leftBuildMode = false;
                    probeStream = rightInput;
                    continue;
                }
                return null;
            }
            
            // Find matches in hash table
            Object joinKey = getJoinValue(currentProbeRow, probeStream == leftInput);
            if (joinKey != null && hashTable.containsKey(joinKey)) {
                currentMatches = hashTable.get(joinKey).iterator();
                if (currentMatches.hasNext()) {
                    return combineRows(currentProbeRow, currentMatches.next());
                }
            } else if (joinType == JoinType.LEFT && probeStream == leftInput) {
                // Left join: return left row with nulls on right
                return combineRows(currentProbeRow, createNullRow(rightColumnNames.length));
            }
            // For INNER join, continue to next row
        }
    }
    
    private void buildHashTable() throws IOException, SQLException {
        // Determine smaller input for hash build
        long leftEstimate = leftInput.estimateRowCount();
        long rightEstimate = rightInput.estimateRowCount();
        
        RowStream buildStream;
        boolean buildIsLeft;
        
        if (leftEstimate <= rightEstimate) {
            buildStream = leftInput;
            buildIsLeft = true;
            probeStream = rightInput;
            leftBuildMode = true;
        } else {
            buildStream = rightInput;
            buildIsLeft = false;
            probeStream = leftInput;
            leftBuildMode = false;
        }
        
        hashTable = new HashMap<>();
        
        // Build hash table from smaller input
        Object[] row;
        while ((row = buildStream.next()) != null) {
            Object joinKey = getJoinValue(row, buildIsLeft);
            if (joinKey != null) {
                hashTable.computeIfAbsent(joinKey, k -> new ArrayList<>()).add(row);
            }
        }
        
        // Reset build stream for potential rescan (for RIGHT JOIN)
        if (joinType == JoinType.RIGHT || joinType == JoinType.FULL) {
            buildStream.reset();
        }
    }
    
    private Object getJoinValue(Object[] row, boolean isLeft) {
        String columnName = isLeft ? leftJoinColumn : rightJoinColumn;
        if (columnName == null) return null;
        
        String[] columnNames = isLeft ? leftColumnNames : rightColumnNames;
        for (int i = 0; i < columnNames.length; i++) {
            if (columnNames[i].equalsIgnoreCase(columnName)) {
                return row[i];
            }
        }
        return null;
    }
    
    private Object[] combineRows(Object[] leftRow, Object[] rightRow) {
        Object[] combined = new Object[leftColumnNames.length + rightColumnNames.length];
        System.arraycopy(leftRow, 0, combined, 0, leftColumnNames.length);
        System.arraycopy(rightRow, 0, combined, leftColumnNames.length, rightColumnNames.length);
        return combined;
    }
    
    private Object[] createNullRow(int length) {
        Object[] row = new Object[length];
        Arrays.fill(row, null);
        return row;
    }
    
    @Override
    public String[] getColumnNames() {
        return outputColumnNames;
    }
    
    @Override
    public int[] getColumnTypes() {
        return outputColumnTypes;
    }
    
    @Override
    public void reset() throws IOException, SQLException {
        leftInput.reset();
        rightInput.reset();
        hashTable = null;
        rightBuildComplete = false;
        currentMatches = null;
        currentProbeRow = null;
    }
    
    @Override
    public boolean supportsReset() {
        return false;
    }
    
    @Override
    public long estimateRowCount() {
        return Math.min(leftInput.estimateRowCount(), rightInput.estimateRowCount()) * 10;
    }
    
    @Override
    public void close() throws IOException {
        if (hashTable != null) {
            hashTable.clear();
            hashTable = null;
        }
        leftInput.close();
        rightInput.close();
    }
}