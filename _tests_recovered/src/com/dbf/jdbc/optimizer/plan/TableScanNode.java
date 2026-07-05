package com.dbf.jdbc.optimizer.plan;

/**
 * Table scan node
 */
public class TableScanNode extends LogicalPlanNode {
    private final String tableName;
    private final String alias;
    private final long tableSize;
    private boolean useIndex = false;
    private String indexName;
    private String indexColumn;
    
    public TableScanNode(String tableName, String alias, long tableSize) {
        this.tableName = tableName;
        this.alias = alias;
        this.tableSize = tableSize;
        setEstimatedRows(tableSize);
        setEstimatedCost(tableSize);
    }
    
    public void setIndexScan(String indexName, String indexColumn) {
        this.useIndex = true;
        this.indexName = indexName;
        this.indexColumn = indexColumn;
        // Index scan is O(log n)
        setEstimatedCost(Math.log(tableSize) * 10);
        setEstimatedRows(tableSize / 100); // Index reduces rows
    }
    
    public String getTableName() { return tableName; }
    public String getAlias() { return alias; }
    public long getTableSize() { return tableSize; }
    public boolean useIndex() { return useIndex; }
    public String getIndexName() { return indexName; }
    public String getIndexColumn() { return indexColumn; }
    
    @Override
    public String getNodeName() { return "TableScan"; }
    
    @Override
    public void accept(PlanVisitor visitor) {
        visitor.visit(this);
    }
}
