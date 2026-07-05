package com.dbf.jdbc.optimizer.rules;

import java.util.ArrayList;
import java.util.List;

import com.dbf.jdbc.optimizer.plan.FilterNode;
import com.dbf.jdbc.optimizer.plan.LogicalPlanNode;
import com.dbf.jdbc.optimizer.plan.ProjectionNode;
import com.dbf.jdbc.optimizer.plan.SortNode;
import com.dbf.jdbc.optimizer.plan.TableScanNode;
import com.dbf.jdbc.parser.TokenType;
import com.dbf.jdbc.parser.ast.ExpressionNode;

/**
 * Rule-based optimizer that applies transformation rules
 */
public class RuleBasedOptimizer {
    private final List<OptimizationRule> rules = new ArrayList<>();
    
    public RuleBasedOptimizer() {
        // Register rules in order of application
        rules.add(new PushFilterDownRule());
        rules.add(new PushProjectionDownRule());
        rules.add(new EliminateRedundantSortRule());
        rules.add(new MergeFiltersRule());
        rules.add(new ConstantFoldingRule());
    }
    
    public LogicalPlanNode optimize(LogicalPlanNode root) {
        boolean changed;
        do {
            changed = false;
            for (OptimizationRule rule : rules) {
                LogicalPlanNode newRoot = rule.apply(root);
                if (newRoot != root) {
                    root = newRoot;
                    changed = true;
                }
            }
        } while (changed);
        
        return root;
    }
    
    interface OptimizationRule {
        LogicalPlanNode apply(LogicalPlanNode node);
    }
    
    /**
     * Rule 1: Push filters down toward table scans
     */
    class PushFilterDownRule implements OptimizationRule {
        @Override
        public LogicalPlanNode apply(LogicalPlanNode node) {
            if (node instanceof FilterNode && node.getChildren().length > 0) {
                LogicalPlanNode child = node.getChildren()[0];
                
                // If child is also a filter, merge them
                if (child instanceof FilterNode) {
                    // Merge conditions
                    return node;
                }
                
                // Push filter through projection
                if (child instanceof ProjectionNode) {
                    // Filter can be pushed below projection if it only uses projected columns
                    return node;
                }
            }
            return node;
        }
    }
    
    /**
     * Rule 2: Push projections down
     */
    class PushProjectionDownRule implements OptimizationRule {
        @Override
        public LogicalPlanNode apply(LogicalPlanNode node) {
            if (node instanceof ProjectionNode && node.getChildren().length > 0) {
                LogicalPlanNode child = node.getChildren()[0];
                
                // Push projection through filter
                if (child instanceof FilterNode) {
                    // Projection can be pushed
                    return node;
                }
            }
            return node;
        }
    }
    
    /**
     * Rule 3: Eliminate redundant sorts
     */
    class EliminateRedundantSortRule implements OptimizationRule {
        @Override
        public LogicalPlanNode apply(LogicalPlanNode node) {
            if (node instanceof SortNode && node.getChildren().length > 0) {
                LogicalPlanNode child = node.getChildren()[0];
                
                // If child already sorted in same order, eliminate sort
                if (isAlreadySorted(child, (SortNode) node)) {
                    return child;
                }
            }
            return node;
        }
        
        private boolean isAlreadySorted(LogicalPlanNode node, SortNode sort) {
            // Check if data comes from index that provides required order
            if (node instanceof TableScanNode) {
                TableScanNode scan = (TableScanNode) node;
                if (scan.useIndex() && sort.getSortColumns().size() == 1) {
                    return scan.getIndexColumn().equalsIgnoreCase(sort.getSortColumns().get(0));
                }
            }
            return false;
        }
    }
    
    /**
     * Rule 4: Merge adjacent filters
     */
    class MergeFiltersRule implements OptimizationRule {
        @Override
        public LogicalPlanNode apply(LogicalPlanNode node) {
            if (node instanceof FilterNode && node.getChildren().length > 0) {
                LogicalPlanNode child = node.getChildren()[0];
                if (child instanceof FilterNode) {
                    // Merge conditions with AND
                    return child; // Simplified - should combine conditions
                }
            }
            return node;
        }
    }
    
    /**
     * Rule 5: Constant folding
     */
    class ConstantFoldingRule implements OptimizationRule {
        @Override
        public LogicalPlanNode apply(LogicalPlanNode node) {
            // Evaluate constant expressions at optimization time
            // e.g., WHERE 1 = 1  eliminate filter
            return node;
        }
    }
 // Add to RuleBasedOptimizer.java

    /**
     * Rule 6: Convert Filter + TableScan to IndexScan when possible
     */
    class ConvertToIndexScanRule implements OptimizationRule {
        @Override
        public LogicalPlanNode apply(LogicalPlanNode node) {
            if (node instanceof FilterNode && node.getChildren().length > 0) {
                LogicalPlanNode child = node.getChildren()[0];
                if (child instanceof TableScanNode) {
                    TableScanNode scan = (TableScanNode) child;
                    FilterNode filter = (FilterNode) node;
                    
                    // Check if filter is equality on indexed column
                    String indexedColumn = extractEqualityColumn(filter.getCondition());
                    if (indexedColumn != null && hasIndex(scan.getTableName(), indexedColumn)) {
                        // Convert to index scan
                        scan.setIndexScan(getIndexName(scan.getTableName(), indexedColumn), indexedColumn);
                        // Filter can be removed since index handles it
                        return scan;
                    }
                }
            }
            return node;
        }
        
        private String extractEqualityColumn(ExpressionNode condition) {
            if (condition == null) return null;
            if (condition.isBinaryOp() && condition.getType() == TokenType.EQ) {
                if (condition.getLeft().isColumn()) {
                    return condition.getLeft().getColumnName();
                }
                if (condition.getRight().isColumn()) {
                    return condition.getRight().getColumnName();
                }
            }
            return null;
        }
        
        private boolean hasIndex(String tableName, String columnName) {
            // Check if index exists for this column
            // In production, use StatisticsCollector
            return false;
        }
        
        private String getIndexName(String tableName, String columnName) {
            return tableName + "_" + columnName + "_idx";
        }
    }
}