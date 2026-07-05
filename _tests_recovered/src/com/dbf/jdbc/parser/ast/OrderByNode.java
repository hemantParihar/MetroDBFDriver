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
        
        public OrderItem(String columnName, boolean ascending) {
            super(null);
            this.columnName = columnName;
            this.ascending = ascending;
        }
        
        public String getColumnName() {
            return columnName;
        }
        
        public boolean isAscending() {
            return ascending;
        }
    }
}