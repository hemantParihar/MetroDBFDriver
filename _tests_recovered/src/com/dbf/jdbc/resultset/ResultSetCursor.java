package com.dbf.jdbc.resultset;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Manages result set cursor position and navigation
 */
public class ResultSetCursor {
    private List<Object[]> rowCache;
    private List<Map<String, Object>> aggregatedCache;
    private int currentRow = -1;
    private int totalRows = 0;
    private boolean isAggregated = false;
    private boolean isJoined = false;
    private int fetchSize = 100;
    private int fetchDirection = java.sql.ResultSet.FETCH_FORWARD;
    
    public ResultSetCursor() {
        this.rowCache = new java.util.ArrayList<>();
    }
    
    public void setRows(List<Object[]> rows) {
        this.rowCache = rows;
        this.totalRows = rows.size();
        this.isAggregated = false;
        this.isJoined = false;
        this.currentRow = -1;
    }
    
    public void setAggregatedRows(List<Map<String, Object>> rows) {
        this.aggregatedCache = rows;
        this.totalRows = rows.size();
        this.isAggregated = true;
        this.isJoined = false;
        this.currentRow = -1;
    }
    
    public void setJoinedRows(List<Object[]> rows, int leftFieldCount, int rightFieldCount) {
        this.rowCache = rows;
        this.totalRows = rows.size();
        this.isAggregated = false;
        this.isJoined = true;
        this.currentRow = -1;
    }
    
    public boolean next() {
        if (currentRow + 1 < totalRows) {
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
        if (row == 0) {
            beforeFirst();
            return false;
        }
        int target = row > 0 ? row - 1 : totalRows + row;
        if (target >= 0 && target < totalRows) {
            currentRow = target;
            return true;
        }
        if (target < 0) {
            beforeFirst();
        } else {
            afterLast();
        }
        return false;
    }
    
    public boolean relative(int rows) {
        return absolute(getRow() + rows);
    }
    
    public boolean first() {
        if (totalRows > 0) {
            currentRow = 0;
            return true;
        }
        return false;
    }
    
    public boolean last() {
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
        currentRow = totalRows;
    }
    
    public int getRow() {
        return isBeforeFirst() || isAfterLast() ? 0 : currentRow + 1;
    }
    
    public boolean isBeforeFirst() {
        return currentRow == -1 && totalRows > 0;
    }
    
    public boolean isAfterLast() {
        return currentRow >= totalRows;
    }
    
    public boolean isFirst() {
        return currentRow == 0;
    }
    
    public boolean isLast() {
        return currentRow == totalRows - 1 && currentRow >= 0;
    }
    
    public Object[] getCurrentRow() {
        if (isAggregated || isJoined) {
            // For aggregated/joined, need special handling
            return null;
        }
        if (currentRow < 0 || currentRow >= totalRows) {
            return null;
        }
        return rowCache.get(currentRow);
    }
    
    public Map<String, Object> getCurrentAggregatedRow() {
        if (!isAggregated || aggregatedCache == null) {
            return null;
        }
        if (currentRow < 0 || currentRow >= totalRows) {
            return null;
        }
        return aggregatedCache.get(currentRow);
    }
    
    public Object[] getCurrentJoinedRow(int leftFieldCount) {
        if (!isJoined || rowCache == null) {
            return null;
        }
        if (currentRow < 0 || currentRow >= totalRows) {
            return null;
        }
        return rowCache.get(currentRow);
    }
    
    public boolean isAggregated() {
        return isAggregated;
    }
    
    public boolean isJoined() {
        return isJoined;
    }
    
    public int getTotalRows() {
        return totalRows;
    }
    
    public int getCurrentPosition() {
        return currentRow;
    }
    
    public void setFetchSize(int size) {
        this.fetchSize = size;
    }
    
    public int getFetchSize() {
        return fetchSize;
    }
    
    public void setFetchDirection(int direction) throws SQLException {
        if (direction != java.sql.ResultSet.FETCH_FORWARD && 
            direction != java.sql.ResultSet.FETCH_REVERSE &&
            direction != java.sql.ResultSet.FETCH_UNKNOWN) {
            throw new SQLException("Invalid fetch direction: " + direction);
        }
        this.fetchDirection = direction;
    }
    
    public int getFetchDirection() {
        return fetchDirection;
    }
}