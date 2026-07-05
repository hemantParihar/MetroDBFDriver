package com.dbf.jdbc.execution.streaming;

import com.dbf.jdbc.dbf.DBFField;
import com.dbf.jdbc.execution.eval.RowExpressionEvaluator;
import com.dbf.jdbc.parser.TokenType;
import com.dbf.jdbc.parser.ast.ColumnNode;
import com.dbf.jdbc.parser.ast.ExpressionNode;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates a SELECT list against each input row, producing the output
 * columns. Each select item is one of:
 * <ul>
 *   <li>{@code *} — passthrough of every input column</li>
 *   <li>a plain (optionally qualified) column — passthrough by name</li>
 *   <li>a computed expression (literal, function, arithmetic) — evaluated</li>
 * </ul>
 * Output column types are inferred so {@code ResultSetMetaData} reports
 * sensible JDBC types.
 */
public class ExpressionProjectionOperator implements RowStream, FieldAwareStream {
    private final RowStream input;
    private final RowExpressionEvaluator evaluator;

    private final List<Emitter> emitters = new ArrayList<>();
    private final String[] outNames;
    private final int[] outTypes;
    private final List<DBFField> outFields = new ArrayList<>();
    private boolean closed = false;

    /** One output column: either passthrough of an input index, or an expression. */
    private static final class Emitter {
        final int passthroughIndex; // -1 when expression-based
        final ExpressionNode expression;

        Emitter(int passthroughIndex, ExpressionNode expression) {
            this.passthroughIndex = passthroughIndex;
            this.expression = expression;
        }
    }

    /**
     * @param input        upstream rows
     * @param selectItems  the SELECT list
     * @param inputFields  DBF field per input column (for passthrough type
     *                     metadata); may be null
     */
    public ExpressionProjectionOperator(RowStream input, List<ColumnNode> selectItems,
            List<DBFField> inputFields) throws SQLException {
        this.input = input;
        this.evaluator = new RowExpressionEvaluator(input.getColumnNames());
        String[] inputNames = input.getColumnNames();

        List<String> names = new ArrayList<>();
        for (ColumnNode item : selectItems) {
            if (item.isStar()) {
                for (int i = 0; i < inputNames.length; i++) {
                    emitters.add(new Emitter(i, null));
                    names.add(stripQualifier(inputNames[i]));
                    outFields.add(fieldFor(inputFields, i, stripQualifier(inputNames[i])));
                }
            } else if (item.isExpression()) {
                emitters.add(new Emitter(-1, item.getExpression()));
                String name = item.getAlias() != null ? item.getAlias() : "EXPR" + names.size();
                names.add(name);
                outFields.add(inferField(item.getExpression(), name, inputFields));
            } else {
                // Plain column passthrough, resolved by (optionally qualified) name
                int idx = evaluator.columnIndex(item.getTableName(), item.getColumnName());
                if (idx < 0) {
                    throw new SQLException("Column not found: "
                        + (item.getTableName() != null ? item.getTableName() + "." : "")
                        + item.getColumnName());
                }
                emitters.add(new Emitter(idx, null));
                String name = item.getAlias() != null ? item.getAlias() : item.getColumnName();
                names.add(name);
                outFields.add(fieldFor(inputFields, idx, name));
            }
        }

        this.outNames = names.toArray(new String[0]);
        this.outTypes = new int[outFields.size()];
        for (int i = 0; i < outFields.size(); i++) {
            outTypes[i] = sqlType(outFields.get(i).getType());
        }
    }

    /**
     * Augment constructor: passes through every input column (keeping its
     * full, qualified name) and appends one computed column per expression.
     * Used to attach ORDER BY sort keys before an external sort.
     */
    public ExpressionProjectionOperator(RowStream input, List<DBFField> inputFields,
            List<ExpressionNode> appendExprs, List<String> appendNames) throws SQLException {
        this.input = input;
        this.evaluator = new RowExpressionEvaluator(input.getColumnNames());
        String[] inputNames = input.getColumnNames();

        List<String> names = new ArrayList<>();
        for (int i = 0; i < inputNames.length; i++) {
            emitters.add(new Emitter(i, null));
            names.add(inputNames[i]); // keep qualifier so later stages can resolve
            outFields.add(fieldFor(inputFields, i, inputNames[i]));
        }
        for (int k = 0; k < appendExprs.size(); k++) {
            emitters.add(new Emitter(-1, appendExprs.get(k)));
            names.add(appendNames.get(k));
            outFields.add(inferField(appendExprs.get(k), appendNames.get(k), inputFields));
        }

        this.outNames = names.toArray(new String[0]);
        this.outTypes = new int[outFields.size()];
        for (int i = 0; i < outFields.size(); i++) {
            outTypes[i] = sqlType(outFields.get(i).getType());
        }
    }

