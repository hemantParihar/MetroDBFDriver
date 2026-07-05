package com.dbf.jdbc.execution;

import com.dbf.jdbc.dbf.DBFField;
import com.dbf.jdbc.dbf.DBFReader;
import com.dbf.jdbc.execution.eval.RowExpressionEvaluator;
import com.dbf.jdbc.execution.streaming.MaterializedRowStream;
import com.dbf.jdbc.execution.streaming.RowStream;
import com.dbf.jdbc.parser.Token;
import com.dbf.jdbc.parser.TokenType;
import com.dbf.jdbc.parser.ast.ASTNode;
import com.dbf.jdbc.parser.ast.AggregateNode;
import com.dbf.jdbc.parser.ast.ColumnNode;
import com.dbf.jdbc.parser.ast.ExpressionNode;
import com.dbf.jdbc.parser.ast.OrderByNode;
import com.dbf.jdbc.parser.ast.SelectNode;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Executes aggregate queries: COUNT/SUM/AVG/MIN/MAX over arbitrary scalar
 * expressions (e.g. {@code MAX(STR(YEAR(d))+'-'+STR(MONTH(d)))},
 * {@code SUM(IIF(...))}), with optional GROUP BY, HAVING and ORDER BY, and
 * with constant/scalar select items ({@code 0 AS x}, {@code '' AS y}) mixed
 * into the select list. Output columns are emitted in source order. One scan
 * of the file; memory is O(number of groups).
 */
public final class AggregateQueryExecutor {

    private AggregateQueryExecutor() {
    }

    public static RowStream execute(DBFReader reader, SelectNode selectNode)
            throws SQLException, IOException {
        List<DBFField> fields = reader.getHeader().getFields();
        String[] fieldNames = new String[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            fieldNames[i] = fields.get(i).getName();
        }
        reader.beforeFirst();
        return executeCore(new ReaderCursor(reader, fields.size()), fieldNames, fields, selectNode);
    }

    /**
     * Aggregates over a joined (or otherwise pre-computed) row stream. Column
     * names are alias-qualified ("TRAN.DEBIT", "AG.CUST_DESC") and the supplied
     * {@code combinedFields} are aligned to them, so GROUP BY / aggregate
     * arguments and the WHERE clause resolve across both tables. The stream is
     * fully consumed and closed here; the result is one row per group.
     */
    public static RowStream execute(RowStream source, List<DBFField> combinedFields,
            SelectNode selectNode) throws SQLException, IOException {
        String[] fieldNames = source.getColumnNames();
        try {
            return executeCore(new StreamCursor(source), fieldNames, combinedFields, selectNode);
        } finally {
            source.close();
        }
    }

    private static RowStream executeCore(RowCursor cursor, String[] fieldNames,
            List<DBFField> fields, SelectNode selectNode) throws SQLException, IOException {
        RowExpressionEvaluator evaluator = new RowExpressionEvaluator(fieldNames);

        ExpressionNode where = selectNode.getWhere() != null
            ? selectNode.getWhere().getCondition() : null;

        // GROUP BY keys: a column or an expression (e.g. MID(CUST_DESC,31,10)),
        // evaluated per row to form the grouping tuple.
        List<ExpressionNode> groupKeys = selectNode.getGroupBy() != null
            ? selectNode.getGroupBy().getKeys() : new ArrayList<ExpressionNode>();

        // Output specs, one per select item, in source order
        List<ItemSpec> specs = new ArrayList<>();
        for (ASTNode item : selectNode.getSelectItems()) {
            specs.add(buildSpec(item, specs.size()));
        }
        int n = specs.size();

        String[] labels = new String[n];
        List<DBFField> outFields = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            labels[i] = specs.get(i).label;
            outFields.add(inferOutputField(specs.get(i), evaluator, fields));
        }

