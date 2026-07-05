package com.dbf.jdbc.optimizer;

import java.io.IOException;
import java.sql.SQLException;

import com.dbf.jdbc.execution.streaming.RowStream;
import com.dbf.jdbc.optimizer.cost.CostBasedOptimizer;
import com.dbf.jdbc.optimizer.physical.PhysicalPlanGenerator;
import com.dbf.jdbc.optimizer.plan.FilterNode;
import com.dbf.jdbc.optimizer.plan.LogicalPlanNode;
import com.dbf.jdbc.optimizer.plan.TableScanNode;
import com.dbf.jdbc.optimizer.rules.RuleBasedOptimizer;
import com.dbf.jdbc.parser.TokenType;
import com.dbf.jdbc.parser.ast.ExpressionNode;
import com.dbf.jdbc.parser.ast.SelectNode;

/**
 * Main query optimizer - orchestrates all optimization phases
 */
public class QueryOptimizer {
    private final RuleBasedOptimizer ruleOptimizer;
    private final CostBasedOptimizer costOptimizer;
    private final PhysicalPlanGenerator physicalGenerator;
    
    public QueryOptimizer(String basePath, String charset) {
        this.ruleOptimizer = new RuleBasedOptimizer();
        this.costOptimizer = new CostBasedOptimizer(basePath);
        this.physicalGenerator = new PhysicalPlanGenerator(basePath, charset);
    }
    
    /**
     * Optimize a query and generate physical plan
     * 
     * Phases:
     * 1. Build initial logical plan from AST
     * 2. Apply rule-based optimizations (push filters, eliminate redundancies)
     * 3. Apply cost-based optimizations (join order, algorithm selection)
     * 4. Generate physical execution operators
     */
    public RowStream optimize(SelectNode selectNode, String tablePath) throws SQLException, IOException {
        // Phase 1: Build logical plan from AST
        LogicalPlanNode logicalPlan = buildLogicalPlan(selectNode, tablePath);
        
        // Phase 2: Rule-based optimization (RBO)
        LogicalPlanNode optimizedPlan = ruleOptimizer.optimize(logicalPlan);
        
        // Phase 3: Cost-based optimization (CBO)
        optimizedPlan = costOptimizer.optimize(optimizedPlan);
        
        // Phase 4: Generate physical operators
        return physicalGenerator.generate(optimizedPlan);
    }
    
    private LogicalPlanNode buildLogicalPlan(SelectNode selectNode, String tablePath) throws IOException {
        // Get table size
        com.dbf.jdbc.dbf.DBFReader reader = new com.dbf.jdbc.dbf.DBFReader(
            tablePath, java.nio.charset.Charset.forName("UTF-8"));
        long tableSize = reader.getHeader().getRecordCount();
        reader.close();
        
        // Build plan bottom-up
        LogicalPlanNode root = new TableScanNode(
            selectNode.getFrom().getTableName(),
            selectNode.getFrom().getAlias(),
            tableSize);
        
        // Add filter if present
        if (selectNode.getWhere() != null) {
            FilterNode filter = new FilterNode(selectNode.getWhere().getCondition());
            filter.setChildren(root);
            root = filter;
        }
        
        // Add projection
        // ... build projection node
        
        // Add sort if present
        if (selectNode.getOrderBy() != null) {
            // Build sort node
        }
        
        // Add join if present
        if (selectNode.getJoin() != null) {
            // Build join node
        }
        
        return root;
    }
    
    /**
     * Print optimized execution plan for debugging
     */
    public void printPlan(LogicalPlanNode plan) {
        System.out.println("=== Optimized Execution Plan ===");
        printPlan(plan, 0);
        System.out.println("=================================");
    }
    
    private void printPlan(LogicalPlanNode node, int depth) {
        String indent =repeat("  ",depth);
        System.out.println(indent + node.getNodeName() + 
            " (cost=" + node.getEstimatedCost() + 
            ", rows=" + node.getEstimatedRows() + ")");
        
        for (LogicalPlanNode child : node.getChildren()) {
            printPlan(child, depth + 1);
        }
    }
    public static String repeat(String str, int len) {
	    StringBuilder sb = new StringBuilder(len * str.length());
	    for (int i = 0; i < len; i++) {
	        sb.append(str);
	    }
	    return sb.toString();
	}
 // Add to QueryOptimizer.java

    /**
     * Analyze query to determine if index can be used
     */
    private boolean canUseIndex(SelectNode selectNode) {
        if (selectNode.getWhere() == null) return false;
        
        ExpressionNode condition = selectNode.getWhere().getCondition();
        return isIndexableCondition(condition);
    }

    private boolean isIndexableCondition(ExpressionNode node) {
        if (node == null) return false;
        
        // Equality on column (e.g., id = 100)
        if (node.isBinaryOp() && node.getType() == TokenType.EQ) {
            return (node.getLeft().isColumn() && node.getRight().isLiteral()) ||
                   (node.getRight().isColumn() && node.getLeft().isLiteral());
        }
        
        // Range condition (e.g., id > 100, id BETWEEN 1 AND 100)
        if (node.isBinaryOp() && (node.getType() == TokenType.GT ||
            node.getType() == TokenType.LT ||
            node.getType() == TokenType.GE ||
            node.getType() == TokenType.LE)) {
            return (node.getLeft().isColumn() && node.getRight().isLiteral()) ||
                   (node.getRight().isColumn() && node.getLeft().isLiteral());
        }
        
        // AND conditions - check if any part is indexable
        if (node.getType() == TokenType.AND) {
            return isIndexableCondition(node.getLeft()) || 
                   isIndexableCondition(node.getRight());
        }
        
        return false;
    }
}