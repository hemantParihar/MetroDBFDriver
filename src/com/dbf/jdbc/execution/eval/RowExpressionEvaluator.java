package com.dbf.jdbc.execution.eval;

import com.dbf.jdbc.parser.TokenType;
import com.dbf.jdbc.parser.ast.ExpressionNode;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Evaluates a parsed {@link ExpressionNode} against a single row, resolving
 * column references against a fixed set of (possibly alias-qualified) column
 * names. Shared by the filter, projection and sort operators so functions,
 * arithmetic, comparisons and boolean logic behave identically everywhere.
 *
 * Column names may be qualified ("ALIAS.COL") or plain ("COL"). A qualified
 * reference resolves by exact match; an unqualified reference resolves by the
 * column-name suffix (first match wins).
 */
public class RowExpressionEvaluator {
    private final Map<String, Integer> qualified = new HashMap<>();
    private final Map<String, Integer> bySuffix = new HashMap<>();
    private final Map<String, Pattern> likeCache = new HashMap<>();
    // The 1-based record number of the row being evaluated, for RECNO();
    // -1 when not applicable (e.g. joined rows).
    private int currentRecno = -1;

    public RowExpressionEvaluator(String[] columnNames) {
        for (int i = 0; i < columnNames.length; i++) {
            String name = columnNames[i].toUpperCase();
            qualified.put(name, i);
            int dot = name.lastIndexOf('.');
            String suffix = dot >= 0 ? name.substring(dot + 1) : name;
            // First occurrence wins for ambiguous unqualified names
            bySuffix.putIfAbsent(suffix, i);
        }
    }

    /** Resolves a column reference to its row index, or -1 if not found. */
    public int columnIndex(String tableName, String columnName) {
        if (columnName == null) return -1;
        if (tableName != null) {
            Integer idx = qualified.get((tableName + "." + columnName).toUpperCase());
            if (idx != null) return idx;
            // Single-table queries store columns unqualified; fall back to the
            // column-name suffix so "TRAN.DEBIT" still resolves to "DEBIT".
            idx = bySuffix.get(columnName.toUpperCase());
            return idx != null ? idx : -1;
        }
        Integer idx = qualified.get(columnName.toUpperCase());
        if (idx != null) return idx;
        idx = bySuffix.get(columnName.toUpperCase());
        return idx != null ? idx : -1;
    }

    public boolean matches(ExpressionNode condition, Object[] row) throws SQLException {
        return matches(condition, row, -1);
    }

    /** @param recno the 1-based record number, for RECNO() (-1 if N/A). */
    public boolean matches(ExpressionNode condition, Object[] row, int recno) throws SQLException {
        if (condition == null) return true;
        this.currentRecno = recno;
        return toBoolean(evaluate(condition, row));
    }

    public Object evaluate(ExpressionNode node, Object[] row) throws SQLException {
        if (node == null) return null;

        if (node.isFunction()) {
            List<Object> args = new ArrayList<>();
            if (node.getArguments() != null) {
                for (ExpressionNode arg : node.getArguments()) {
                    args.add(evaluate(arg, row));
                }
            }
            return FunctionLibrary.call(node.getFunctionName(), args);
        }

        if (node.isLiteral()) {
            return literal(node);
        }

        if (node.isColumn()) {
            if (node.isRecno()
                || (node.getColumnName() != null && "RECNO".equalsIgnoreCase(node.getColumnName()))) {
                return currentRecno > 0 ? (long) currentRecno : null;
            }
            int idx = columnIndex(node.getTableName(), node.getColumnName());
            return (idx >= 0 && idx < row.length) ? row[idx] : null;
        }

        TokenType type = node.getType();
        switch (type) {
            case AND:
                return toBoolean(evaluate(node.getLeft(), row))
                    && toBoolean(evaluate(node.getRight(), row));
            case OR:
                return toBoolean(evaluate(node.getLeft(), row))
                    || toBoolean(evaluate(node.getRight(), row));
            case NOT:
                return !toBoolean(evaluate(node.getLeft(), row));
            case EQ: case NE: case LT: case GT: case LE: case GE:
                return compare(evaluate(node.getLeft(), row),
                    evaluate(node.getRight(), row), type);
            case LIKE: {
                Object value = evaluate(node.getLeft(), row);
                Object pattern = evaluate(node.getRight(), row);
                if (value == null || pattern == null) return false;
                // Space-sensitive: match the value exactly as stored (no trim),
                // so patterns control any leading/trailing spaces themselves.
                return likePattern(pattern.toString())
                    .matcher(value.toString()).matches();
            }
            case PLUS: case MINUS: case MULTIPLY: case STAR: case DIVIDE: {
                Object l = evaluate(node.getLeft(), row);
                Object r = evaluate(node.getRight(), row);
                if (l == null || r == null) return null;
                // String concatenation with + when either side is non-numeric
                if (type == TokenType.PLUS && (!isNumeric(l) || !isNumeric(r))) {
                    return l.toString() + r.toString();
                }
                double a = toDouble(l);
                double b = toDouble(r);
                // Type-preserving: int op int -> int; if either side is
                // fractional (Double), the result is Double (int + double =
                // double). Division yields int only when it divides evenly.
                boolean bothInt = isIntegerValue(l) && isIntegerValue(r);
                switch (type) {
                    case PLUS:
                        if (bothInt) return (long) (a + b);
                        return a + b;
                    case MINUS:
                        if (bothInt) return (long) (a - b);
                        return a - b;
                    case DIVIDE:
                        if (b == 0) return null;
                        if (bothInt && (long) a % (long) b == 0) return (long) a / (long) b;
                        return a / b;
                    default: // MULTIPLY / STAR
                        if (bothInt) return (long) (a * b);
                        return a * b;
                }
            }
            default:
                return null;
        }
    }