        // ORDER BY: aggregate sort keys become hidden aggregates appended
        // after the output columns; plain keys reference an output label.
        // sortPlan holds {indexInFullRow, direction} for each ORDER BY item.
        List<ItemSpec> allSpecs = new ArrayList<>(specs);
        List<int[]> sortPlan = new ArrayList<>();
        if (selectNode.getOrderBy() != null) {
            for (OrderByNode.OrderItem item : selectNode.getOrderBy().getItems()) {
                int dir = item.isAscending() ? 1 : -1;
                if (item.isAggregate()) {
                    ItemSpec hidden = aggSpec(item.getAggregate());
                    sortPlan.add(new int[] { allSpecs.size(), dir });
                    allSpecs.add(hidden);
                } else {
                    String name = item.isExpression() ? null : item.getColumnName();
                    int li = name != null ? indexOfIgnoreCase(labels, name) : -1;
                    if (li >= 0) {
                        // ORDER BY references an output column by name/alias.
                        sortPlan.add(new int[] { li, dir });
                    } else {
                        // ORDER BY a grouped column or an expression over grouped
                        // columns (e.g. MID(CUST_DESC,31) or CUST_DESC itself):
                        // capture its value per group as a hidden sort key.
                        ItemSpec hidden = new ItemSpec();
                        hidden.isAggregate = false;
                        hidden.label = "__ORDERKEY";
                        hidden.scalarExpr = item.isExpression()
                            ? item.getExpression() : columnRefByName(name);
                        sortPlan.add(new int[] { allSpecs.size(), dir });
                        allSpecs.add(hidden);
                    }
                }
            }
        }

        // Single scan, accumulating per group (output + hidden order-by aggs)
        Map<List<Object>, GroupAcc> groups = new LinkedHashMap<>();
        Object[] inRow;
        while ((inRow = cursor.next()) != null) {
            int recno = cursor.recno();
            if (where != null && !evaluator.matches(where, inRow, recno)) continue;

            List<Object> key = new ArrayList<>(groupKeys.size());
            for (ExpressionNode g : groupKeys) {
                key.add(normalizeKey(evaluator.evaluate(g, inRow)));
            }

            GroupAcc acc = groups.get(key);
            if (acc == null) {
                acc = new GroupAcc(allSpecs);
                groups.put(key, acc);
            }
            acc.accumulate(allSpecs, evaluator, inRow);
        }

        // A global aggregate (no GROUP BY) over zero rows still yields one row
        if (groups.isEmpty() && groupKeys.isEmpty()) {
            groups.put(new ArrayList<>(), new GroupAcc(allSpecs));
        }

        // Emit full rows (output columns + hidden sort keys). With no explicit
        // ORDER BY, emit in GROUP BY key order (ascending) rather than the
        // hash/scan order in which groups happened to be first seen. This is
        // deterministic and matches the conventional behavior of Access/Jet
        // (the ODBC engine the user compares against), where a grouped query
        // comes back sorted by its grouping columns.
        List<Map.Entry<List<Object>, GroupAcc>> entries = new ArrayList<>(groups.entrySet());
        if (sortPlan.isEmpty() && !groupKeys.isEmpty()) {
            entries.sort((e1, e2) -> compareKeys(e1.getKey(), e2.getKey()));
        }
        List<Object[]> fullRows = new ArrayList<>();
        for (Map.Entry<List<Object>, GroupAcc> e : entries) {
            fullRows.add(e.getValue().emit(allSpecs, evaluator));
        }

        // HAVING over the output columns (labels resolve to indexes 0..n-1)
        if (selectNode.getHaving() != null) {
            RowExpressionEvaluator havingEval = new RowExpressionEvaluator(labels);
            List<Object[]> kept = new ArrayList<>();
            for (Object[] row : fullRows) {
                if (havingEval.matches(selectNode.getHaving().getCondition(), row)) {
                    kept.add(row);
                }
            }
            fullRows = kept;
        }

        if (!sortPlan.isEmpty()) {
            fullRows.sort((a, b) -> {
                for (int[] key : sortPlan) {
                    int cmp = compare(a[key[0]], b[key[0]]) * key[1];
                    if (cmp != 0) return cmp;
                }
                return 0;
            });
        }

        // Trim hidden sort columns from the output
        List<Object[]> resultRows = new ArrayList<>(fullRows.size());
        for (Object[] full : fullRows) {
            resultRows.add(full.length == n ? full : java.util.Arrays.copyOf(full, n));
        }

