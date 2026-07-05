package com.dbf.jdbc.sql;

import com.dbf.jdbc.dbf.DBFReader;
import com.dbf.jdbc.parser.ast.ExpressionNode;
import java.io.IOException;

/**
 * Filters rows based on WHERE clause expression
 */
public class RowFilter {
    private final ExpressionEvaluator evaluator;
    private final ExpressionNode condition;
    
    public RowFilter(DBFReader reader, ExpressionNode condition) {
        this.evaluator = new ExpressionEvaluator(reader);
        this.condition = condition;
    }
    
    public boolean matches() throws IOException {
        if (condition == null) return true;
        return evaluator.evaluate(condition);
    }
}