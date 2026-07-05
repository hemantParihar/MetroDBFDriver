// FromNode.java
package com.dbf.jdbc.parser.ast;

public class FromNode extends ASTNode {
    private String tableName;
    private String alias;
    
    public FromNode() {
        super(null);
    }
    
    public void setTableName(String tableName) {
        this.tableName = tableName;
    }
    
    public String getTableName() {
        return tableName;
    }
    
    public String getAlias() {
        return alias;
    }
    
    public void setAlias(String alias) {
        this.alias = alias;
    }
}