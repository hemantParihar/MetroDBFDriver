package com.dbf.jdbc.index.ntx;

import com.dbf.jdbc.dbf.DBFField;

import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Reproduces the exact key bytes Clipper writes for an index key expression,
 * evaluated against one DBF record. This is the foundation of NTX writing: to
 * maintain an index we must produce byte-for-byte the same key Clipper would,
 * or the file becomes unreadable by the user's other tools.
 *
 * <p>Supported Clipper functions (the ones seen in real METRO indexes):
 * field references, string concatenation with {@code +}, UPPER/LOWER,
 * LTRIM/RTRIM/TRIM/ALLTRIM, STR (space-padded, right-justified), SUBS/SUBSTR,
 * RECNO/RECN, YEAR/MONTH/DAY, DTOS, and quoted literals. Anything else makes
 * {@link #canEvaluate} return false, so callers refuse to maintain that index
 * rather than risk corrupting it.
 *
 * <p>Correctness is not assumed: {@code NtxKeyValidateRun} checks this against
 * the keys actually stored in the user's .NTX files.
 */
public final class NtxKeyEvaluator {

    private final Map<String, DBFField> fieldsByName = new HashMap<>();
    private final List<DBFField> fields;

    public NtxKeyEvaluator(List<DBFField> fields) {
        this.fields = fields;
        for (DBFField f : fields) {
            fieldsByName.put(f.getName().toUpperCase(), f);
        }
    }

    /**
     * @param values resolves a field name to its decoded value (String/Number/Date)
     * @param recno  1-based record number, for RECNO()/RECN()
     * @return the key bytes (ISO-8859-1), or null if the expression uses an
     *         unsupported construct (caller must then not maintain this index)
     */
    public byte[] evaluate(String keyExpr, Function<String, Object> values, long recno) {
        try {
            StringBuilder sb = new StringBuilder();
            for (String term : splitTopLevel(keyExpr, '+')) {
                String s = evalString(term.trim(), values, recno);
                if (s == null) {
                    return null;
                }
                sb.append(s);
            }
            return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** True if the key expression uses only constructs this evaluator supports. */
    public boolean canEvaluate(String keyExpr, long sampleRecno) {
        return evaluate(keyExpr, name -> sampleValue(name), sampleRecno) != null;
    }

    private Object sampleValue(String name) {
        DBFField f = fieldsByName.get(name.toUpperCase());
        if (f == null) {
            return null;
        }
        switch (Character.toUpperCase(f.getType())) {
            case 'N': case 'F': case 'O': case 'I': return 0;
            case 'D': return Date.valueOf("2000-01-01");
            default: return "";
        }
    }

    // ==================== evaluation ====================

    /** Evaluates one term to its string contribution (Clipper semantics). */
    private String evalString(String term, Function<String, Object> values, long recno) {
        if (term.isEmpty()) {
            return "";
        }
        // Quoted literal
        if ((term.startsWith("'") && term.endsWith("'"))
                || (term.startsWith("\"") && term.endsWith("\""))) {
            return term.substring(1, term.length() - 1);
        }

        Call call = asCall(term);
        if (call != null) {
            return evalFunction(call, values, recno);
        }

        // Bare identifier -> a field; char fields contribute their fixed-width
        // value (padded with spaces, matching how DBF stores them).
        DBFField f = fieldsByName.get(term.toUpperCase());
        if (f != null) {
            char type = Character.toUpperCase(f.getType());
            if (type == 'C') {
                Object v = values.apply(f.getName());
                return padRight(v == null ? "" : v.toString(), f.getLength());
            }
            // A bare non-char field in a key is unusual; refuse rather than guess.
            return null;
        }
        return null; // unknown token
    }

    private String evalFunction(Call call, Function<String, Object> values, long recno) {
        String fn = call.name.toUpperCase();
        List<String> a = call.args;
        switch (fn) {
            case "RECNO":
            case "RECN":
                return Long.toString(recno);
            case "UPPER":
            case "UCASE": {
                String s = evalString(a.get(0), values, recno);
                return s == null ? null : s.toUpperCase();
            }
            case "LOWER":
            case "LCASE": {
                String s = evalString(a.get(0), values, recno);
                return s == null ? null : s.toLowerCase();
            }
            case "LTRIM": {
                String s = evalString(a.get(0), values, recno);
                return s == null ? null : s.replaceAll("^ +", "");
            }
            case "RTRIM":
            case "TRIM": {
                String s = evalString(a.get(0), values, recno);
                return s == null ? null : s.replaceAll(" +$", "");
            }
            case "ALLTRIM": {
                String s = evalString(a.get(0), values, recno);
                return s == null ? null : s.trim();
            }
            case "SUBS":
            case "SUBSTR": {
                String s = evalString(a.get(0), values, recno);
                if (s == null) return null;
                int start = (int) evalNumber(a.get(1), values, recno);
                int len = a.size() > 2 ? (int) evalNumber(a.get(2), values, recno)
                                       : s.length() - start + 1;
                int from = Math.max(0, start - 1);
                int to = Math.min(s.length(), from + Math.max(0, len));
                return from <= to ? s.substring(from, to) : "";
            }
            case "DTOS": {
                Object v = fieldValue(a.get(0), values);
                if (v instanceof Date) {
                    return ((Date) v).toString().replace("-", ""); // YYYYMMDD
                }
                return null;
            }
            case "YEAR":
            case "MONTH":
            case "DAY":
                return Long.toString((long) evalNumber(call.raw, values, recno));
            case "STR": {
                double n = evalNumber(a.get(0), values, recno);
                int width = a.size() > 1 ? (int) constNumber(a.get(1)) : 10;
                int dec = a.size() > 2 ? (int) constNumber(a.get(2)) : 0;
                return clipperStr(n, width, dec);
            }
            default:
                return null; // unsupported function
        }
    }

    /** Evaluates a numeric sub-expression (field, YEAR/MONTH/DAY, literal). */
    private double evalNumber(String term, Function<String, Object> values, long recno) {
        term = term.trim();
        Call call = asCall(term);
        if (call != null) {
            String fn = call.name.toUpperCase();
            if (fn.equals("RECNO") || fn.equals("RECN")) {
                return recno;
            }
            if (fn.equals("YEAR") || fn.equals("MONTH") || fn.equals("DAY")) {
                Object v = fieldValue(call.args.get(0), values);
                if (v instanceof Date) {
                    Date d = (Date) v;
                    String[] p = d.toString().split("-");
                    int idx = fn.equals("YEAR") ? 0 : fn.equals("MONTH") ? 1 : 2;
                    return Integer.parseInt(p[idx]);
                }
                return 0;
            }
            return 0;
        }
        DBFField f = fieldsByName.get(term.toUpperCase());
        if (f != null) {
            Object v = values.apply(f.getName());
            if (v instanceof Number) {
                return ((Number) v).doubleValue();
            }
            if (v != null) {
                try {
                    return Double.parseDouble(v.toString().trim());
                } catch (NumberFormatException ignore) {
                    return 0;
                }
            }
            return 0;
        }
        // A bare identifier that is not one of this table's fields means the
        // expression belongs to another table (or uses an unknown name) -- fail
        // the whole evaluation so the index is treated as unsupported.
        if (term.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalStateException("unknown field: " + term);
        }
        return constNumber(term);
    }

    private Object fieldValue(String name, Function<String, Object> values) {
        DBFField f = fieldsByName.get(name.trim().toUpperCase());
        return f == null ? null : values.apply(f.getName());
    }

    private static double constNumber(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Clipper STR(): right-justified, space-padded; '*' fill on overflow. */
    private static String clipperStr(double value, int width, int dec) {
        String body;
        if (dec > 0) {
            body = String.format("%." + dec + "f", value);
        } else {
            body = Long.toString(Math.round(value));
        }
        if (body.length() > width) {
            StringBuilder stars = new StringBuilder();
            for (int i = 0; i < width; i++) stars.append('*');
            return stars.toString();
        }
        return padLeft(body, width);
    }

    // ==================== tiny parser ====================

    private static final class Call {
        final String name;
        final List<String> args;
        final String raw;

        Call(String name, List<String> args, String raw) {
            this.name = name;
            this.args = args;
            this.raw = raw;
        }
    }

    /** Parses {@code NAME(arg, arg, ...)} or returns null if not a call. */
    private static Call asCall(String term) {
        int lp = term.indexOf('(');
        if (lp <= 0 || !term.endsWith(")")) {
            return null;
        }
        String name = term.substring(0, lp).trim();
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return null;
        }
        String inside = term.substring(lp + 1, term.length() - 1);
        return new Call(name, splitTopLevel(inside, ','), term);
    }

    private static List<String> splitTopLevel(String expr, char sep) {
        List<String> parts = new java.util.ArrayList<>();
        int depth = 0;
        int start = 0;
        boolean inStr = false;
        char q = 0;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (inStr) {
                if (c == q) inStr = false;
            } else if (c == '\'' || c == '"') {
                inStr = true;
                q = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth > 0) depth--;
            } else if (c == sep && depth == 0) {
                parts.add(expr.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(expr.substring(start));
        return parts;
    }

    private static String padRight(String s, int width) {
        if (s.length() >= width) {
            return s.substring(0, width);
        }
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) sb.append(' ');
        return sb.toString();
    }

    private static String padLeft(String s, int width) {
        if (s.length() >= width) {
            return s.substring(0, width);
        }
        StringBuilder sb = new StringBuilder();
        while (sb.length() < width - s.length()) sb.append(' ');
        sb.append(s);
        return sb.toString();
    }
}
