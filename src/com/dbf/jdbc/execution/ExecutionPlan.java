package com.dbf.jdbc.execution;

import com.dbf.jdbc.parser.ast.SelectNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Query execution plan containing the operator tree
 */
public class ExecutionPlan {
    private final SelectNode originalQuery;
    private final Operator rootOperator;
    private final List<String> warnings;
    private long estimatedCost = -1;
    
    public ExecutionPlan(SelectNode originalQuery, Operator rootOperator) {
        this.originalQuery = originalQuery;
        this.rootOperator = rootOperator;
        this.warnings = new ArrayList<>();
    }
    
    public SelectNode getOriginalQuery() {
        return originalQuery;
    }
    
    public Operator getRootOperator() {
        return rootOperator;
    }
    
    public void addWarning(String warning) {
        warnings.add(warning);
    }
    
    public List<String> getWarnings() {
        return warnings;
    }
    
    public void setEstimatedCost(long cost) {
        this.estimatedCost = cost;
    }
    
    public long getEstimatedCost() {
        return estimatedCost;
    }
    
    public String explain() {
        StringBuilder sb = new StringBuilder();
        sb.append("Execution Plan:\n");
        explainOperator(rootOperator, sb, 0);
        sb.append("\nEstimated Cost: ").append(estimatedCost);
        if (!warnings.isEmpty()) {
            sb.append("\nWarnings:\n");
            for (String w : warnings) {
                sb.append("  - ").append(w).append("\n");
            }
        }
        return sb.toString();
    }
    
    private void explainOperator(Operator op, StringBuilder sb, int depth) {
        String indent = repeat("  ",depth);
        sb.append(indent).append(op.getOperatorName()).append("\n");
        
        // Add operator details
        String details = op.getOperatorDetails();
        if (details != null && !details.isEmpty()) {
            sb.append(indent).append("  ").append(details).append("\n");
        }
        
        for (Operator child : op.getChildren()) {
            explainOperator(child, sb, depth + 1);
        }
    }
    public static String repeat(String str, int len) {
	    StringBuilder sb = new StringBuilder(len * str.length());
	    for (int i = 0; i < len; i++) {
	        sb.append(str);
	    }
	    return sb.toString();
	}
}