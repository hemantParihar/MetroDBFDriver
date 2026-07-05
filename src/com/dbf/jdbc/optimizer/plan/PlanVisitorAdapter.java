package com.dbf.jdbc.optimizer.plan;

/**
 * Base visitor with empty implementations
 */
public abstract class PlanVisitorAdapter implements PlanVisitor {
    @Override public void visit(TableScanNode node) {}
    @Override public void visit(FilterNode node) {}
    @Override public void visit(ProjectionNode node) {}
    @Override public void visit(SortNode node) {}
    @Override public void visit(JoinNode node) {}
    @Override public void visit(AggregateNode node) {}
    @Override public void visit(LimitNode node) {}
}
