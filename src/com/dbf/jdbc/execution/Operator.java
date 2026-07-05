package com.dbf.jdbc.execution;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class for all execution operators
 * Follows the Iterator/Volcano model
 */
public abstract class Operator {
    protected List<Operator> children;
    protected Operator parent;
    protected String operatorId;
    protected boolean isOpen = false;
    protected long rowsProcessed = 0;
    
    public Operator() {
        this.children = new ArrayList<>();
        this.operatorId = generateOperatorId();
    }
    
    private static long idCounter = 0;
    private synchronized String generateOperatorId() {
        return getClass().getSimpleName() + "_" + (++idCounter);
    }
    
    public void addChild(Operator child) {
        children.add(child);
        child.setParent(this);
    }
    
    public void setParent(Operator parent) {
        this.parent = parent;
    }
    
    public List<Operator> getChildren() {
        return children;
    }
    
    public Operator getParent() {
        return parent;
    }
    
    public String getOperatorId() {
        return operatorId;
    }
    
    /**
     * Open the operator (initialize resources)
     */
    public void open() throws SQLException, IOException {
        isOpen = true;
        rowsProcessed = 0;
        for (Operator child : children) {
            child.open();
        }
    }
    
    /**
     * Get the next row from this operator
     * @return Object array representing the row, or null if no more rows
     */
    public abstract Object[] next() throws SQLException, IOException;
    
    /**
     * Close the operator (release resources)
     */
    public void close() throws SQLException, IOException {
        isOpen = false;
        for (Operator child : children) {
            child.close();
        }
    }
    
    /**
     * Reset the operator to start from beginning
     */
    public void reset() throws SQLException, IOException {
        rowsProcessed = 0;
        for (Operator child : children) {
            child.reset();
        }
    }
    
    /**
     * Get operator name for explain plan
     */
    public abstract String getOperatorName();
    
    /**
     * Get operator details for explain plan
     */
    public String getOperatorDetails() {
        return "rows=" + rowsProcessed;
    }
    
    /**
     * Estimate the cost of this operator (rows processed)
     */
    public abstract long estimateCost() throws SQLException, IOException;
    
    public long getRowsProcessed() {
        return rowsProcessed;
    }
    
    protected void incrementRowsProcessed() {
        rowsProcessed++;
    }
}