    @Override
    public Object[] next() throws IOException, SQLException {
        Object[] row = input.next();
        if (row == null) return null;

        Object[] out = new Object[emitters.size()];
        for (int i = 0; i < emitters.size(); i++) {
            Emitter e = emitters.get(i);
            if (e.passthroughIndex >= 0) {
                out[i] = e.passthroughIndex < row.length ? row[e.passthroughIndex] : null;
            } else {
                out[i] = evaluator.evaluate(e.expression, row);
            }
        }
        return out;
    }

    @Override
    public String[] getColumnNames() {
        return outNames;
    }

    @Override
    public int[] getColumnTypes() {
        return outTypes;
    }

    @Override
    public List<DBFField> getFields() {
        return outFields;
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

    // ==================== Type inference ====================

    private static String stripQualifier(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    private DBFField fieldFor(List<DBFField> inputFields, int idx, String name) {
        if (inputFields != null && idx >= 0 && idx < inputFields.size()) {
            DBFField src = inputFields.get(idx);
            return makeField(name, src.getType(), src.getLength(), src.getDecimalCount());
        }
        return makeField(name, 'C', 254, 0);
    }

    /** Static type inference for a computed expression. */
    private DBFField inferField(ExpressionNode expr, String name, List<DBFField> inputFields) {
        char type = inferType(expr, inputFields);
        switch (type) {
            case 'N': return makeField(name, 'N', 18, 4);
            case 'L': return makeField(name, 'L', 1, 0);
            case 'D': return makeField(name, 'D', 8, 0);
            default:  return makeField(name, 'C', 254, 0);
        }
    }

    private char inferType(ExpressionNode expr, List<DBFField> inputFields) {
        if (expr == null) return 'C';
        if (expr.isFunction()) {
            switch (expr.getFunctionName()) {
                case "LEN": case "LENGTH": case "VAL": case "ABS":
                case "ROUND": case "INT": case "MOD":
                    return 'N';
                case "ISBLANK":
                    return 'L';
                case "ISNULL":
                    return (expr.getArguments() != null && expr.getArguments().size() == 1)
                        ? 'L' : 'C';
                default:
                    return 'C';
            }
        }
        if (expr.isLiteral()) {
            return expr.getType() == TokenType.NUMBER ? 'N' : 'C';
        }
        if (expr.isColumn()) {
            int idx = evaluator.columnIndex(expr.getTableName(), expr.getColumnName());
            if (inputFields != null && idx >= 0 && idx < inputFields.size()) {
                return inputFields.get(idx).getType();
            }
            return 'C';
        }
        switch (expr.getType()) {
            case EQ: case NE: case LT: case GT: case LE: case GE:
            case AND: case OR: case NOT: case LIKE:
                return 'L';
            case PLUS:
                // numeric + only when both sides look numeric; else string concat
                return (inferType(expr.getLeft(), inputFields) == 'N'
                    && inferType(expr.getRight(), inputFields) == 'N') ? 'N' : 'C';
            case MINUS: case MULTIPLY: case STAR: case DIVIDE:
                return 'N';
            default:
                return 'C';
        }
    }

    private static DBFField makeField(String name, char type, int length, int decimals) {
        DBFField f = new DBFField();
        f.setName(name);
        f.setType(type);
        f.setLength(length);
        f.setDecimalCount(decimals);
        return f;
    }

    private static int sqlType(char dbfType) {
        // Single source of truth: see com.dbf.jdbc.dbf.DbfType
        return com.dbf.jdbc.dbf.DbfType.sqlType(dbfType);
    }
}
