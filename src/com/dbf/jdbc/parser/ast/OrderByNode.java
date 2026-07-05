// OrderByNode.java
package com.dbf.jdbc.parser.ast;

import java.util.ArrayList;
import java.util.List;

public class OrderByNode extends ASTNode {
    private List<OrderItem> items = new ArrayList<>();
    
    public OrderByNode() {
        super(null);
    }
    
    public void addItem(OrderItem item) {
        items.add(item);
        addChild(item);
    }
    
    public List<OrderItem> getItems() {
        return items;
    }
    
    public static class OrderItem extends ASTNode {
        private String columnName;
        private boolean ascending = true;
        private ExpressionNode expression; // non-null for ORDER BY <expression>
        private AggregateNode aggregate;   // non-null for ORDER BY MAX(col), etc.

        public OrderItem(String columnName, boolean ascending) {
            super(null);
            this.columnName = columnName;
            this.ascending = ascending;
        }

        public OrderItem(ExpressionNode expression, boolean ascending) {
            super(null);
            this.expression = expression;
            this.ascending = ascending;
        }

        public OrderItem(AggregateNode aggregate, boolean ascending) {
            super(null);
            this.aggregate = aggregate;
            this.ascending = ascending;
        }

        public AggregateNode getAggregate() {
            return aggregate;
        }

        public boolean isAggregate() {
            return aggregate != null;
        }

        public String getColumnName() {
            return columnName;
        }

        public boolean isAscending() {
            return ascending;
        }

        public ExpressionNode getExpression() {
            return expression;
        }

        public boolean isExpression() {
            return expression != null;
        }
    }
}