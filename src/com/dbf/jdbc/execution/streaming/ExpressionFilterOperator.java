package com.dbf.jdbc.execution.streaming;

import com.dbf.jdbc.execution.eval.RowExpressionEvaluator;
import com.dbf.jdbc.parser.ast.ExpressionNode;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

/**
 * Streaming WHERE filter that evaluates a full expression (functions,
 * qualified columns, arithmetic, AND/OR/NOT, LIKE, comparisons) against
 * each row and passes those that evaluate truthy. Column metadata passes
 * through unchanged.
 */
public class ExpressionFilterOperator implements RowStream, FieldAwareStream {
    private final RowStream input;
    private final ExpressionNode condition;
    private final RowExpressionEvaluator evaluator;
    private boolean closed = false;

    public ExpressionFilterOperator(RowStream input, ExpressionNode condition) {
        this.input = input;
        this.condition = condition;
        this.evaluator = new RowExpressionEvaluator(input.getColumnNames());
    }

    @Override
    public Object[] next() throws IOException, SQLException {
        Object[] row;
        while ((row = input.next()) != null) {
            if (evaluator.matches(condition, row)) {
                return row;
            }
        }
        return null;
    }

    @Override
    public String[] getColumnNames() {
        return input.getColumnNames();
    }

    @Override
    public int[] getColumnTypes() {
        return input.getColumnTypes();
    }

    @Override
    public List<com.dbf.jdbc.dbf.DBFField> getFields() {
        return input instanceof FieldAwareStream ? ((FieldAwareStream) input).getFields() : null;
    }

    @Override
    public void reset() throws IOException, SQLException {
        input.reset();
    }

    @Override
    public boolean supportsReset() {
        return input.supportsReset();
    }

    @Override
    public long estimateRowCount() {
        return input.estimateRowCount();
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            input.close();
        }
    }
}
