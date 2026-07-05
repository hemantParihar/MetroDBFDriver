package com.dbf.jdbc.optimizer.plan;

import java.util.List;

/**
 * Projection node (SELECT columns)
 */
public class ProjectionNode extends LogicalPlanNode {
    private final List<String> columns;
    private final List<String> aliases;
    
    public ProjectionNode(List<String> columns, List<String> aliases) {
        this.columns = columns;
        this.aliases = aliases;
    }
    
    public List<String> getColumns() { return columns; }
    public List<String> getAliases() { return aliases; }
    
    @Override
    public String getNodeName() { return "Projection"; }
    
    @Override
    public void accept(PlanVisitor visitor) {
        visitor.visit(this);
    }
}
