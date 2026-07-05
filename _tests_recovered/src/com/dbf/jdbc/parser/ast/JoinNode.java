package com.dbf.jdbc.parser.ast;

import com.dbf.jdbc.parser.TokenType;

public class JoinNode extends ASTNode {
    private JoinType joinType;
    private String rightTable;
    private ExpressionNode condition;
    private String rightTableAlias;
    
    public JoinNode() {
        super(null);
    }
    
    public JoinType getJoinType() {
        return joinType;
    }
    
    public void setJoinType(JoinType type) {
        this.joinType = type;
    }
    
    // For backward compatibility with parser that uses setType
    public void setType(JoinType type) {
        this.joinType = type;
    }
    
    public String getRightTable() {
        return rightTable;
    }
    
    public void setRightTable(String table) {
        this.rightTable = table;
    }
    
    public ExpressionNode getCondition() {
        return condition;
    }
    
    public void setCondition(ExpressionNode condition) {
        this.condition = condition;
        if (condition != null) addChild(condition);
    }
    
    public String getRightTableAlias() {
        return rightTableAlias;
    }
    
    public void setRightTableAlias(String alias) {
        this.rightTableAlias = alias;
    }
    
    @Override
    public TokenType getType() {
        return TokenType.JOIN;
    }
}

