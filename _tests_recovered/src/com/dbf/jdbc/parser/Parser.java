package com.dbf.jdbc.parser;

import com.dbf.jdbc.parser.ast.*;
import java.io.IOException;
import java.io.Reader;
import java.util.*;

public class Parser {
    private final Lexer lexer;
    private Token currentToken;
    private int tokenIndex = 0;
    private List<Token> tokens;

    public Parser(Reader reader) throws IOException {
        this.lexer = new Lexer(reader);
        this.tokens = lexer.getAllTokens();
        
        // Skip any UNKNOWN tokens at the beginning
        int startIndex = 0;
        while (startIndex < tokens.size() && tokens.get(startIndex).getType() == TokenType.UNKNOWN) {
            startIndex++;
        }
        this.tokenIndex = startIndex;
        this.currentToken = tokenIndex < tokens.size() ? tokens.get(tokenIndex) : 
            new Token(TokenType.EOF, null, 1, 1);
    }

    private void advance() {
        tokenIndex++;
        if (tokenIndex < tokens.size()) {
            currentToken = tokens.get(tokenIndex);
        } else {
            currentToken = new Token(TokenType.EOF, null, 
                currentToken.getLine(), currentToken.getColumn());
        }
    }

    private boolean match(TokenType type) {
        if (currentToken.getType() == type) {
            advance();
            return true;
        }
        return false;
    }

    private void expect(TokenType type) throws ParseException {
        if (currentToken.getType() != type) {
            throw new ParseException("Expected " + type + " but found " + currentToken.getType() + 
                " at line " + currentToken.getLine() + ", column " + currentToken.getColumn());
        }
        // Don't advance here - let caller advance after getting value
    }

    public SelectNode parseSelect() throws ParseException {
        SelectNode select = new SelectNode();
        
        // SELECT
        expect(TokenType.SELECT);
        advance();  // consume SELECT
        
        // Parse columns
        parseSelectList(select);
        
        // FROM
        expect(TokenType.FROM);
        advance();  // consume FROM
        
        // Table name - get value BEFORE advancing
        expect(TokenType.IDENTIFIER);
        FromNode from = new FromNode();
        String tableName = currentToken.getValue();  // This should now have the value!
        System.out.println("Found table name: '" + tableName + "'");
        from.setTableName(tableName);
        select.setFrom(from);
        advance();  // consume table name
        
        return select;
    }

    private void parseSelectList(SelectNode select) throws ParseException {
        do {
            if (currentToken.getType() == TokenType.STAR) {
                ColumnNode star = new ColumnNode(currentToken);
                select.addColumn(star);
                advance();
            } 
            else if (currentToken.getType() == TokenType.RECNO) {
                // Handle RECNO()
                advance(); // consume RECNO
                
                // Handle optional parentheses
                if (currentToken.getType() == TokenType.LPAREN) {
                    advance(); // consume (
                    if (currentToken.getType() == TokenType.RPAREN) {
                        advance(); // consume )
                    }
                }
                
                // Create a special column for RECNO
                Token recnoToken = new Token(TokenType.IDENTIFIER, "RECNO", 
                    currentToken.getLine(), currentToken.getColumn());
                ColumnNode recnoCol = new ColumnNode(recnoToken);
                recnoCol.setColumnName("RECNO");
                select.addColumn(recnoCol);
            }
            else {
                // Regular column
                expect(TokenType.IDENTIFIER);
                ColumnNode column = new ColumnNode(currentToken);
                column.setColumnName(currentToken.getValue());
                select.addColumn(column);
                advance();
            }
        } while (match(TokenType.COMMA));
    }

    public static class ParseException extends Exception {
        public ParseException(String message) {
            super(message);
        }
    }
}