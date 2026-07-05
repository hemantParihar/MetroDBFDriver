package com.dbf.example;

import com.dbf.jdbc.parser.Lexer;
import com.dbf.jdbc.parser.Token;
import com.dbf.jdbc.parser.TokenType;
import java.io.StringReader;

public class DebugParserTest {
    public static void main(String[] args) {
        try {
            String sql = "SELECT RECNO(), * FROM master";
            System.out.println("Testing SQL: " + sql);
            System.out.println("SQL length: " + sql.length());
            System.out.println("First char: '" + sql.charAt(0) + "' (ASCII: " + (int)sql.charAt(0) + ")");
            
            // Print each character
            System.out.print("Characters: ");
            for (int i = 0; i < sql.length(); i++) {
                char c = sql.charAt(i);
                System.out.print("[" + c + "]");
            }
            System.out.println();
            
            Lexer lexer = new Lexer(new StringReader(sql));
            System.out.println("\nTokens generated:");
            Token token;
            int tokenCount = 0;
            while ((token = lexer.nextToken()).getType() != TokenType.EOF && tokenCount < 20) {
                System.out.println("  " + tokenCount + ": " + token.getType() + " -> '" + token.getValue() + "'");
                tokenCount++;
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}