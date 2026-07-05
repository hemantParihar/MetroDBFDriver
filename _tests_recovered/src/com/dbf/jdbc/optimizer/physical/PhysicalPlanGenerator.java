package com.dbf.jdbc.optimizer.physical;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;

import com.dbf.jdbc.dbf.DBFReader;
import com.dbf.jdbc.execution.LimitOperator;
import com.dbf.jdbc.execution.streaming.ExternalSortOperator;
import com.dbf.jdbc.execution.streaming.IndexScanOperator;
import com.dbf.jdbc.execution.streaming.IndexSeekOperator;
import com.dbf.jdbc.execution.streaming.LimitOperatorAdapter;
import com.dbf.jdbc.execution.streaming.RowStream;
import com.dbf.jdbc.execution.streaming.StreamingFilterOperator;
import com.dbf.jdbc.execution.streaming.StreamingTableScanOperator;
import com.dbf.jdbc.index.MDXIndex;
import com.dbf.jdbc.index.NDXIndex;
import com.dbf.jdbc.join.HashJoinOperator;
import com.dbf.jdbc.join.SortMergeJoinOperator;
import com.dbf.jdbc.optimizer.plan.AggregateNode;
import com.dbf.jdbc.optimizer.plan.FilterNode;
import com.dbf.jdbc.optimizer.plan.JoinNode;
import com.dbf.jdbc.optimizer.plan.LimitNode;
import com.dbf.jdbc.optimizer.plan.LogicalPlanNode;
import com.dbf.jdbc.optimizer.plan.ProjectionNode;
import com.dbf.jdbc.optimizer.plan.SortNode;
import com.dbf.jdbc.optimizer.plan.TableScanNode;

/**
 * Converts logical plan to physical execution operators
 */
public class PhysicalPlanGenerator {
    private final String basePath;
    private final String charset;
    
    public PhysicalPlanGenerator(String basePath, String charset) {
        this.basePath = basePath;
        this.charset = charset;
    }
    
    public RowStream generate(LogicalPlanNode plan) throws IOException, SQLException {
        if (plan instanceof TableScanNode) {
            return generateScan((TableScanNode) plan);
        } else if (plan instanceof FilterNode) {
            return generateFilter((FilterNode) plan);
        } else if (plan instanceof ProjectionNode) {
            return generateProjection((ProjectionNode) plan);
        } else if (plan instanceof SortNode) {
            return generateSort((SortNode) plan);
        } else if (plan instanceof JoinNode) {
            return generateJoin((JoinNode) plan);
        } else if (plan instanceof AggregateNode) {
            return generateAggregate((AggregateNode) plan);
        } else if (plan instanceof LimitNode) {
            return generateLimit((LimitNode) plan);
        }
        
        throw new SQLException("Unknown plan node: " + plan.getClass());
    }
    
//    private RowStream generateScan(TableScanNode scan) throws IOException, SQLException {
//        String filePath = basePath + "/" + scan.getTableName() + ".dbf";
//        com.dbf.jdbc.dbf.DBFReader reader = new com.dbf.jdbc.dbf.DBFReader(
//            filePath, java.nio.charset.Charset.forName(charset));
//        
//        if (scan.useIndex()) {
//            // Use index scan - need to implement IndexScanOperator
//            // For now, fall back to table scan
//        }
//        
//        return new StreamingTableScanOperator(reader, scan.getTableName(), scan.getAlias());
//    }
    
 // Add to PhysicalPlanGenerator.java

    private RowStream generateScan(TableScanNode scan) throws IOException, SQLException {
        String filePath = basePath + "/" + scan.getTableName() + ".dbf";
        DBFReader reader = new DBFReader(filePath, java.nio.charset.Charset.forName(charset));
        
        // Check if we should use index scan
        if (scan.useIndex() && scan.getIndexName() != null) {
            String indexColumn = scan.getIndexColumn();
            Object indexValue = getIndexValueFromContext(scan);
            
            // Try to load NDX index
            String ndxPath = basePath + "/" + scan.getTableName() + ".ndx";
            File ndxFile = new File(ndxPath);
            if (ndxFile.exists()) {
                try {
                    NDXIndex ndxIndex = new NDXIndex(ndxPath);
                    ndxIndex.load();
                    // No open() call needed - initialization happens in next()
                    return new IndexSeekOperator(reader, ndxIndex, indexValue.toString());
                } catch (Exception e) {
                    // Fall back to table scan
                }
            }
            
            // Try MDX index
            String mdxPath = basePath + "/" + scan.getTableName() + ".mdx";
            File mdxFile = new File(mdxPath);
            if (mdxFile.exists()) {
                try {
                    MDXIndex mdxIndex = new MDXIndex(mdxPath);
                    // No open() call needed - initialization happens in next()
                    return new IndexScanOperator(reader, scan.getTableName(), scan.getAlias(),
                        mdxIndex, scan.getIndexName(), indexColumn, indexValue);
                } catch (Exception e) {
                    // Fall back to table scan
                }
            }
        }
        
        // Fall back to full table scan
        return new StreamingTableScanOperator(reader, scan.getTableName(), scan.getAlias());
    }

