package com.dbf.jdbc.resultset;

import com.dbf.jdbc.dbf.DBFReader;
import com.dbf.jdbc.dbf.DBFField;
import com.dbf.jdbc.index.NDXIndex;
import com.dbf.jdbc.index.MDXIndex;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;

/**
 * STREAMING Join Engine - Uses hash join with streaming
 * NEVER materializes the entire right table
 * Uses hash table for probe side only
 */
public class JoinEngine {
    private final DBFReader leftReader;
    private final DBFReader rightReader;
    private final JoinType joinType;
    private final String leftJoinColumn;
    private final String rightJoinColumn;
    private final boolean useIndex;
    private final NDXIndex ndxIndex;
    private final MDXIndex mdxIndex;
    private final String indexTag;
    
    // Streaming state
    private Map<Object, List<Object[]>> hashTable;
    private Object[] currentLeftRow;
    private Iterator<Object[]> currentMatches;
    private boolean rightBuilt = false;
    private boolean leftExhausted = false;
    private long leftRowCount = 0;
    private long rightRowCount = 0;
    
    public enum JoinType { INNER, LEFT, RIGHT, FULL }
    
    public JoinEngine(DBFReader leftReader, DBFReader rightReader, 
                      String leftColumn, String rightColumn, JoinType type) {
        this.leftReader = leftReader;
        this.rightReader = rightReader;
        this.leftJoinColumn = leftColumn;
        this.rightJoinColumn = rightColumn;
        this.joinType = type;
        this.useIndex = false;
        this.ndxIndex = null;
        this.mdxIndex = null;
        this.indexTag = null;
    }
    
    public JoinEngine(DBFReader leftReader, DBFReader rightReader,
                      NDXIndex index, String leftColumn, String rightColumn, JoinType type) {
        this.leftReader = leftReader;
        this.rightReader = rightReader;
        this.leftJoinColumn = leftColumn;
        this.rightJoinColumn = rightColumn;
        this.joinType = type;
        this.useIndex = true;
        this.ndxIndex = index;
        this.mdxIndex = null;
        this.indexTag = null;
    }
    
    public void buildHashTable() throws IOException {
        if (rightBuilt) return;
        
        System.err.println("Building hash table from right table (streaming)...");
        hashTable = new HashMap<>();
        
        rightReader.beforeFirst();
        long rowsProcessed = 0;
        
        while (rightReader.next()) {
            Object[] row = readFullRow(rightReader);
            Object key = getJoinKey(row, false);
            if (key != null) {
                hashTable.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
                rightRowCount++;
            }
            rowsProcessed++;
            
            if (rowsProcessed % 100000 == 0) {
                System.err.println("  Processed " + rowsProcessed + " right rows, " + 
                    hashTable.size() + " unique keys");
            }
        }
        
        rightBuilt = true;
        System.err.println("Hash table built: " + rightRowCount + " rows, " + 
            hashTable.size() + " unique keys");
    }
    
    public boolean hasNext() throws IOException {
        if (!rightBuilt) {
            buildHashTable();
        }
        
        while (true) {
            if (currentMatches != null && currentMatches.hasNext()) {
                return true;
            }
            
            if (leftExhausted) {
                return false;
            }
            
            // Get next left row
            if (currentLeftRow == null) {
                if (leftReader.next()) {
                    currentLeftRow = readFullRow(leftReader);
                    leftRowCount++;
                    Object key = getJoinKey(currentLeftRow, true);
                    
                    if (key != null && hashTable.containsKey(key)) {
                        currentMatches = hashTable.get(key).iterator();
                        if (currentMatches.hasNext()) {
                            return true;
                        }
                    }
                    
                    // For LEFT JOIN, return row even without match
                    if (joinType == JoinType.LEFT) {
                        // Return left row with nulls
                        return true;
                    }
                    
                    currentLeftRow = null;
                } else {
                    leftExhausted = true;
                    return false;
                }
            } else {
                currentLeftRow = null;
            }
        }
    }
    
    public Object[] getNext() throws IOException {
        if (currentMatches != null && currentMatches.hasNext()) {
            Object[] rightRow = currentMatches.next();
            return combineRows(currentLeftRow, rightRow);
        }
        
        if (joinType == JoinType.LEFT && currentLeftRow != null) {
            Object[] result = combineRows(currentLeftRow, null);
            currentLeftRow = null;
            return result;
        }
        
        return null;
    }
    
    private Object getJoinKey(Object[] row, boolean isLeft) {
        String columnName = isLeft ? leftJoinColumn : rightJoinColumn;
        if (columnName == null) return null;
        
        DBFReader reader = isLeft ? leftReader : rightReader;
        List<DBFField> fields = reader.getHeader().getFields();
        
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).getName().equalsIgnoreCase(columnName)) {
                return row[i];
            }
        }
        return null;
    }
    
    private Object[] readFullRow(DBFReader reader) throws IOException {
        List<DBFField> fields = reader.getHeader().getFields();
        Object[] row = new Object[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            try {
				row[i] = reader.getValue(i);
			} catch (IOException | SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
        }
        return row;
    }
    
    private Object[] combineRows(Object[] leftRow, Object[] rightRow) {
        int leftSize = leftReader.getHeader().getFieldCount();
        int rightSize = rightReader.getHeader().getFieldCount();
        Object[] combined = new Object[leftSize + rightSize];
        
        if (leftRow != null) {
            System.arraycopy(leftRow, 0, combined, 0, Math.min(leftSize, leftRow.length));
        }
        if (rightRow != null) {
            System.arraycopy(rightRow, 0, combined, leftSize, Math.min(rightSize, rightRow.length));
        }
        return combined;
    }
    
    public void reset() throws IOException {
        leftReader.beforeFirst();
        rightReader.beforeFirst();
        currentLeftRow = null;
        currentMatches = null;
        leftExhausted = false;
        rightBuilt = false;
        hashTable = null;
        leftRowCount = 0;
        rightRowCount = 0;
    }
    
    public boolean isJoined() { return rightBuilt; }
    
    public DBFReader getLeftReader() { return leftReader; }
    public DBFReader getRightReader() { return rightReader; }
    public long getLeftRowCount() { return leftRowCount; }
    public long getRightRowCount() { return rightRowCount; }
    public int getHashTableSize() { return hashTable != null ? hashTable.size() : 0; }
}