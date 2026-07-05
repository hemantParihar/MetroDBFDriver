package com.dbf.jdbc.optimizer.plan;

import java.util.List;

/**
 * Sort node (ORDER BY)
 */
public class SortNode extends LogicalPlanNode {
    private final List<String> sortColumns;
    private final List<Boolean> ascending;
    
    public SortNode(List<String> sortColumns, List<Boolean> ascending) {
        this.sortColumns = sortColumns;
        this.ascending = ascending;
    }
    
    public List<String> getSortColumns() { return sortColumns; }
    public List<Boolean> getAscending() { return ascending; }
    
    @Override
    public String getNodeName() { return "Sort"; }
    
    @Override
    public void accept(PlanVisitor visitor) {
        visitor.visit(this);
    }
}