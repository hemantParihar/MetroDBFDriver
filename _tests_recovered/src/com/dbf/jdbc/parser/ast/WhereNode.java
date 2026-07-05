package com.dbf.jdbc.parser.ast;

import com.dbf.jdbc.parser.Token;

public class WhereNode extends ASTNode {
    private ExpressionNode condition;
    
    public WhereNode() {
        super(null);
    }
    
    public void setCondition(ExpressionNode condition) {
        this.condition = condition;
        addChild(condition);
    }
    
    public ExpressionNode getCondition() {
        return condition;
    }
}
