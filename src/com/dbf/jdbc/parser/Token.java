package com.dbf.jdbc.parser;

/**
 * Token produced by lexer for LALR parser
 */
public class Token {
    private final TokenType type;
    private final String value;
    private final int line;
    private final int column;
    
    public Token(TokenType type, String value, int line, int column) {
        this.type = type;
        this.value = value;
        this.line = line;
        this.column = column;
    }
    
    public TokenType getType() {
        return type;
    }
    
    public String getValue() {
        return value;
    }
    
    public int getLine() {
        return line;
    }
    
    public int getColumn() {
        return column;
    }
    
    public boolean isKeyword() {
        return type.ordinal() >= TokenType.SELECT.ordinal() && 
               type.ordinal() <= TokenType.DROP.ordinal();
    }
    
    @Override
    public String toString() {
        return type + (value != null ? "(" + value + ")" : "");
    }
}