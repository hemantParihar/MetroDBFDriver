// ColumnNode.java
package com.dbf.jdbc.parser.ast;

import com.dbf.jdbc.parser.Token;

public class ColumnNode extends ASTNode {
    private boolean isStar;
    private String alias;
    private String tableName;
    private String columnName;
    private ExpressionNode expression; // non-null for computed select items (0, ISNULL(x), a+b)

    public ColumnNode(Token token) {
        super(token);
        this.isStar = token.getType() == com.dbf.jdbc.parser.TokenType.STAR;
    }

    public ExpressionNode getExpression() {
        return expression;
    }

    public void setExpression(ExpressionNode expression) {
        this.expression = expression;
    }

    public boolean isExpression() {
        return expression != null;
    }
    
    public boolean isStar() {
        return isStar;
    }
    
    public String getColumnName() {
        return getValue();
    }
    
    public String getAlias() {
        return alias;
    }
    
    public void setAlias(String alias) {
        this.alias = alias;
    }
    
    public String getTableName() {
        return tableName;
    }
    
    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

	public void setColumnName(String columnName) {
		this.columnName = columnName;
	}
}
