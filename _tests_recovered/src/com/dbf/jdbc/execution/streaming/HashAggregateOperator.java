package com.dbf.jdbc.execution.streaming;

import com.dbf.jdbc.parser.ast.AggregateNode;
import com.dbf.jdbc.parser.ast.SelectNode;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;

/**
 * Streaming Hash Aggregate - NEVER stores full rows
 * Stores only aggregate state per group
 * Memory: O(number_of_groups), NOT O(total_rows)
 */
public class HashAggregateOperator implements RowStream {
    private final RowStream input;
    private final int[] groupByIndexes;
    private final String[] groupByColumns;
    private final List<AggregateInfo> aggregates;
    private final String[] outputColumnNames;
    private final int[] outputColumnTypes;
    
    private Map<GroupKey, AggregateState> groupStates;
    private Iterator<Map.Entry<GroupKey, AggregateState>> resultIterator;
    private boolean aggregated = false;
    private boolean closed = false;
    private long totalGroups = 0;
    
    /**
     * Defines what to aggregate
     */
    private static class AggregateInfo {
        final String function;
        final int columnIndex;
        final boolean distinct;
        final String outputName;
        
        AggregateInfo(String function, int columnIndex, boolean distinct, String outputName) {
            this.function = function.toUpperCase();
            this.columnIndex = columnIndex;
            this.distinct = distinct;
            this.outputName = outputName;
        }
    }
    
    /**
     * Stores ONLY aggregate state, not the rows
     * This is the key memory optimization
     */
    private static class AggregateState {
        long count = 0;
        double sum = 0;
        Object min = null;
        Object max = null;
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
     * Key for grouping
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
    
    public HashAggregateOperator(RowStream input, SelectNode selectNode, String[] columnNames) {
        this(input, selectNode, columnNames, 50 * 1024 * 1024); // Default 50MB
    }
    
    public HashAggregateOperator(RowStream input, SelectNode selectNode, String[] columnNames, long memoryLimit) {
        this.input = input;
        
        // Parse group by columns
        List<Integer> groupIdx = new ArrayList<>();
        List<String> groupCols = new ArrayList<>();
        if (selectNode.getGroupBy() != null) {
            for (String colName : selectNode.getGroupBy().getColumnNames()) {
                int idx = findColumnIndex(colName, columnNames);
                if (idx >= 0) {
                    groupIdx.add(idx);
                    groupCols.add(colName);
                }
            }
        }
        
        // Convert to arrays
        this.groupByIndexes = new int[groupIdx.size()];
        for (int i = 0; i < groupIdx.size(); i++) {
            this.groupByIndexes[i] = groupIdx.get(i);
        }
        this.groupByColumns = groupCols.toArray(new String[0]);
        
        // Parse aggregates
        this.aggregates = new ArrayList<>();
        List<String> outNames = new ArrayList<>();
        List<Integer> outTypes = new ArrayList<>();
        
        // Add group by columns to output
        for (String col : groupCols) {
            outNames.add(col);
            outTypes.add(java.sql.Types.VARCHAR);
        }
        
        // Add aggregates to output
        if (selectNode.getAggregates() != null) {
            for (AggregateNode agg : selectNode.getAggregates()) {
                String colName = agg.getColumn() != null ? agg.getColumn().getColumnName() : null;
                int colIdx = colName != null ? findColumnIndex(colName, columnNames) : -1;
                String outputName = agg.getAlias() != null ? agg.getAlias() : 
                                   agg.getFunction() + "(" + (colName != null ? colName : "*") + ")";
                aggregates.add(new AggregateInfo(agg.getFunction(), colIdx, agg.isDistinct(), outputName));
                outNames.add(outputName);
                outTypes.add(getAggregateType(agg.getFunction()));
            }
        }
        
        this.outputColumnNames = outNames.toArray(new String[0]);
        this.outputColumnTypes = new int[outTypes.size()];
        for (int i = 0; i < outTypes.size(); i++) {
            this.outputColumnTypes[i] = outTypes.get(i);
        }
    }
    
