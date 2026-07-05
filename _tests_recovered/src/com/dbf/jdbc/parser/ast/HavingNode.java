// HavingNode.java
package com.dbf.jdbc.parser.ast;

public class HavingNode extends ASTNode {
    private ExpressionNode condition;
    
    public HavingNode() { super(null); }
    
    public ExpressionNode getCondition() { return condition; }
    public void setCondition(ExpressionNode condition) { this.condition = condition; }
}