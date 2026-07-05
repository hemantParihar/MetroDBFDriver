package com.dbf.jdbc.resultset.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages row caching for scrollable result sets
 * Does NOT know about JDBC or conversions
 */
public class ResultSetBuffer {
    private List<Object[]> rows;
    private int maxRows = 0;
    private boolean fetchComplete = false;
    
    public ResultSetBuffer() {
        this.rows = new ArrayList<>();
    }
    
    public void addRow(Object[] row) {
        if (maxRows == 0 || rows.size() < maxRows) {
            rows.add(row);
        }
        if (maxRows > 0 && rows.size() >= maxRows) {
            fetchComplete = true;
        }
    }
    
    public Object[] getRow(int index) {
        if (index >= 0 && index < rows.size()) {
            return rows.get(index);
        }
        return null;
    }
    
    public int size() { return rows.size(); }
    public void clear() { rows.clear(); }
    public boolean isFetchComplete() { return fetchComplete; }
    public void setMaxRows(int maxRows) { this.maxRows = maxRows; }
    public void setFetchComplete(boolean complete) { this.fetchComplete = complete; }
    
    public List<Object[]> getAllRows() {
        return rows;
    }
}