    private Object getIndexValueFromContext(TableScanNode scan) {
        // Extract the value from the filter condition
        // This requires traversing the plan tree
        // Simplified for now
        return null;
    }
    
    private RowStream generateFilter(FilterNode filter) throws IOException, SQLException {
        RowStream input = generate(filter.getChildren()[0]);
        return new StreamingFilterOperator(input, filter.getCondition());
    }
    
    private RowStream generateProjection(ProjectionNode projection) throws IOException, SQLException {
        RowStream input = generate(projection.getChildren()[0]);
        // Need to implement StreamingProjectionOperator
        return input;
    }
    
    private RowStream generateSort(SortNode sort) throws IOException, SQLException {
        RowStream input = generate(sort.getChildren()[0]);
        // Create OrderByNode from sort info
        com.dbf.jdbc.parser.ast.OrderByNode orderBy = new com.dbf.jdbc.parser.ast.OrderByNode();
        for (int i = 0; i < sort.getSortColumns().size(); i++) {
            orderBy.addItem(new com.dbf.jdbc.parser.ast.OrderByNode.OrderItem(
                sort.getSortColumns().get(i), sort.getAscending().get(i)));
        }
        return new ExternalSortOperator(input, orderBy, input.getColumnNames());
    }
    
    private RowStream generateJoin(JoinNode join) throws IOException, SQLException {
        RowStream left = generate(join.getChildren()[0]);
        RowStream right = generate(join.getChildren()[1]);
        
        String algorithm = join.getJoinAlgorithm();
        HashJoinOperator.JoinType joinType = convertJoinType(join.getJoinType());
        
        if ("HashJoin".equals(algorithm)) {
            return new HashJoinOperator(left, right, 
                join.getLeftColumn(), join.getRightColumn(), joinType);
        } else if ("IndexNestedLoop".equals(algorithm)) {
            // Need IndexNestedLoopJoinOperator
            return new HashJoinOperator(left, right, 
                join.getLeftColumn(), join.getRightColumn(), joinType);
        } else {
            return new SortMergeJoinOperator(left, right,
                join.getLeftColumn(), join.getRightColumn(), 
                convertSortMergeJoinType(join.getJoinType()));
        }
    }
    
    private RowStream generateAggregate(AggregateNode aggregate) throws IOException, SQLException {
        RowStream input = generate(aggregate.getChildren()[0]);
        // Need to create SelectNode from aggregate info
        // For now, return input
        return input;
    }
    
//    private RowStream generateLimit(LimitNode limit) throws IOException, SQLException {
//        RowStream input = generate(limit.getChildren()[0]);
//        // Use StreamingLimitOperator instead of LimitOperator
//        return new StreamingLimitOperator(input, limit.getLimit());
//    }
    private RowStream generateLimit(LimitNode limit) throws IOException, SQLException {
        RowStream input = generate(limit.getChildren()[0]);
        
        // Create Operator-based LimitOperator
        LimitOperator limitOp = new LimitOperator(limit.getLimit());
        limitOp.addChild(null); // Need to set child appropriately
        
        // Wrap in adapter
        return new LimitOperatorAdapter(limitOp, input);
    }
    
    private HashJoinOperator.JoinType convertJoinType(JoinNode.JoinType type) {
        switch (type) {
            case INNER: return HashJoinOperator.JoinType.INNER;
            case LEFT: return HashJoinOperator.JoinType.LEFT;
            case RIGHT: return HashJoinOperator.JoinType.RIGHT;
            case FULL: return HashJoinOperator.JoinType.FULL;
            default: return HashJoinOperator.JoinType.INNER;
        }
    }
    
    private SortMergeJoinOperator.JoinType convertSortMergeJoinType(JoinNode.JoinType type) {
        switch (type) {
            case INNER: return SortMergeJoinOperator.JoinType.INNER;
            case LEFT: return SortMergeJoinOperator.JoinType.LEFT;
            case RIGHT: return SortMergeJoinOperator.JoinType.RIGHT;
            case FULL: return SortMergeJoinOperator.JoinType.FULL;
            default: return SortMergeJoinOperator.JoinType.INNER;
        }
    }
}