    /**
     * Whether a value is an integer-typed number (so arithmetic/aggregates keep
     * it integer). DBF NUMERIC(n,0) fields decode to {@code Long} and integer
     * literals are {@code Long}; NUMERIC(n,d>0) fields and decimal literals are
     * {@code Double}. A numeric string with no decimal point counts as integer.
     */
    public static boolean isIntegerValue(Object o) {
        if (o instanceof Long || o instanceof Integer
                || o instanceof Short || o instanceof Byte) {
            return true;
        }
        if (o instanceof Number) {
            return false; // Double / Float / BigDecimal -> fractional type
        }
        if (o instanceof String) {
            String s = ((String) o).trim();
            if (s.isEmpty() || s.indexOf('.') >= 0) return false;
            try {
                Long.parseLong(s);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    private Object literal(ExpressionNode node) {
        if (node.getLiteralValue() != null) {
            return node.getLiteralValue();
        }
        switch (node.getType()) {
            case NUMBER:
                String v = node.getValue();
                if (v.contains(".")) return Double.parseDouble(v);
                try {
                    return Long.parseLong(v);
                } catch (NumberFormatException e) {
                    return Double.parseDouble(v);
                }
            case STRING:
                return node.getValue();
            case NULL:
                return null;
            default:
                return node.getValue();
        }
    }

    private boolean compare(Object left, Object right, TokenType op) {
        if (left == null && right == null) {
            return op == TokenType.EQ;
        }
        if (left == null || right == null) {
            return op == TokenType.NE;
        }
        // Cheap pre-check before the numeric attempt: without it, a filter like
        // TYPE='A' made Double.parseDouble("A") THROW for every scanned row
        // (exception fill-in dominated filtered-scan time). Semantics unchanged:
        // pre-check false-positives ("12AB") still fall through via the catch.
        if (couldBeNumeric(left) && couldBeNumeric(right)) {
            try {
                double l = toDouble(left);
                double r = toDouble(right);
                switch (op) {
                    case EQ: return Math.abs(l - r) < 1e-10;
                    case NE: return Math.abs(l - r) >= 1e-10;
                    case LT: return l < r;
                    case GT: return l > r;
                    case LE: return l <= r;
                    case GE: return l >= r;
                    default: return false;
                }
            } catch (NumberFormatException e) {
                // fall through to the string comparison below
            }
        }
        // xBase/Clipper string comparison is case-SENSITIVE and binary (by
        // ASCII code), and space-sensitive (values compared exactly as
        // stored, no trimming). This matches the dBASE ODBC engine: e.g.
        // lowercase 'i' (105) is NOT within 'A'..'Z' (65..90), so a filter
        // like TYPE>='A' AND TYPE<='Z' excludes it. Use UCASE()/TRIM() in
        // SQL for case- or padding-insensitive matches.
        String l = left.toString();
        String r = right.toString();
        switch (op) {
            case EQ: return l.equals(r);
            case NE: return !l.equals(r);
            case LT: return l.compareTo(r) < 0;
            case GT: return l.compareTo(r) > 0;
            case LE: return l.compareTo(r) <= 0;
            case GE: return l.compareTo(r) >= 0;
            default: return false;
        }
    }

    /**
     * Cheap no-exception test for "might parse as a number": Numbers always;
     * strings only when the first non-space char could start a numeric literal
     * ('N'/'I' kept so "NaN"/"Infinity" still reach the parse, as before).
     */
    private static boolean couldBeNumeric(Object v) {
        if (v instanceof Number) return true;
        String s = v.toString();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t') continue;
            return (c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.'
                || c == 'N' || c == 'I';
        }
        return false; // all-blank never parses
    }

    private Pattern likePattern(String sqlPattern) {
        Pattern cached = likeCache.get(sqlPattern);
        if (cached != null) return cached;
        StringBuilder regex = new StringBuilder();
        for (char c : sqlPattern.toCharArray()) {
            switch (c) {
                case '%': regex.append(".*"); break;
                case '_': regex.append('.'); break;
                default: regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        // Case-sensitive to match xBase/Clipper (use UCASE() for case-insensitive LIKE).
        Pattern p = Pattern.compile(regex.toString(), Pattern.DOTALL);
        likeCache.put(sqlPattern, p);
        return p;
    }

    private static boolean isNumeric(Object o) {
        if (o instanceof Number) return true;
        try {
            String s = o.toString().trim();
            if (s.isEmpty()) return false;
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static double toDouble(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof Boolean) return ((Boolean) o) ? 1 : 0;
        String s = o.toString().trim();
        if (s.isEmpty()) throw new NumberFormatException("empty");
        return Double.parseDouble(s);
    }

    private static boolean toBoolean(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof Number) return ((Number) o).doubleValue() != 0;
        String s = o.toString().trim();
        return !s.isEmpty();
    }
}
