package com.dbf.jdbc.execution;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Limits the number of rows returned (for FETCH FIRST / LIMIT)
 */
public class LimitOperator extends Operator {
    private final long limit;
    private long rowsReturned = 0;
    private boolean limitReached = false;
    
    public LimitOperator(long limit) {
        this.limit = limit;
    }
    
    @Override
    public void open() throws SQLException, IOException {
        super.open();
        rowsReturned = 0;
        limitReached = false;
        if (children.isEmpty()) {
            throw new SQLException("LimitOperator requires a child operator");
        }
    }
    
    @Override
    public Object[] next() throws SQLException, IOException {
        if (limitReached || rowsReturned >= limit) {
            limitReached = true;
            return null;
        }
        
        Object[] row = children.get(0).next();
        if (row == null) return null;
        
        rowsReturned++;
        incrementRowsProcessed();
        return row;
    }
    
    @Override
    public void reset() throws SQLException, IOException {
        super.reset();
        rowsReturned = 0;
        limitReached = false;
    }
    
    @Override
    public String getOperatorName() {
        return "Limit";
    }
    
    @Override
    public String getOperatorDetails() {
        return "limit=" + limit;
    }
    
    @Override
    public long estimateCost() throws SQLException, IOException {
        if (children.isEmpty()) return 0;
        long childCost = children.get(0).estimateCost();
        return Math.min(childCost, limit);
    }
}