        return new MaterializedRowStream(resultRows, labels, outFields);
    }

    // ==================== Row sources ====================

    /** Uniform pull-based row source so the core loop is storage-agnostic. */
    private interface RowCursor {
        /** Next row, or null when exhausted. */
        Object[] next() throws SQLException, IOException;
        /** 1-based record number of the last row returned, or -1 if N/A. */
        int recno();
    }

    /** Cursor over a DBF table, skipping deleted records (single-table path). */
    private static final class ReaderCursor implements RowCursor {
        private final DBFReader reader;
        private final int fieldCount;
        private int recno = -1;

        ReaderCursor(DBFReader reader, int fieldCount) {
            this.reader = reader;
            this.fieldCount = fieldCount;
        }

        @Override
        public Object[] next() throws SQLException, IOException {
            while (reader.next()) {
                if (reader.isDeleted()) continue;
                Object[] row = new Object[fieldCount];
                for (int i = 0; i < fieldCount; i++) {
                    row[i] = reader.getValue(i);
                }
                recno = reader.getCurrentRecord() + 1;
                return row;
            }
            return null;
        }

        @Override
        public int recno() {
            return recno;
        }
    }

    /** Cursor over a pre-computed row stream (join pipeline); no record number. */
    private static final class StreamCursor implements RowCursor {
        private final RowStream source;

        StreamCursor(RowStream source) {
            this.source = source;
        }

        @Override
        public Object[] next() throws SQLException, IOException {
            return source.next();
        }

        @Override
        public int recno() {
            return -1;
        }
    }

    /** Builds an aggregate spec from an ORDER BY aggregate node. */
    private static ItemSpec aggSpec(AggregateNode agg) {
        ItemSpec spec = new ItemSpec();
        spec.isAggregate = true;
        spec.function = agg.getFunction();
        spec.star = agg.isStar();
        spec.distinct = agg.isDistinct();
        spec.argExpr = agg.getArgument();
        spec.label = "__ORDERAGG";
        return spec;
    }

    // ==================== Output type inference ====================

    /**
     * Infers the DBF field (hence JDBC type) of one output column:
     * COUNT/SUM/AVG are numeric; MIN/MAX take the type of their argument;
     * scalar items take the type of their expression.
     */
    private static DBFField inferOutputField(ItemSpec spec,
            RowExpressionEvaluator evaluator, List<DBFField> fields) {
        if (spec.isAggregate) {
            switch (spec.function.toUpperCase()) {
                case "COUNT": return makeField(spec.label, 'N', 18, 0);
                case "SUM":   return makeField(spec.label, 'N', 18, 2);
                case "AVG":   return makeField(spec.label, 'N', 18, 6);
                case "MIN":
                case "MAX":   return fieldForExpr(spec.argExpr, spec.label, evaluator, fields);
                default:      return makeField(spec.label, 'C', 254, 0);
            }
        }
        if (spec.composite) {
            return fieldForExpr(spec.compositeExpr, spec.label, evaluator, fields);
        }
        return fieldForExpr(spec.scalarExpr, spec.label, evaluator, fields);
    }

    /** Field for an expression: a column keeps its source type; else inferred. */
    private static DBFField fieldForExpr(ExpressionNode expr, String label,
            RowExpressionEvaluator evaluator, List<DBFField> fields) {
        if (expr != null && expr.isColumn() && !expr.isRecno()) {
            int idx = evaluator.columnIndex(expr.getTableName(), expr.getColumnName());
            if (idx >= 0 && idx < fields.size()) {
                DBFField src = fields.get(idx);
                return makeField(label, src.getType(), src.getLength(), src.getDecimalCount());
            }
        }
        char type = inferType(expr, evaluator, fields);
        switch (type) {
            case 'N': return makeField(label, 'N', 18, 4);
            case 'D': return makeField(label, 'D', 8, 0);
            case 'L': return makeField(label, 'L', 1, 0);
            default:  return makeField(label, 'C', 254, 0);
        }
    }

    private static char inferType(ExpressionNode expr,
            RowExpressionEvaluator evaluator, List<DBFField> fields) {
        if (expr == null) return 'C';
        if (expr.isRecno()) return 'N';
        if (expr.isAggregate()) {
            switch (expr.getAggregateFunction()) {
                case "COUNT": case "SUM": case "AVG": return 'N';
                case "MIN": case "MAX": return inferType(expr.getAggregateArg(), evaluator, fields);
                default: return 'C';
            }
        }
        if (expr.isFunction()) {
            switch (expr.getFunctionName()) {
                case "LEN": case "LENGTH": case "VAL": case "ABS": case "ROUND":
                case "INT": case "MOD": case "YEAR": case "MONTH": case "DAY":
                    return 'N';
                case "ISBLANK":
                    return 'L';
                case "ISNULL":
                    return (expr.getArguments() != null && expr.getArguments().size() == 1)
                        ? 'L' : 'C';
                case "IIF":
                    // type of the "true" branch is representative
                    return expr.getArguments() != null && expr.getArguments().size() >= 2
                        ? inferType(expr.getArguments().get(1), evaluator, fields) : 'C';
                default:
                    return 'C';
            }
        }
        if (expr.isLiteral()) {
            return expr.getType() == TokenType.NUMBER ? 'N' : 'C';
        }
        if (expr.isColumn()) {
            int idx = evaluator.columnIndex(expr.getTableName(), expr.getColumnName());
            return (idx >= 0 && idx < fields.size()) ? fields.get(idx).getType() : 'C';
        }
        switch (expr.getType()) {
            case EQ: case NE: case LT: case GT: case LE: case GE:
            case AND: case OR: case NOT: case LIKE:
                return 'L';
            case PLUS:
                return inferType(expr.getLeft(), evaluator, fields) == 'N'
                    && inferType(expr.getRight(), evaluator, fields) == 'N' ? 'N' : 'C';
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

    private static ItemSpec buildSpec(ASTNode item, int position) throws SQLException {
        ItemSpec spec = new ItemSpec();
        if (item instanceof AggregateNode) {
            AggregateNode agg = (AggregateNode) item;
            spec.isAggregate = true;
            spec.function = agg.getFunction();
            spec.star = agg.isStar();
            spec.distinct = agg.isDistinct();
            spec.argExpr = agg.getArgument();
            spec.label = agg.getAlias() != null ? agg.getAlias()
                : agg.getFunction() + "_" + position;
        } else {
            ColumnNode col = (ColumnNode) item;
            if (col.isStar()) {
                throw new SQLException("SELECT * cannot be combined with aggregate functions");
            }
            spec.isAggregate = false;
            ExpressionNode e = col.isExpression() ? col.getExpression() : columnRef(col);
            spec.label = col.getAlias() != null ? col.getAlias()
                : (col.getColumnName() != null ? col.getColumnName() : "EXPR_" + position);
            // An expression containing aggregate calls (e.g. MAX(x)+1) is a
            // composite: its inner aggregates are computed per group, then the
            // surrounding expression is evaluated against those results.
            List<ExpressionNode> inner = new ArrayList<>();
            collectAggregates(e, inner);
            if (inner.isEmpty()) {
                spec.scalarExpr = e;
            } else {
                spec.composite = true;
                spec.compositeExpr = e;
                spec.innerAggs = inner;
            }
        }
        return spec;
    }

    /** Collects (by identity) all aggregate calls inside an expression tree. */
    private static void collectAggregates(ExpressionNode n, List<ExpressionNode> out) {
        if (n == null) {
            return;
        }
        if (n.isAggregate()) {
            out.add(n);
            collectAggregates(n.getAggregateArg(), out); // (rare) nested
            return;
        }
        collectAggregates(n.getLeft(), out);
        collectAggregates(n.getRight(), out);
        if (n.getArguments() != null) {
            for (ExpressionNode a : n.getArguments()) {
                collectAggregates(a, out);
            }
        }
    }

    /**
     * Rebuilds {@code expr} with each aggregate node replaced by a literal of
     * its computed value, so the result can be evaluated as a plain scalar
     * expression by {@link RowExpressionEvaluator}.
     */
    private static ExpressionNode substituteAggregates(ExpressionNode expr,
            java.util.IdentityHashMap<ExpressionNode, Object> values) {
        if (expr == null) {
            return null;
        }
        if (values.containsKey(expr)) {
            Object v = values.get(expr);
            if (v == null) {
                return new ExpressionNode(new Token(TokenType.NULL, null, 0, 0));
            }
            return new ExpressionNode(v); // literal-value node
        }
        if (expr.isFunction()) {
            List<ExpressionNode> args = new ArrayList<>();
            if (expr.getArguments() != null) {
                for (ExpressionNode a : expr.getArguments()) {
                    args.add(substituteAggregates(a, values));
                }
            }
            ExpressionNode copy = new ExpressionNode(expr.getToken());
            copy.setFunction(expr.getFunctionName(), args);
            return copy;
        }
        if (expr.getLeft() != null || expr.getRight() != null) {
            ExpressionNode copy = new ExpressionNode(expr.getToken());
            copy.setLeft(substituteAggregates(expr.getLeft(), values));
            copy.setRight(substituteAggregates(expr.getRight(), values));
            return copy;
        }
        return expr; // leaf literal/column - reuse as-is
    }

    private static ExpressionNode columnRef(ColumnNode col) {
        ExpressionNode node = new ExpressionNode(
            new Token(TokenType.IDENTIFIER, col.getColumnName(), 0, 0));
        node.setColumnName(col.getColumnName());
        node.setTableName(col.getTableName());
        return node;
    }

    /** Builds a bare column-reference expression from a column name (for ORDER BY keys). */
    private static ExpressionNode columnRefByName(String name) {
        ExpressionNode node = new ExpressionNode(new Token(TokenType.IDENTIFIER, name, 0, 0));
        node.setColumnName(name);
        return node;
    }

    // ==================== Per-item / per-group state ====================

    private static final class ItemSpec {
        boolean isAggregate;
        String label;
        // aggregate
        String function;
        ExpressionNode argExpr; // null for COUNT(*)
        boolean star;
        boolean distinct;
        // scalar
        ExpressionNode scalarExpr;
        // composite: an expression containing aggregate calls, e.g. MAX(x)+1
        boolean composite;
        ExpressionNode compositeExpr;
        List<ExpressionNode> innerAggs;
    }

    /** Per-group accumulator: aggregate state or captured value per select item. */
    private static final class GroupAcc {
        final Object[] slots;       // AggregateState for agg items
        final boolean[] captured;   // for scalar items

        GroupAcc(List<ItemSpec> specs) {
            slots = new Object[specs.size()];
            captured = new boolean[specs.size()];
            for (int i = 0; i < specs.size(); i++) {
                ItemSpec spec = specs.get(i);
                if (spec.isAggregate) {
                    slots[i] = new AggregateState(spec.distinct);
                } else if (spec.composite) {
                    AggregateState[] states = new AggregateState[spec.innerAggs.size()];
                    for (int j = 0; j < states.length; j++) {
                        states[j] = new AggregateState(spec.innerAggs.get(j).isDistinct());
                    }
                    slots[i] = states;
                }
            }
        }

        void accumulate(List<ItemSpec> specs, RowExpressionEvaluator eval, Object[] row)
                throws SQLException {
            for (int i = 0; i < specs.size(); i++) {
                ItemSpec spec = specs.get(i);
                if (spec.isAggregate) {
                    AggregateState st = (AggregateState) slots[i];
                    if (spec.star || spec.argExpr == null) {
                        st.accumulateRow();
                    } else {
                        st.accumulate(eval.evaluate(spec.argExpr, row));
                    }
                } else if (spec.composite) {
                    AggregateState[] states = (AggregateState[]) slots[i];
                    for (int j = 0; j < states.length; j++) {
                        ExpressionNode agg = spec.innerAggs.get(j);
                        if (agg.isStar() || agg.getAggregateArg() == null) {
                            states[j].accumulateRow();
                        } else {
                            states[j].accumulate(eval.evaluate(agg.getAggregateArg(), row));
                        }
                    }
                } else if (!captured[i]) {
                    // Scalar / grouped column: capture the first row of the group
                    slots[i] = eval.evaluate(spec.scalarExpr, row);
                    captured[i] = true;
                }
            }
        }

        Object[] emit(List<ItemSpec> specs, RowExpressionEvaluator eval) throws SQLException {
            Object[] out = new Object[specs.size()];
            for (int i = 0; i < specs.size(); i++) {
                ItemSpec spec = specs.get(i);
                if (spec.isAggregate) {
                    out[i] = ((AggregateState) slots[i]).result(spec.function);
                } else if (spec.composite) {
                    // Compute each inner aggregate, then evaluate the surrounding
                    // expression with those results substituted in.
                    AggregateState[] states = (AggregateState[]) slots[i];
                    java.util.IdentityHashMap<ExpressionNode, Object> values =
                        new java.util.IdentityHashMap<>();
                    for (int j = 0; j < states.length; j++) {
                        ExpressionNode agg = spec.innerAggs.get(j);
                        values.put(agg, states[j].result(agg.getAggregateFunction()));
                    }
                    ExpressionNode rewritten = substituteAggregates(spec.compositeExpr, values);
                    out[i] = eval.evaluate(rewritten, EMPTY_ROW);
                } else {
                    out[i] = slots[i];
                }
            }
            return out;
        }
    }

    private static final Object[] EMPTY_ROW = new Object[0];

    // ==================== Sorting / helpers ====================

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static int compare(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        }
        if (a instanceof Comparable && a.getClass() == b.getClass()) {
            return ((Comparable) a).compareTo(b);
        }
        return a.toString().compareToIgnoreCase(b.toString());
    }

    /** Lexicographic compare of two GROUP BY key tuples (element-wise, ascending). */
    private static int compareKeys(List<Object> a, List<Object> b) {
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            int cmp = compare(a.get(i), b.get(i));
            if (cmp != 0) return cmp;
        }
        return Integer.compare(a.size(), b.size());
    }

    private static int indexOfIgnoreCase(String[] labels, String value) {
        for (int i = 0; i < labels.length; i++) {
            if (labels[i].equalsIgnoreCase(value)) return i;
        }
        return -1;
    }

    private static Object normalizeKey(Object value) {
        return value instanceof String ? ((String) value).trim() : value;
    }

    /** Per-group, per-aggregate incremental state. */
    private static final class AggregateState {
        private final boolean distinct;
        private final Set<Object> seen;
        private long count = 0;
        private double sum = 0;
        private boolean hasNumeric = false;
        private boolean allIntegers = true; // every numeric value seen was integer-typed
        private Object min = null;
        private Object max = null;

        AggregateState(boolean distinct) {
            this.distinct = distinct;
            this.seen = distinct ? new HashSet<>() : null;
        }

        /** COUNT(*) - counts every row regardless of values. */
        void accumulateRow() {
            count++;
        }

        void accumulate(Object value) {
            if (value == null) return;
            if (value instanceof String && ((String) value).trim().isEmpty()) return;
            Object normalized = normalizeKey(value);
            if (distinct && !seen.add(normalized)) return;

            count++;
            Double num = toNumber(value);
            if (num != null) {
                sum += num;
                hasNumeric = true;
                if (!com.dbf.jdbc.execution.eval.RowExpressionEvaluator.isIntegerValue(value)) {
                    allIntegers = false;
                }
            }
            if (min == null || compare(value, min) < 0) min = value;
            if (max == null || compare(value, max) > 0) max = value;
        }

        Object result(String function) {
            switch (function.toUpperCase()) {
                case "COUNT": return count;
                // Type-preserving: SUM/AVG over integer values stay integer
                // (SUM(int)->int, AVG(int)->int truncated); decimals stay Double.
                case "SUM":
                    if (!hasNumeric) return null;
                    return allIntegers ? (Object) (long) sum : (Object) sum;
                case "AVG":
                    if (count == 0 || !hasNumeric) return null;
                    return allIntegers ? (Object) (long) (sum / count) : (Object) (sum / count);
                case "MIN": return min;
                case "MAX": return max;
                default: return null;
            }
        }

        private Double toNumber(Object value) {
            if (value instanceof Number) return ((Number) value).doubleValue();
            try {
                String s = value.toString().trim();
                return s.isEmpty() ? null : Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
