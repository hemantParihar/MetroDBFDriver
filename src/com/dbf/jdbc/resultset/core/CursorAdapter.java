package com.dbf.jdbc.resultset.core;

/**
 * Manages cursor position - no knowledge of data
 */
public class CursorAdapter {
    private int currentRow = -1;
    private int totalRows = -1; // -1 means unknown (streaming)
    private boolean isStreaming = true;
    private int fetchSize = 100;
    private int fetchDirection = java.sql.ResultSet.FETCH_FORWARD;
    
    // For aggregated results
    private boolean isAggregated = false;
    private int aggregatedTotalRows = 0;
    
    public CursorAdapter(boolean isStreaming) {
        this.isStreaming = isStreaming;
    }
    
    public boolean next() {
        if (isStreaming) {
            // Streaming - just increment, total unknown
            currentRow++;
            return true;
        } else if (totalRows >= 0 && currentRow + 1 < totalRows) {
            currentRow++;
            return true;
        }
        return false;
    }
    
    public boolean previous() {
        if (currentRow > 0) {
            currentRow--;
            return true;
        }
        if (currentRow == 0) {
            beforeFirst();
        }
        return false;
    }
    
    public boolean absolute(int row) {
        if (isStreaming) {
            // Streaming doesn't support absolute positioning
            return false;
        }
        if (row == 0) {
            beforeFirst();
            return false;
        }
        int target = row > 0 ? row - 1 : totalRows + row;
        if (target >= 0 && target < totalRows) {
            currentRow = target;
            return true;
        }
        if (target < 0) beforeFirst();
        else afterLast();
        return false;
    }
    
    public boolean relative(int rows) {
        return absolute(getRow() + rows);
    }
    
    public boolean first() {
        if (isStreaming) return false;
        if (totalRows > 0) {
            currentRow = 0;
            return true;
        }
        return false;
    }
    
    public boolean last() {
        if (isStreaming) return false;
        if (totalRows > 0) {
            currentRow = totalRows - 1;
            return true;
        }
        return false;
    }
    
    public void beforeFirst() { 
        currentRow = -1; 
    }
    
    public void afterLast() { 
        if (!isStreaming && totalRows >= 0) {
            currentRow = totalRows;
        }
    }
    
    public int getRow() { 
        return isBeforeFirst() || isAfterLast() ? 0 : currentRow + 1; 
    }
    
    public int getCurrentPosition() { 
        return currentRow; 
    }
    
    public void setTotalRows(int totalRows) { 
        this.totalRows = totalRows; 
        this.isStreaming = false;
    }
    
    public void setAggregatedTotalRows(int totalRows) {
        this.aggregatedTotalRows = totalRows;
        this.isAggregated = true;
        this.isStreaming = false;
    }
    
    public boolean isStreaming() { 
        return isStreaming; 
    }
    
    public boolean isAggregated() {
        return isAggregated;
    }
    
    public boolean isBeforeFirst() { 
        return currentRow == -1 && (totalRows == -1 || totalRows > 0); 
    }
    
    public boolean isAfterLast() { 
        if (isStreaming) return false;
        return currentRow >= totalRows; 
    }
    
    public boolean isFirst() { 
        return currentRow == 0; 
    }
    
    public boolean isLast() { 
        if (isStreaming) return false;
        return currentRow == totalRows - 1 && currentRow >= 0; 
    }
    
    // Fetch size methods
    public void setFetchSize(int rows) {
        if (rows < 0) {
            throw new IllegalArgumentException("Fetch size cannot be negative: " + rows);
        }
        this.fetchSize = rows;
    }
    
    public int getFetchSize() {
        return fetchSize;
    }
    
    // Fetch direction methods
    public void setFetchDirection(int direction) {
        if (direction != java.sql.ResultSet.FETCH_FORWARD && 
            direction != java.sql.ResultSet.FETCH_REVERSE && 
            direction != java.sql.ResultSet.FETCH_UNKNOWN) {
            throw new IllegalArgumentException("Invalid fetch direction: " + direction);
        }
        this.fetchDirection = direction;
    }
    
    public int getFetchDirection() {
        return fetchDirection;
    }
    
    // Reset method
    public void reset() {
        currentRow = -1;
    }
    
    @Override
    public String toString() {
        return "CursorAdapter{" +
               "currentRow=" + currentRow +
               ", totalRows=" + totalRows +
               ", isStreaming=" + isStreaming +
               ", fetchSize=" + fetchSize +
               '}';
    }
}