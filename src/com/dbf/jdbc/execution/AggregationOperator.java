package com.dbf.jdbc.execution;

import com.dbf.jdbc.parser.ast.AggregateNode;
import com.dbf.jdbc.parser.ast.GroupByNode;
import com.dbf.jdbc.parser.ast.SelectNode;
import com.dbf.jdbc.resultset.TypeConverter;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;

/**
 * Performs GROUP BY and aggregation (COUNT, SUM, AVG, MAX, MIN)
 */
public class AggregationOperator extends Operator {
    private final List<String> groupByColumns;
    private final Map<String, AggregateInfo> aggregates;
    private final List<String> columnNames;
    
    private Map<GroupKey, Accumulator> groups;
    private Iterator<Map.Entry<GroupKey, Accumulator>> resultIterator;
    private boolean isAggregated = false;
    
    private static class AggregateInfo {
        final String function;
        final String columnName;
        
        AggregateInfo(String function, String columnName) {
            this.function = function.toUpperCase();
            this.columnName = columnName;
        }
    }
    
    private static class Accumulator {
        long count = 0;
        double sum = 0;
        Object max = null;
        Object min = null;
        List<Object> values = new ArrayList<>(); // For DISTINCT
        
        void accumulate(Object value, boolean distinct) {
            if (value == null) return;
            
            if (distinct) {
                if (!values.contains(value)) {
                    values.add(value);
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
            if (max == null || compareValues(value, max) > 0) max = value;
            if (min == null || compareValues(value, min) < 0) min = value;
        }
        
        Object getResult(String function) {
            switch (function) {
                case "COUNT": return count;
                case "SUM": return sum;
                case "AVG": return count > 0 ? sum / count : 0.0;
                case "MAX": return max;
                case "MIN": return min;
                default: return null;
            }
        }
        
        @SuppressWarnings({"unchecked", "rawtypes"})
        private int compareValues(Object a, Object b) {
            if (a == null && b == null) return 0;
            if (a == null) return -1;
            if (b == null) return 1;
            if (a instanceof Comparable && b instanceof Comparable) {
                return ((Comparable) a).compareTo(b);
            }
            return a.toString().compareTo(b.toString());
        }
    }
    
    private static class GroupKey {
        private final List<Object> parts;
        
        GroupKey(List<Object> parts) {
            this.parts = parts;
        }
        
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof GroupKey)) return false;
            return parts.equals(((GroupKey) o).parts);
        }
        
        @Override
        public int hashCode() {
            return parts.hashCode();
        }
    }
    
    public AggregationOperator(SelectNode selectNode, List<String> columnNames) {
        this.columnNames = columnNames;
        this.groupByColumns = new ArrayList<>();
        this.aggregates = new LinkedHashMap<>();
        
        if (selectNode != null) {
            if (selectNode.getGroupBy() != null) {
                groupByColumns.addAll(selectNode.getGroupBy().getColumnNames());
            }
            
            if (selectNode.getAggregates() != null) {
                for (AggregateNode agg : selectNode.getAggregates()) {
                    String colName = agg.getColumn() != null ? agg.getColumn().getColumnName() : "*";
                    aggregates.put(agg.getFunction() + "(" + colName + ")", 
                                   new AggregateInfo(agg.getFunction(), colName));
                }
            }
        }
    }
    
    public boolean hasAggregation() {
        return !aggregates.isEmpty();
    }
    
    @Override
    public void open() throws SQLException, IOException {
        super.open();
        if (children.isEmpty()) {
            throw new SQLException("AggregationOperator requires a child operator");
        }
        
        groups = new HashMap<>();
        
        // Collect and aggregate all rows
        Object[] row;
        while ((row = children.get(0).next()) != null) {
            GroupKey key = extractGroupKey(row);
            Accumulator acc = groups.computeIfAbsent(key, k -> new Accumulator());
            
            for (Map.Entry<String, AggregateInfo> aggEntry : aggregates.entrySet()) {
                String colName = aggEntry.getValue().columnName;
                int colIdx = findColumnIndex(colName);
                Object value = (colIdx >= 0 && colIdx < row.length) ? row[colIdx] : null;
                acc.accumulate(value, false);
            }
        }
        
        resultIterator = groups.entrySet().iterator();
        isAggregated = true;
    }
    
    private GroupKey extractGroupKey(Object[] row) {
        List<Object> parts = new ArrayList<>();
        for (String colName : groupByColumns) {
            int colIdx = findColumnIndex(colName);
            Object value = (colIdx >= 0 && colIdx < row.length) ? row[colIdx] : null;
            parts.add(value);
        }
        return new GroupKey(parts);
    }
    
    private int findColumnIndex(String columnName) {
        for (int i = 0; i < columnNames.size(); i++) {
            if (columnNames.get(i).equalsIgnoreCase(columnName)) {
                return i;
            }
        }
        return -1;
    }
    
    @Override
    public Object[] next() throws SQLException, IOException {
        if (!isAggregated || resultIterator == null || !resultIterator.hasNext()) {
            return null;
        }
        
        Map.Entry<GroupKey, Accumulator> entry = resultIterator.next();
        List<Object> result = new ArrayList<>();
        
        // Add group by columns
        for (Object part : entry.getKey().parts) {
            result.add(part);
        }
        
        // Add aggregates
        for (Map.Entry<String, AggregateInfo> aggEntry : aggregates.entrySet()) {
            result.add(entry.getValue().getResult(aggEntry.getValue().function));
        }
        
        incrementRowsProcessed();
        return result.toArray();
    }
    
    @Override
    public void reset() throws SQLException, IOException {
        super.reset();
        isAggregated = false;
        groups = null;
        resultIterator = null;
    }
    
    @Override
    public String getOperatorName() {
        return "Aggregation";
    }
    
    @Override
    public String getOperatorDetails() {
        StringBuilder sb = new StringBuilder();
        if (!groupByColumns.isEmpty()) {
            sb.append("group by=").append(groupByColumns).append(" ");
        }
        if (!aggregates.isEmpty()) {
            sb.append("aggregates=").append(aggregates.keySet());
        }
        return sb.toString();
    }
    
    @Override
    public long estimateCost() throws SQLException, IOException {
        if (children.isEmpty()) return 0;
        long childCost = children.get(0).estimateCost();
        // Aggregation reduces rows to number of groups
        return Math.min(childCost, 1000);
    }
}