    private int findColumnIndex(String columnName, String[] columnNames) {
        for (int i = 0; i < columnNames.length; i++) {
            if (columnNames[i].equalsIgnoreCase(columnName)) {
                return i;
            }
        }
        return -1;
    }
    
    private int getAggregateType(String function) {
        switch (function.toUpperCase()) {
            case "COUNT": return java.sql.Types.BIGINT;
            case "SUM": return java.sql.Types.DOUBLE;
            case "AVG": return java.sql.Types.DOUBLE;
            case "MAX": 
            case "MIN": return java.sql.Types.VARCHAR;
            default: return java.sql.Types.VARCHAR;
        }
    }
    
    @Override
    public Object[] next() throws IOException, SQLException {
        if (!aggregated) {
            performIncrementalAggregation();
        }
        
        if (resultIterator == null || !resultIterator.hasNext()) {
            return null;
        }
        
        Map.Entry<GroupKey, AggregateState> entry = resultIterator.next();
        List<Object> result = new ArrayList<>();
        
        // Add group by values
        for (Object val : entry.getKey().values) {
            result.add(val);
        }
        
        // Add aggregate results
        AggregateState state = entry.getValue();
        for (AggregateInfo agg : aggregates) {
            result.add(state.getResult(agg.function));
        }
        
        return result.toArray();
    }
    
    /**
     * Perform incremental aggregation - stores ONLY aggregate state, not rows
     * Memory: O(number_of_distinct_groups), NOT O(total_rows)
     */
    private void performIncrementalAggregation() throws IOException, SQLException {
        groupStates = new HashMap<>();
        
        Object[] row;
        long rowsProcessed = 0;
        
        while ((row = input.next()) != null) {
            rowsProcessed++;
            
            // Extract group key (only the grouping columns, not the full row)
            Object[] keyValues = new Object[groupByIndexes.length];
            for (int i = 0; i < groupByIndexes.length; i++) {
                int idx = groupByIndexes[i];
                keyValues[i] = (idx < row.length) ? row[idx] : null;
            }
            GroupKey key = new GroupKey(keyValues);
            
            // Get or create aggregate state for this group
            AggregateState state = groupStates.get(key);
            if (state == null) {
                state = new AggregateState();
                groupStates.put(key, state);
                totalGroups++;
            }
            
            // Update aggregate state with current row values
            for (AggregateInfo agg : aggregates) {
                Object value = null;
                if (agg.columnIndex >= 0 && agg.columnIndex < row.length) {
                    value = row[agg.columnIndex];
                }
                state.accumulate(value, agg.distinct);
            }
            
            // Optional: Log progress for large datasets
            if (rowsProcessed % 100000 == 0) {
                System.out.println("Aggregated " + rowsProcessed + " rows into " + totalGroups + " groups");
            }
        }
        
        resultIterator = groupStates.entrySet().iterator();
        aggregated = true;
        
        System.out.println("Aggregation complete: " + rowsProcessed + " rows  " + totalGroups + " groups");
    }
    
    @Override
    public String[] getColumnNames() {
        return outputColumnNames;
    }
    
    @Override
    public int[] getColumnTypes() {
        return outputColumnTypes;
    }
    
    @Override
    public void reset() throws IOException, SQLException {
        aggregated = false;
        groupStates = null;
        resultIterator = null;
        totalGroups = 0;
        input.reset();
    }
    
    @Override
    public boolean supportsReset() {
        return false;
    }
    
    @Override
    public long estimateRowCount() {
        return totalGroups > 0 ? totalGroups : input.estimateRowCount() / 10;
    }
    
    public long getTotalGroups() {
        return totalGroups;
    }
    
    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            groupStates = null;
            resultIterator = null;
            input.close();
        }
    }
}