package com.dbf.jdbc.optimizer.plan;

import com.dbf.jdbc.optimizer.plan.JoinNode.JoinType;

/**
 * Join node
 */
public class JoinNode extends LogicalPlanNode {
    private final JoinType joinType;
    private final String leftColumn;
    private final String rightColumn;
    private String joinAlgorithm = "HashJoin";
    
    public enum JoinType { INNER, LEFT, RIGHT, FULL }
    
    public JoinNode(JoinType joinType, String leftColumn, String rightColumn) {
        this.joinType = joinType;
        this.leftColumn = leftColumn;
        this.rightColumn = rightColumn;
    }
    
    public JoinType getJoinType() { return joinType; }
    public String getLeftColumn() { return leftColumn; }
    public String getRightColumn() { return rightColumn; }
    public String getJoinAlgorithm() { return joinAlgorithm; }
    
    public void setJoinAlgorithm(String algorithm) { 
        this.joinAlgorithm = algorithm;
        // Update cost based on algorithm
        if ("HashJoin".equals(algorithm)) {
            setEstimatedCost(getEstimatedCost() / 2);
        }
    }
    
    @Override
    public String getNodeName() { return "Join"; }
    
    @Override
    public void accept(PlanVisitor visitor) {
        visitor.visit(this);
    }
}

