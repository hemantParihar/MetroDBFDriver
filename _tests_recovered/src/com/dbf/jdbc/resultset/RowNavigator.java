package com.dbf.jdbc.resultset;

import java.sql.SQLException;

/**
 * Facade for row navigation operations
 */
public class RowNavigator {
    private final ResultSetCursor cursor;
    
    public RowNavigator(ResultSetCursor cursor) {
        this.cursor = cursor;
    }
    
    public boolean next() {
        return cursor.next();
    }
    
    public boolean previous() throws SQLException {
        return cursor.previous();
    }
    
    public boolean absolute(int row) throws SQLException {
        return cursor.absolute(row);
    }
    
    public boolean relative(int rows) throws SQLException {
        return cursor.relative(rows);
    }
    
    public boolean first() throws SQLException {
        return cursor.first();
    }
    
    public boolean last() throws SQLException {
        return cursor.last();
    }
    
    public void beforeFirst() throws SQLException {
        cursor.beforeFirst();
    }
    
    public void afterLast() throws SQLException {
        cursor.afterLast();
    }
    
    public int getRow() throws SQLException {
        return cursor.getRow();
    }
    
    public boolean isBeforeFirst() throws SQLException {
        return cursor.isBeforeFirst();
    }
    
    public boolean isAfterLast() throws SQLException {
        return cursor.isAfterLast();
    }
    
    public boolean isFirst() throws SQLException {
        return cursor.isFirst();
    }
    
    public boolean isLast() throws SQLException {
        return cursor.isLast();
    }
    
    public void setFetchSize(int rows) throws SQLException {
        if (rows < 0) {
            throw new SQLException("Fetch size cannot be negative");
        }
        cursor.setFetchSize(rows);
    }
    
    public int getFetchSize() throws SQLException {
        return cursor.getFetchSize();
    }
    
    public void setFetchDirection(int direction) throws SQLException {
        cursor.setFetchDirection(direction);
    }
    
    public int getFetchDirection() throws SQLException {
        return cursor.getFetchDirection();
    }
}