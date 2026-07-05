package com.dbf.jdbc.optimizer.plan;


/**
 * Visitor pattern for plan transformations
 */
public interface PlanVisitor {
    void visit(TableScanNode node);
    void visit(FilterNode node);
    void visit(ProjectionNode node);
    void visit(SortNode node);
    void visit(JoinNode node);
    void visit(AggregateNode node);
    void visit(LimitNode node);
}

