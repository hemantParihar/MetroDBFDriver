package com.dbf.jdbc.parser.ast;

import com.dbf.jdbc.parser.Token;
import com.dbf.jdbc.parser.TokenType;
import java.util.ArrayList;
import java.util.List;

/**
 * Abstract Syntax Tree node for parsed SQL
 */
public abstract class ASTNode {
    private final Token token;
    private final List<ASTNode> children = new ArrayList<>();
    
    public ASTNode(Token token) {
        this.token = token;
    }
    
    public Token getToken() {
        return token;
    }
    
    public List<ASTNode> getChildren() {
        return children;
    }
    
    public void addChild(ASTNode child) {
        children.add(child);
    }
    
    public String getValue() {
        return token != null ? token.getValue() : null;
    }
    
    public TokenType getType() {
        return token != null ? token.getType() : TokenType.UNKNOWN;
    }
}