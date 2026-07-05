package com.dbf.jdbc.optimizer.plan;

import java.util.List;
import java.util.Map;

/**
 * Aggregate node (GROUP BY)
 */
public class AggregateNode extends LogicalPlanNode {
    private final List<String> groupByColumns;
    private final Map<String, String> aggregates;
    
    public AggregateNode(List<String> groupByColumns, Map<String, String> aggregates) {
        this.groupByColumns = groupByColumns;
        this.aggregates = aggregates;
    }
    
    public List<String> getGroupByColumns() { return groupByColumns; }
    public Map<String, String> getAggregates() { return aggregates; }
    
    @Override
    public String getNodeName() { return "Aggregate"; }
    
    @Override
    public void accept(PlanVisitor visitor) {
        visitor.visit(this);
    }
}

