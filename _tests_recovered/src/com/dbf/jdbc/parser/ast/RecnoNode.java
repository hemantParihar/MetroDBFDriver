// RecnoNode.java
package com.dbf.jdbc.parser.ast;

public class RecnoNode extends ASTNode {
    
    public RecnoNode() {
        super(null);
    }
    
    @Override
    public String toString() {
        return "RECNO()";
    }
}