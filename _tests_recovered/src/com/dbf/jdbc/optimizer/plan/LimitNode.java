package com.dbf.jdbc.optimizer.plan;

/**
 * Limit node
 */
public class LimitNode extends LogicalPlanNode {
    private final long limit;
    
    public LimitNode(long limit) {
        this.limit = limit;
    }
    
    public long getLimit() { return limit; }
    
    @Override
    public String getNodeName() { return "Limit"; }
    
    @Override
    public void accept(PlanVisitor visitor) {
        visitor.visit(this);
    }
}
