package com.dbf.jdbc.optimizer.plan;

import com.dbf.jdbc.parser.ast.ExpressionNode;

/**
 * Filter node (WHERE clause)
 */
public class FilterNode extends LogicalPlanNode {
    private final ExpressionNode condition;
    private double selectivity = 0.5; // Default: filter eliminates 50%
    
    public FilterNode(ExpressionNode condition) {
        this.condition = condition;
    }
    
    public ExpressionNode getCondition() { return condition; }
    public double getSelectivity() { return selectivity; }
    public void setSelectivity(double selectivity) { this.selectivity = selectivity; }
    
    @Override
    public String getNodeName() { return "Filter"; }
    
    @Override
    public void accept(PlanVisitor visitor) {
        visitor.visit(this);
    }
}