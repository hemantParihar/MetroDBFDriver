package com.dbf.jdbc.optimizer.cost;

import com.dbf.jdbc.optimizer.plan.*;
import com.dbf.jdbc.optimizer.stats.StatisticsCollector;
import java.io.IOException;

/**
 * Cost-Based Optimizer (CBO) using statistics
 */
public class CostBasedOptimizer {
    private final StatisticsCollector stats;
    
    public CostBasedOptimizer(String basePath) {
        this.stats = new StatisticsCollector(basePath);
    }
    
    public LogicalPlanNode optimize(LogicalPlanNode root) throws IOException {
        // Calculate costs for all plans
        calculateCosts(root);
        
        // Choose optimal join order
        if (root instanceof JoinNode) {
            return optimizeJoinOrder((JoinNode) root);
        }
        
        // Choose optimal join algorithm
        if (root instanceof JoinNode) {
            optimizeJoinAlgorithm((JoinNode) root);
        }
        
        // Choose between full scan and index scan
        optimizeScanMethod(root);
        
        return root;
    }
    
    private void calculateCosts(LogicalPlanNode node) throws IOException {
        for (LogicalPlanNode child : node.getChildren()) {
            calculateCosts(child);
        }
        
        if (node instanceof TableScanNode) {
            TableScanNode scan = (TableScanNode) node;
            long rows = scan.getTableSize();
            scan.setEstimatedRows(rows);
            scan.setEstimatedCost((double) rows);
            
        } else if (node instanceof FilterNode) {
            FilterNode filter = (FilterNode) node;
            if (filter.getChildren().length > 0) {
                LogicalPlanNode child = filter.getChildren()[0];
                filter.setEstimatedRows((long) (child.getEstimatedRows() * filter.getSelectivity()));
                filter.setEstimatedCost(child.getEstimatedCost() + filter.getEstimatedRows());
            }
            
        } else if (node instanceof SortNode) {
            SortNode sort = (SortNode) node;
            if (sort.getChildren().length > 0) {
                LogicalPlanNode child = sort.getChildren()[0];
                long rows = child.getEstimatedRows();
                sort.setEstimatedRows(rows);
                // Sort cost: O(n log n)
                double sortCost = rows * Math.log(rows);
                sort.setEstimatedCost(child.getEstimatedCost() + sortCost);
            }
            
        } else if (node instanceof JoinNode) {
            JoinNode join = (JoinNode) node;
            if (join.getChildren().length == 2) {
                LogicalPlanNode left = join.getChildren()[0];
                LogicalPlanNode right = join.getChildren()[1];
                long leftRows = left.getEstimatedRows();
                long rightRows = right.getEstimatedRows();
                
                // Join cost depends on algorithm
                if ("HashJoin".equals(join.getJoinAlgorithm())) {
                    double hashCost = left.getEstimatedCost() + right.getEstimatedCost() + 
                                      Math.min(leftRows, rightRows);
                    join.setEstimatedCost(hashCost);
                    join.setEstimatedRows(leftRows * rightRows / 100); // Estimate
                } else {
                    // Nested loop: O(n * m)
                    double nestedCost = left.getEstimatedCost() + right.getEstimatedCost() + 
                                        (double) leftRows * rightRows;
                    join.setEstimatedCost(nestedCost);
                    join.setEstimatedRows(leftRows * rightRows / 100);
                }
            }
        }
    }
    
    /**
     * Reorder joins to minimize cost (smallest first)
     */
    private JoinNode optimizeJoinOrder(JoinNode join) throws IOException {
        if (join.getChildren().length != 2) return join;
        
        LogicalPlanNode left = join.getChildren()[0];
        LogicalPlanNode right = join.getChildren()[1];
        
        double leftCost = left.getEstimatedCost();
        double rightCost = right.getEstimatedCost();
        
        // Put smaller table on the left for hash join
        if (rightCost < leftCost) {
            JoinNode reordered = new JoinNode(join.getJoinType(), 
                join.getRightColumn(), join.getLeftColumn());
            reordered.setChildren(right, left);
            reordered.setJoinAlgorithm(join.getJoinAlgorithm());
            // Copy cost estimates
            reordered.setEstimatedCost(join.getEstimatedCost());
            reordered.setEstimatedRows(join.getEstimatedRows());
            return reordered;
        }
        
        return join;
    }
    
    /**
     * Choose optimal join algorithm based on table sizes
     */
    private void optimizeJoinAlgorithm(JoinNode join) throws IOException {
        if (join.getChildren().length != 2) return;
        
        LogicalPlanNode left = join.getChildren()[0];
        LogicalPlanNode right = join.getChildren()[1];
        
        long leftSize = left.getEstimatedRows();
        long rightSize = right.getEstimatedRows();
        long smaller = Math.min(leftSize, rightSize);
        
        if (smaller < 10000) {
            join.setJoinAlgorithm("HashJoin");
        } else if (hasIndex(join)) {
            join.setJoinAlgorithm("IndexNestedLoop");
        } else {
            join.setJoinAlgorithm("SortMergeJoin");
        }
    }
    
    /**
     * Choose between full table scan and index scan
     */
    private void optimizeScanMethod(LogicalPlanNode node) throws IOException {
        if (node instanceof TableScanNode) {
            TableScanNode scan = (TableScanNode) node;
            // Check if filter on indexed column exists in parent
            if (hasSelectiveFilter(scan)) {
                // Use index if available
                if (scan.getTableSize() > 10000 && hasIndexOnFilterColumn(scan)) {
                    scan.setIndexScan(scan.getTableName() + "_idx", getFilterColumn(scan));
                }
            }
        }
        
        for (LogicalPlanNode child : node.getChildren()) {
            optimizeScanMethod(child);
        }
    }
    
    private boolean hasIndex(JoinNode join) throws IOException {
        // Check if join column has index on right table
        return false;
    }
    
    private boolean hasSelectiveFilter(TableScanNode scan) {
        // Check if there's a filter that significantly reduces rows
        return false;
    }
    
    private boolean hasIndexOnFilterColumn(TableScanNode scan) throws IOException {
        String tableName = scan.getTableName();
        String filterColumn = getFilterColumn(scan);
        if (filterColumn == null || filterColumn.isEmpty()) return false;
        
        try {
            com.dbf.jdbc.optimizer.stats.StatisticsCollector.TableStats tableStats = 
                stats.getTableStats(tableName);
            return tableStats.indexes.containsKey(filterColumn.toLowerCase());
        } catch (Exception e) {
            return false;
        }
    }
    
    private String getFilterColumn(TableScanNode scan) {
        // Extract filter column from parent FilterNode
        // This is simplified - in production, traverse the plan tree
        return "";
    }
}