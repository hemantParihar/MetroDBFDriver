package com.dbf.jdbc.optimizer.join;

import com.dbf.jdbc.optimizer.plan.JoinNode;
import java.util.*;

/**
 * Optimizes join order using dynamic programming
 * Finds the optimal join order with minimal cost
 */
public class JoinOrderOptimizer {
    
    public static class JoinGraph {
        private final List<String> tables;
        private final Map<String, Long> tableSizes;
        private final Map<String, Map<String, Double>> joinSelectivity;
        
        public JoinGraph() {
            this.tables = new ArrayList<>();
            this.tableSizes = new HashMap<>();
            this.joinSelectivity = new HashMap<>();
        }
        
        public void addTable(String table, long size) {
            tables.add(table);
            tableSizes.put(table, size);
        }
        
        public void addJoin(String left, String right, double selectivity) {
            joinSelectivity.computeIfAbsent(left, k -> new HashMap<>()).put(right, selectivity);
            joinSelectivity.computeIfAbsent(right, k -> new HashMap<>()).put(left, selectivity);
        }
        
        public long getTableSize(String table) {
            return tableSizes.getOrDefault(table, 1000L);
        }
        
        public double getJoinSelectivity(String left, String right) {
            Map<String, Double> map = joinSelectivity.get(left);
            if (map != null && map.containsKey(right)) {
                return map.get(right);
            }
            return 0.01; // Default 1% selectivity
        }
        
        public List<String> getTables() {
            return tables;
        }
    }
    
    /**
     * Dynamic programming to find optimal join order
     * Complexity: O(2^n * n^2) where n = number of tables
     */
    public static List<String> findOptimalOrder(JoinGraph graph) {
        int n = graph.getTables().size();
        if (n == 0) return new ArrayList<>();
        if (n == 1) return graph.getTables();
        
        // DP state: mask -> (cost, order)
        Map<Integer, JoinOrderState> dp = new HashMap<>();
        
        // Initialize single-table states
        for (int i = 0; i < n; i++) {
            int mask = 1 << i;
            String table = graph.getTables().get(i);
            long cost = graph.getTableSize(table);
            dp.put(mask, new JoinOrderState(cost, new ArrayList<>(Arrays.asList(table))));
        }
        
        // Build larger subsets
        for (int mask = 1; mask < (1 << n); mask++) {
            if (dp.containsKey(mask)) continue;
            
            // Try joining two subsets
            for (int submask = (mask - 1) & mask; submask > 0; submask = (submask - 1) & mask) {
                int otherMask = mask & ~submask;
                if (dp.containsKey(submask) && dp.containsKey(otherMask)) {
                    JoinOrderState left = dp.get(submask);
                    JoinOrderState right = dp.get(otherMask);
                    
                    // Calculate join cost
                    long joinCost = calculateJoinCost(left, right, graph);
                    long totalCost = left.cost + right.cost + joinCost;
                    
                    JoinOrderState current = dp.get(mask);
                    if (current == null || totalCost < current.cost) {
                        List<String> order = new ArrayList<>();
                        order.addAll(left.order);
                        order.addAll(right.order);
                        dp.put(mask, new JoinOrderState(totalCost, order));
                    }
                }
            }
        }
        
        int fullMask = (1 << n) - 1;
        JoinOrderState result = dp.get(fullMask);
        return result != null ? result.order : graph.getTables();
    }
    
    private static long calculateJoinCost(JoinOrderState left, JoinOrderState right, JoinGraph graph) {
        long leftRows = estimateRows(left.order, graph);
        long rightRows = estimateRows(right.order, graph);
        
        // Find largest join selectivity between any table in left and any in right
        double maxSelectivity = 0.01;
        for (String leftTable : left.order) {
            for (String rightTable : right.order) {
                double sel = graph.getJoinSelectivity(leftTable, rightTable);
                maxSelectivity = Math.max(maxSelectivity, sel);
            }
        }
        
        return (long) (leftRows * rightRows * maxSelectivity);
    }
    
    private static long estimateRows(List<String> tables, JoinGraph graph) {
        long rows = graph.getTableSize(tables.get(0));
        for (int i = 1; i < tables.size(); i++) {
            rows = (long) (rows * graph.getTableSize(tables.get(i)) * 
                          graph.getJoinSelectivity(tables.get(i - 1), tables.get(i)));
        }
        return rows;
    }
    
    private static class JoinOrderState {
        final long cost;
        final List<String> order;
        
        JoinOrderState(long cost, List<String> order) {
            this.cost = cost;
            this.order = order;
        }
    }
}