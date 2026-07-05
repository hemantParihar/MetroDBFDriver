package com.dbf.jdbc.parser.ast;

import java.util.ArrayList;
import java.util.List;

public class SelectNode extends ASTNode {
    private List<ColumnNode> columns = new ArrayList<>();
    private List<AggregateNode> aggregates = new ArrayList<>();
    private FromNode from;
    private JoinNode join;
    private WhereNode where;
    private GroupByNode groupBy;
    private HavingNode having;
    private OrderByNode orderBy;
    
    public SelectNode() {
        super(null);
    }
    
    public void addColumn(ColumnNode column) {
        columns.add(column);
        addChild(column);
    }
    
    public void addAggregate(AggregateNode aggregate) {
        aggregates.add(aggregate);
        addChild(aggregate);
    }
    
    public List<ColumnNode> getColumns() {
        return columns;
    }
    
    public List<AggregateNode> getAggregates() {
        return aggregates;
    }
    
    public void setFrom(FromNode from) {
        this.from = from;
        addChild(from);
    }
    
    public FromNode getFrom() {
        return from;
    }
    
    public void setJoin(JoinNode join) {
        this.join = join;
        if (join != null) addChild(join);
    }
    
    public JoinNode getJoin() {
        return join;
    }
    
    public void setWhere(WhereNode where) {
        this.where = where;
        if (where != null) addChild(where);
    }
    
    public WhereNode getWhere() {
        return where;
    }
    
    public void setGroupBy(GroupByNode groupBy) {
        this.groupBy = groupBy;
        if (groupBy != null) addChild(groupBy);
    }
    
    public GroupByNode getGroupBy() {
        return groupBy;
    }
    
    public void setHaving(HavingNode having) {
        this.having = having;
        if (having != null) addChild(having);
    }
    
    public HavingNode getHaving() {
        return having;
    }
    
    public void setOrderBy(OrderByNode orderBy) {
        this.orderBy = orderBy;
        if (orderBy != null) addChild(orderBy);
    }
    
    public OrderByNode getOrderBy() {
        return orderBy;
    }
    
    public boolean hasAggregates() {
        return aggregates != null && !aggregates.isEmpty();
    }
}