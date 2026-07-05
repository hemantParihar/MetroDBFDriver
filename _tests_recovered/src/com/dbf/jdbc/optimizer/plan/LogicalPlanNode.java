package com.dbf.jdbc.optimizer.plan;

public abstract class LogicalPlanNode {
    private LogicalPlanNode[] children;
    private double estimatedCost = -1;
    private long estimatedRows = -1;
    
    public void setChildren(LogicalPlanNode... children) {
        this.children = children;
    }
    
    public LogicalPlanNode[] getChildren() {
        return children;
    }
    
    public abstract String getNodeName();
    
    public double getEstimatedCost() {
        return estimatedCost;
    }
    
    public void setEstimatedCost(double cost) {
        this.estimatedCost = cost;
    }
    
    public long getEstimatedRows() {
        return estimatedRows;
    }
    
    public void setEstimatedRows(long rows) {
        this.estimatedRows = rows;
    }
    
    public abstract void accept(PlanVisitor visitor);
}