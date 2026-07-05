package com.dbf.jdbc.resultset;

import com.dbf.jdbc.dbf.DBFField;
import com.dbf.jdbc.parser.TokenType;
import com.dbf.jdbc.parser.ast.ExpressionNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Evaluates WHERE clause conditions against full (unprojected) rows.
 * Column references are resolved against the DBF field list, so the
 * evaluation is independent of the SELECT projection. RECNO() is
 * supported via the record number passed to {@link #matches}.
 */
public class FilterEngine {
    private final ExpressionNode condition;
    private final Map<String, Integer> fieldIndexByName = new HashMap<>();
    private final Map<String, Pattern> likeCache = new HashMap<>();

    public FilterEngine(ExpressionNode condition, List<DBFField> fields) {
        this.condition = condition;
        if (fields != null) {
            for (int i = 0; i < fields.size(); i++) {
                fieldIndexByName.put(fields.get(i).getName().toLowerCase(), i);
            }
        }
    }

    /**
     * @param fullRow the complete record (all DBF fields, in file order)
     * @param recno   the 1-based record number, or -1 if not applicable
     */
    public boolean matches(Object[] fullRow, int recno) {
        if (condition == null) return true;
        return evaluateBoolean(condition, fullRow, recno);
    }

    public boolean matches(Object[] fullRow) {
        return matches(fullRow, -1);
    }

    private boolean evaluateBoolean(ExpressionNode node, Object[] row, int recno) {
        if (node == null) return true;

        TokenType type = node.getType();
        if (type == TokenType.AND) {
            return evaluateBoolean(node.getLeft(), row, recno)
                && evaluateBoolean(node.getRight(), row, recno);
        }
        if (type == TokenType.OR) {
            return evaluateBoolean(node.getLeft(), row, recno)
                || evaluateBoolean(node.getRight(), row, recno);
        }
        if (type == TokenType.NOT) {
            return !evaluateBoolean(node.getLeft(), row, recno);
        }
        if (type == TokenType.LIKE) {
            Object value = evaluateValue(node.getLeft(), row, recno);
            Object pattern = evaluateValue(node.getRight(), row, recno);
            if (value == null || pattern == null) return false;
            // Space-sensitive: match the value exactly as stored (no trim).
            return likePattern(pattern.toString()).matcher(value.toString()).matches();
        }
        if (isComparison(type)) {
            Object left = evaluateValue(node.getLeft(), row, recno);
            Object right = evaluateValue(node.getRight(), row, recno);
            return compareValues(left, right, type);
        }

        Object value = evaluateValue(node, row, recno);
        if (value instanceof Boolean) return (Boolean) value;
        return value != null;
    }

    private boolean isComparison(TokenType type) {
        return type == TokenType.EQ || type == TokenType.NE || type == TokenType.LT
            || type == TokenType.GT || type == TokenType.LE || type == TokenType.GE;
    }

    private Object evaluateValue(ExpressionNode node, Object[] row, int recno) {
        if (node == null) return null;

        if (node.isFunction()) {
            java.util.List<Object> args = new java.util.ArrayList<>();
            if (node.getArguments() != null) {
                for (ExpressionNode arg : node.getArguments()) {
                    args.add(evaluateValue(arg, row, recno));
                }
            }
            try {
                return com.dbf.jdbc.execution.eval.FunctionLibrary.call(
                    node.getFunctionName(), args);
            } catch (java.sql.SQLException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }

        if (node.isRecno()
            || (node.getColumnName() != null && "RECNO".equalsIgnoreCase(node.getColumnName()))) {
            return recno > 0 ? (long) recno : null;
        }

        if (node.isLiteral()) {
            switch (node.getType()) {
                case NUMBER:
                    String val = node.getValue();
                    if (val.contains(".")) {
                        return Double.parseDouble(val);
                    }
                    try {
                        return Long.parseLong(val);
                    } catch (NumberFormatException e) {
                        return Double.parseDouble(val);
                    }
                case STRING:
                    return node.getValue();
                case NULL:
                    return null;
                default:
                    return node.getLiteralValue() != null ? node.getLiteralValue() : node.getValue();
            }
        }

        if (node.isColumn()) {
            String columnName = node.getColumnName() != null ? node.getColumnName() : node.getValue();
            Integer idx = fieldIndexByName.get(columnName.toLowerCase());
            if (idx != null && idx < row.length) {
                return row[idx];
            }
            return null;
        }

        TokenType type = node.getType();
        if (type == TokenType.PLUS || type == TokenType.MINUS
            || type == TokenType.MULTIPLY || type == TokenType.STAR
            || type == TokenType.DIVIDE) {
            Object left = evaluateValue(node.getLeft(), row, recno);
            Object right = evaluateValue(node.getRight(), row, recno);
            if (left == null || right == null) return null;
            try {
                double l = toDouble(left);
                double r = toDouble(right);
                switch (type) {
                    case PLUS: return l + r;
                    case MINUS: return l - r;
                    case DIVIDE: return r != 0 ? l / r : null;
                    default: return l * r;
                }
            } catch (NumberFormatException e) {
                return null;
            }
        }

        if (isComparison(type) || type == TokenType.AND || type == TokenType.OR
            || type == TokenType.NOT || type == TokenType.LIKE) {
            return evaluateBoolean(node, row, recno);
        }

        return null;
    }

    private boolean compareValues(Object left, Object right, TokenType op) {
        if (left == null && right == null) {
            return op == TokenType.EQ;
        }
        if (left == null || right == null) {
            return op == TokenType.NE;
        }

        // Numeric compare only when both sides could plausibly parse as numbers.
        // The cheap pre-check avoids the old exception-per-row dispatch: with a
        // filter like TYPE='A', Double.parseDouble("A") used to THROW for every
        // scanned row (~1us each to fill in the stack trace) -- the dominant
        // cost of a filtered scan. Semantics are unchanged: the parse+catch
        // below still decides for pre-check false-positives like "12AB".
        if (couldBeNumeric(left) && couldBeNumeric(right)) {
            try {
                double lnum = toDouble(left);
                double rnum = toDouble(right);

                switch (op) {
                    case EQ: return Math.abs(lnum - rnum) < 1e-10;
                    case NE: return Math.abs(lnum - rnum) >= 1e-10;
                    case LT: return lnum < rnum;
                    case GT: return lnum > rnum;
                    case LE: return lnum <= rnum;
                    case GE: return lnum >= rnum;
                    default: return false;
                }
            } catch (NumberFormatException e) {
                // fall through to the string comparison below
            }
        }
        // xBase/Clipper string comparison: case-SENSITIVE, binary (by ASCII
        // code), and space-sensitive (compared exactly as stored, no trim).
        // Matches the dBASE ODBC engine (e.g. lowercase 'i'=105 is NOT in
        // 'A'..'Z'=65..90). Use UCASE()/TRIM() for case-/space-insensitivity.
        String lstr = left.toString();
        String rstr = right.toString();

        switch (op) {
            case EQ: return lstr.equals(rstr);
            case NE: return !lstr.equals(rstr);
            case LT: return lstr.compareTo(rstr) < 0;
            case GT: return lstr.compareTo(rstr) > 0;
            case LE: return lstr.compareTo(rstr) <= 0;
            case GE: return lstr.compareTo(rstr) >= 0;
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

    /** Converts a SQL LIKE pattern (% and _) to a case-sensitive regex (xBase). */
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
        Pattern pattern = Pattern.compile(regex.toString(), Pattern.DOTALL);
        likeCache.put(sqlPattern, pattern);
        return pattern;
    }

    private double toDouble(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        String s = value.toString().trim();
        if (s.isEmpty()) throw new NumberFormatException("empty");
        return Double.parseDouble(s);
    }
}
