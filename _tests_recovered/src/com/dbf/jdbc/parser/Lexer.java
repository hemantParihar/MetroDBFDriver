package com.dbf.jdbc.parser;

import java.io.IOException;
import java.io.Reader;
import java.util.*;

public class Lexer {
    private final Reader reader;
    private int currentChar;
    private int line = 1;
    private int column = 0;
    private final List<Token> tokens = new ArrayList<>();
    private int tokenIndex = 0;

    private static final Map<String, TokenType> keywords = new HashMap<>();

    static {
        // Keywords - WITHOUT parentheses
        keywords.put("select", TokenType.SELECT);
        keywords.put("from", TokenType.FROM);
        keywords.put("where", TokenType.WHERE);
        keywords.put("and", TokenType.AND);
        keywords.put("or", TokenType.OR);
        keywords.put("not", TokenType.NOT);
        keywords.put("order", TokenType.ORDER);
        keywords.put("by", TokenType.BY);
        keywords.put("asc", TokenType.ASC);
        keywords.put("desc", TokenType.DESC);
        keywords.put("insert", TokenType.INSERT);
        keywords.put("into", TokenType.INTO);
        keywords.put("values", TokenType.VALUES);
        keywords.put("update", TokenType.UPDATE);
        keywords.put("set", TokenType.SET);
        keywords.put("delete", TokenType.DELETE);
        keywords.put("like", TokenType.LIKE);
        keywords.put("in", TokenType.IN);
        keywords.put("between", TokenType.BETWEEN);
        keywords.put("is", TokenType.IS);
        keywords.put("null", TokenType.NULL);
        keywords.put("create", TokenType.CREATE);
        keywords.put("drop", TokenType.DROP);
        keywords.put("table", TokenType.TABLE);
        keywords.put("recno", TokenType.RECNO);      // ← WITHOUT parentheses!
         keywords.put("recno()", TokenType.RECNO); // ← REMOVE THIS!
        keywords.put("count", TokenType.COUNT);
        keywords.put("sum", TokenType.SUM);
        keywords.put("avg", TokenType.AVG);
        keywords.put("max", TokenType.MAX);
        keywords.put("min", TokenType.MIN);
        keywords.put("join", TokenType.JOIN);
        keywords.put("inner", TokenType.INNER);
        keywords.put("left", TokenType.LEFT);
        keywords.put("right", TokenType.RIGHT);
        keywords.put("full", TokenType.FULL);
        keywords.put("on", TokenType.ON);
        keywords.put("group", TokenType.GROUP);
        keywords.put("having", TokenType.HAVING);
    }

    public Lexer(Reader reader) throws IOException {
        this.reader = reader;
        advance();
        tokenize();
    }

    private void advance() throws IOException {
        currentChar = reader.read();
        if (currentChar != -1) {
            column++;
        }
    }

    private void tokenize() throws IOException {
        while (currentChar != -1) {
            // Skip whitespace
            if (Character.isWhitespace(currentChar)) {
                if (currentChar == '\n') {
                    line++;
                    column = 0;
                }
                advance();
                continue;
            }

            // Identifiers and keywords (letters, underscore, and parentheses for functions)
            if (Character.isLetter(currentChar) || currentChar == '_') {
                readIdentifierOrKeyword();
                continue;
            }

            // Numbers
            if (Character.isDigit(currentChar) || (currentChar == '-' && lookaheadIsDigit())) {
                readNumber();
                continue;
            }

            // String literals
            if (currentChar == '\'' || currentChar == '"') {
                readString();
                continue;
            }

            // Single character tokens and operators
            readSymbol();
        }

        tokens.add(new Token(TokenType.EOF, null, line, column));
    }

    private boolean lookaheadIsDigit() throws IOException {
        reader.mark(1);
        int next = reader.read();
        reader.reset();
        return Character.isDigit(next);
    }

    // FIXED: This is the correct method name
    private void readIdentifierOrKeyword() throws IOException {
        int startLine = line;
        int startCol = column;
        StringBuilder sb = new StringBuilder();

        while (currentChar != -1 && (Character.isLetterOrDigit(currentChar) || currentChar == '_')) {
            sb.append((char) currentChar);
            advance();
        }

        String value = sb.toString();
        String lower = value.toLowerCase();

        TokenType type = keywords.getOrDefault(lower, TokenType.IDENTIFIER);
        tokens.add(new Token(type, value, startLine, startCol));  // Store the value!
    }

    private void readNumber() throws IOException {
        int startLine = line;
        int startCol = column;
        StringBuilder sb = new StringBuilder();
        boolean hasDecimal = false;

        if (currentChar == '-') {
            sb.append((char) currentChar);
            advance();
        }

        while (currentChar != -1 && (Character.isDigit(currentChar) || currentChar == '.')) {
            if (currentChar == '.') {
                if (hasDecimal) break;
                hasDecimal = true;
            }
            sb.append((char) currentChar);
            advance();
        }

        tokens.add(new Token(TokenType.NUMBER, sb.toString(), startLine, startCol));
    }

    private void readString() throws IOException {
        int startLine = line;
        int startCol = column;
        char quote = (char) currentChar;
        StringBuilder sb = new StringBuilder();
        advance(); // Skip opening quote

        while (currentChar != -1 && currentChar != quote) {
            if (currentChar == '\\') {
                advance();
                if (currentChar == 'n') sb.append('\n');
                else if (currentChar == 't') sb.append('\t');
                else if (currentChar == 'r') sb.append('\r');
                else if (currentChar == '\'') sb.append('\'');
                else if (currentChar == '"') sb.append('"');
                else sb.append((char) currentChar);
            } else {
                sb.append((char) currentChar);
            }
            advance();
        }

        if (currentChar == quote) {
            advance(); // Skip closing quote
        }

        tokens.add(new Token(TokenType.STRING, sb.toString(), startLine, startCol));
    }

    private void readSymbol() throws IOException {
        int startLine = line;
        int startCol = column;
        char c = (char) currentChar;
        TokenType type;

        switch (c) {
            case '*': type = TokenType.STAR; advance(); break;
            case ',': type = TokenType.COMMA; advance(); break;
            case '.': type = TokenType.DOT; advance(); break;
            case '(': type = TokenType.LPAREN; advance(); break;
            case ')': type = TokenType.RPAREN; advance(); break;
            case '=': type = TokenType.EQ; advance(); break;
            case '<':
                advance();
                if (currentChar == '=') {
                    type = TokenType.LE;
                    advance();
                } else if (currentChar == '>') {
                    type = TokenType.NE;
                    advance();
                } else {
                    type = TokenType.LT;
                }
                break;
            case '>':
                advance();
                if (currentChar == '=') {
                    type = TokenType.GE;
                    advance();
                } else {
                    type = TokenType.GT;
                }
                break;
            case '!':
                advance();
                if (currentChar == '=') {
                    type = TokenType.NE;
                    advance();
                } else {
                    type = TokenType.UNKNOWN;
                }
                break;
            case '+': type = TokenType.PLUS; advance(); break;
            case '-': type = TokenType.MINUS; advance(); break;
            case '/': type = TokenType.DIVIDE; advance(); break;
            default:
                type = TokenType.UNKNOWN;
                advance();
                break;
        }

        tokens.add(new Token(type, String.valueOf(c), startLine, startCol));
    }

    public Token nextToken() {
        if (tokenIndex >= tokens.size()) {
            return new Token(TokenType.EOF, null, line, column);
        }
        return tokens.get(tokenIndex++);
    }

    public void reset() {
        tokenIndex = 0;
    }

    public List<Token> getAllTokens() {
        return new ArrayList<>(tokens);
    }
}