package com.dbf.jdbc.execution;

import java.io.IOException;
import java.nio.charset.Charset;
import java.sql.SQLException;
import java.util.List;

import com.dbf.jdbc.dbf.DBFField;
import com.dbf.jdbc.dbf.DBFReader;
import com.dbf.jdbc.parser.ast.SelectNode;

/**
 * Entry point for query execution - builds and executes execution plans
 */
public class QueryExecutor {
    private final Charset charset;
    
    public QueryExecutor(Charset charset) {
        this.charset = charset;
    }
    
    /**
     * Execute a SELECT query and return the operator that produces results
     */
    public Operator executeSelect(SelectNode selectNode, String tablePath) throws SQLException, IOException {
        ExecutionPlan plan = buildExecutionPlan(selectNode, tablePath);
        Operator root = plan.getRootOperator();
        root.open();
        return root;
    }
    
    /**
     * Build an optimized execution plan from a SELECT query
     */
    public ExecutionPlan buildExecutionPlan(SelectNode selectNode, String tablePath) throws SQLException, IOException {
        // Create table scan operator
        DBFReader reader = new DBFReader(tablePath, charset);
        TableScanOperator scan = new TableScanOperator(reader, 
            selectNode.getFrom().getTableName(), 
            selectNode.getFrom().getAlias());
        
        Operator current = scan;
        List<String> currentColumnNames = getColumnNames(reader.getHeader().getFields());
        
        // Add filter if WHERE clause exists
        if (selectNode.getWhere() != null) {
            FilterOperator filter = new FilterOperator(selectNode.getWhere().getCondition(), currentColumnNames);
            filter.addChild(current);
            current = filter;
        }
        
        // Add aggregation if needed
        AggregationOperator aggregator = new AggregationOperator(selectNode, currentColumnNames);
        if (aggregator.hasAggregation()) {
            aggregator.addChild(current);
            current = aggregator;
            // After aggregation, column names change
            currentColumnNames = getAggregatedColumnNames(selectNode);
        }
        
        // Add sort if ORDER BY exists
        if (selectNode.getOrderBy() != null && !aggregator.hasAggregation()) {
            SortOperator sorter = new SortOperator(selectNode.getOrderBy(), currentColumnNames);
            sorter.addChild(current);
            current = sorter;
        }
        
        // Add projection for final output
        ProjectionOperator projection = new ProjectionOperator(reader.getHeader().getFields(), selectNode);
        projection.addChild(current);
        
        ExecutionPlan plan = new ExecutionPlan(selectNode, projection);
        plan.setEstimatedCost(estimateTotalCost(scan));
        
        return plan;
    }
    
    private List<String> getColumnNames(List<DBFField> fields) {
        List<String> names = new java.util.ArrayList<>();
        for (DBFField field : fields) {
            names.add(field.getName());
        }
        return names;
    }
    
    private List<String> getAggregatedColumnNames(SelectNode selectNode) {
        List<String> names = new java.util.ArrayList<>();
        if (selectNode.getGroupBy() != null) {
            names.addAll(selectNode.getGroupBy().getColumnNames());
        }
        for (com.dbf.jdbc.parser.ast.AggregateNode agg : selectNode.getAggregates()) {
            names.add(agg.getFunction() + "(" + 
                (agg.getColumn() != null ? agg.getColumn().getColumnName() : "*") + ")");
        }
        return names;
    }
    
    private long estimateTotalCost(TableScanOperator scan) throws SQLException, IOException {
        return scan.estimateCost();
    }
    
    /**
     * Execute a join query between two tables
     */
    public Operator executeJoin(SelectNode selectNode, String leftPath, String rightPath) throws SQLException, IOException {
        DBFReader leftReader = new DBFReader(leftPath, charset);
        DBFReader rightReader = new DBFReader(rightPath, charset);
        
        TableScanOperator leftScan = new TableScanOperator(leftReader, 
            selectNode.getFrom().getTableName(), 
            selectNode.getFrom().getAlias());
        TableScanOperator rightScan = new TableScanOperator(rightReader, 
            selectNode.getJoin().getRightTable(), 
            selectNode.getJoin().getRightTableAlias());
        
        JoinOperator join = new JoinOperator(selectNode.getJoin(), 
            leftReader.getHeader().getFields(), 
            rightReader.getHeader().getFields());
        join.addChild(leftScan);
        join.addChild(rightScan);
        
        // Add filter after join if needed
        Operator current = join;
        
        // Add projection
        List<DBFField> allFields = new java.util.ArrayList<>();
        allFields.addAll(leftReader.getHeader().getFields());
        allFields.addAll(rightReader.getHeader().getFields());
        ProjectionOperator projection = new ProjectionOperator(allFields, selectNode);
        projection.addChild(current);
        
        projection.open();
        return projection;
    }
}