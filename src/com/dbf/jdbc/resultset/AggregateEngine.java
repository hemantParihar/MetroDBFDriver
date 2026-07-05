package com.dbf.jdbc.resultset;

import com.dbf.jdbc.parser.ast.AggregateNode;
import com.dbf.jdbc.parser.ast.GroupByNode;
import com.dbf.jdbc.parser.ast.SelectNode;
import java.io.IOException;
import java.util.*;

/**
 * Incremental aggregation - stores ONLY aggregate state, not the rows
 * Memory: O(number_of_groups) not O(total_rows)
 */
public class AggregateEngine {
    private final List<String> groupByColumns;
    private final Map<String, AggregateInfo> aggregates;
    private Map<GroupKey, AggregateState> groupStates;
    private List<Map<String, Object>> results;
    private boolean isAggregated = false;
    private int currentResultIndex = 0;
    
    /**
     * Defines what to aggregate
     */
    private static class AggregateInfo {
        final String function;
        final int columnIndex; // -1 for COUNT(*)
        final boolean distinct;
        final String alias;
        
        AggregateInfo(String function, int columnIndex, boolean distinct, String alias) {
            this.function = function.toUpperCase();
            this.columnIndex = columnIndex;
            this.distinct = distinct;
            this.alias = alias;
        }
    }
    
    /**
     * Stores ONLY the aggregate state, not the rows
     * This is the key memory optimization
     */
    
    private static class AggregateState {
        // For COUNT and SUM/AVG
        long count = 0;
        double sum = 0;
        
        // For MIN/MAX
        Object min = null;
        Object max = null;
        
        // For DISTINCT (still needed but smaller than full rows)
        Set<Object> distinctValues = null;
        
        void accumulate(Object value, boolean distinct) {
            if (value == null) return;
            
            if (distinct) {
                if (distinctValues == null) {
                    distinctValues = new HashSet<>();
                }
                if (distinctValues.add(value)) {
                    count++;
                    if (value instanceof Number) {
                        sum += ((Number) value).doubleValue();
                    }
                    updateMinMax(value);
                }
            } else {
                count++;
                if (value instanceof Number) {
                    sum += ((Number) value).doubleValue();
                }
                updateMinMax(value);
            }
        }
        
        private void updateMinMax(Object value) {
            if (min == null || compareValues(value, min) < 0) min = value;
            if (max == null || compareValues(value, max) > 0) max = value;
        }
        
        Object getResult(String function) {
            switch (function) {
                case "COUNT": return count;
                case "SUM": return sum;
                case "AVG": return count > 0 ? sum / count : 0.0;
                case "MIN": return min;
                case "MAX": return max;
                default: return null;
            }
        }
        
        @SuppressWarnings({"unchecked", "rawtypes"})
        private int compareValues(Object a, Object b) {
            if (a == null && b == null) return 0;
            if (a == null) return -1;
            if (b == null) return 1;
            if (a instanceof Number && b instanceof Number) {
                return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
            }
            if (a instanceof Comparable && b instanceof Comparable) {
                return ((Comparable) a).compareTo(b);
            }
            return a.toString().compareTo(b.toString());
        }
    }
    
    /**
     * Key for grouping - stores only the key values, not the rows
     */
    private static class GroupKey {
        private final Object[] values;
        private final int hashCode;
        
        GroupKey(Object[] values) {
            this.values = values;
            this.hashCode = Arrays.deepHashCode(values);
        }
        
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof GroupKey)) return false;
            return Arrays.deepEquals(values, ((GroupKey) o).values);
        }
        
        @Override
        public int hashCode() {
            return hashCode;
        }
    }
    
    public AggregateEngine(SelectNode selectNode, RowProjector projector) {
        this.groupByColumns = new ArrayList<>();
        this.aggregates = new LinkedHashMap<>();
        
        if (selectNode != null) {
            // Parse group by columns
            if (selectNode.getGroupBy() != null) {
                groupByColumns.addAll(selectNode.getGroupBy().getColumnNames());
            }
            
            // Parse aggregates
            if (selectNode.getAggregates() != null) {
                for (AggregateNode agg : selectNode.getAggregates()) {
                    String colName = agg.getColumn() != null ? agg.getColumn().getColumnName() : null;
                    int colIdx = colName != null ? projector.getColumnIndex(colName) : -1;
                    String alias = agg.getAlias() != null ? agg.getAlias() : 
                                   agg.getFunction() + "(" + (colName != null ? colName : "*") + ")";
                    aggregates.put(alias, new AggregateInfo(agg.getFunction(), colIdx, agg.isDistinct(), alias));
                }
            }
        }
    }
    
    public boolean hasAggregation() {
        return !aggregates.isEmpty();
    }
    
    /**
     * Incrementally aggregate rows - stores ONLY aggregate state per group
     * Memory: O(number_of_distinct_groups), NOT O(total_rows)
     */
    public List<Map<String, Object>> aggregate(List<Object[]> rows, RowProjector projector) throws IOException {
        if (!hasAggregation()) {
            return null;
        }
        
        // Initialize group states map
        groupStates = new HashMap<>();
        
        // Process each row - update aggregate state incrementally
        for (Object[] row : rows) {
            // Extract group key (only the grouping columns)
            Object[] keyValues = new Object[groupByColumns.size()];
            for (int i = 0; i < groupByColumns.size(); i++) {
                int colIdx = projector.getColumnIndex(groupByColumns.get(i));
                keyValues[i] = (colIdx >= 0 && colIdx < row.length) ? row[colIdx] : null;
            }
            GroupKey key = new GroupKey(keyValues);
            
            // Get or create aggregate state for this group
            AggregateState state = groupStates.get(key);
            if (state == null) {
                state = new AggregateState();
                groupStates.put(key, state);
            }
            
            // Update aggregate state with current row values
            for (Map.Entry<String, AggregateInfo> entry : aggregates.entrySet()) {
                AggregateInfo agg = entry.getValue();
                Object value = null;
                if (agg.columnIndex >= 0 && agg.columnIndex < row.length) {
                    value = row[agg.columnIndex];
                }
                state.accumulate(value, agg.distinct);
            }
        }
        
        // Build result rows from aggregate states
        results = new ArrayList<>();
        for (Map.Entry<GroupKey, AggregateState> entry : groupStates.entrySet()) {
            Map<String, Object> resultRow = new LinkedHashMap<>();
            
            // Add group by columns
            GroupKey key = entry.getKey();
            for (int i = 0; i < groupByColumns.size(); i++) {
                resultRow.put(groupByColumns.get(i), key.values[i]);
            }
            
            // Add aggregate results
            AggregateState state = entry.getValue();
            for (Map.Entry<String, AggregateInfo> aggEntry : aggregates.entrySet()) {
                resultRow.put(aggEntry.getKey(), state.getResult(aggEntry.getValue().function));
            }
            
            results.add(resultRow);
        }
        
        // Free group states to help GC
        groupStates = null;
        isAggregated = true;
        
        return results;
    }
    
    public boolean isAggregated() {
        return isAggregated;
    }
    
    public List<Map<String, Object>> getResults() {
        return results;
    }
    
    public void reset() {
        isAggregated = false;
        results = null;
        groupStates = null;
        currentResultIndex = 0;
    }
    
    public boolean hasNext() {
        return isAggregated && results != null && currentResultIndex < results.size();
    }
    
    public Map<String, Object> next() {
        if (!hasNext()) return null;
        return results.get(currentResultIndex++);
    }
}