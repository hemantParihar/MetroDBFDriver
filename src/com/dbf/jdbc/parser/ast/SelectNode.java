package com.dbf.jdbc.parser.ast;

import java.util.ArrayList;
import java.util.List;

public class SelectNode extends ASTNode {
    private List<ColumnNode> columns = new ArrayList<>();
    private List<AggregateNode> aggregates = new ArrayList<>();
    // Every select item (ColumnNode or AggregateNode) in source order, so the
    // aggregate engine can emit output columns in the order they were written.
    private final List<ASTNode> selectItems = new ArrayList<>();
    private FromNode from;
    private JoinNode join;
    private final List<JoinNode> joins = new ArrayList<>();
    private WhereNode where;
    private GroupByNode groupBy;
    private HavingNode having;
    private OrderByNode orderBy;
    private int limit = -1; // -1 = no limit (TOP n / LIMIT n)

    public SelectNode() {
        super(null);
    }

    /** Row limit from TOP n / LIMIT n, or -1 for no limit. */
    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }
    
    public void addColumn(ColumnNode column) {
        columns.add(column);
        selectItems.add(column);
        addChild(column);
    }

    public void addAggregate(AggregateNode aggregate) {
        aggregates.add(aggregate);
        selectItems.add(aggregate);
        addChild(aggregate);
    }

    /** All select items (ColumnNode / AggregateNode) in source order. */
    public List<ASTNode> getSelectItems() {
        return selectItems;
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

    /** Ordered list of joins for multi-table (left-deep) queries. */
    public void addJoin(JoinNode joinNode) {
        joins.add(joinNode);
        if (join == null) {
            join = joinNode; // keep the single-join accessor working
        }
        addChild(joinNode);
    }

    public List<JoinNode> getJoins() {
        return joins;
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
        if (aggregates != null && !aggregates.isEmpty()) {
            return true;
        }
        // Aggregates may also be nested inside a computed select item, e.g. MAX(x)+1.
        for (ColumnNode c : columns) {
            if (c.isExpression() && containsAggregate(c.getExpression())) {
                return true;
            }
        }
        return false;
    }

    /** Recursively checks whether an expression tree contains an aggregate call. */
    public static boolean containsAggregate(ExpressionNode n) {
        if (n == null) {
            return false;
        }
        if (n.isAggregate()) {
            return true;
        }
        if (containsAggregate(n.getLeft()) || containsAggregate(n.getRight())
                || containsAggregate(n.getAggregateArg())) {
            return true;
        }
        if (n.getArguments() != null) {
            for (ExpressionNode a : n.getArguments()) {
                if (containsAggregate(a)) {
                    return true;
                }
            }
        }
        return false;
    }
}