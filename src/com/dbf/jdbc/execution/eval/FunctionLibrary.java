package com.dbf.jdbc.execution.eval;

import java.sql.SQLException;
import java.util.List;

/**
 * Scalar SQL / xBase functions used in SELECT, WHERE and ORDER BY.
 * Arguments arrive already evaluated; each function returns a Java value
 * ({@code String}, {@code Number}, {@code Boolean}, {@code java.util.Date}
 * or {@code null}).
 *
 * Supported: ISNULL, ISBLANK, NVL, COALESCE, IIF, UPPER/UCASE,
 * LOWER/LCASE, TRIM/ALLTRIM, LTRIM, RTRIM, LEN/LENGTH, LEFT, RIGHT,
 * SUBSTR/SUBSTRING, STR, VAL, ABS, ROUND, INT, MOD, CONCAT.
 */
public final class FunctionLibrary {

    private FunctionLibrary() {
    }

    public static boolean isFunction(String name) {
        switch (name.toUpperCase()) {
            // conditional / null handling
            case "ISNULL": case "ISBLANK": case "NVL": case "NVL2": case "COALESCE":
            case "NULLIF": case "IIF": case "DECODE": case "GREATEST": case "LEAST":
            // string
            case "UPPER": case "UCASE": case "LOWER": case "LCASE":
            case "TRIM": case "ALLTRIM": case "LTRIM": case "RTRIM":
            case "LEN": case "LENGTH": case "CHAR_LENGTH": case "CHARACTER_LENGTH":
            case "OCTET_LENGTH": case "LEFT": case "RIGHT":
            case "SUBSTR": case "SUBSTRING": case "MID": case "STR": case "VAL": case "CONCAT":
            case "REPLACE": case "LPAD": case "RPAD": case "REPEAT": case "SPACE":
            case "REVERSE": case "ASCII": case "CHR": case "CHAR": case "INITCAP":
            case "LOCATE": case "INSTR": case "POSITION":
            // numeric
            case "ABS": case "ROUND": case "INT": case "MOD": case "CEIL": case "CEILING":
            case "FLOOR": case "POWER": case "POW": case "SQRT": case "EXP":
            case "LN": case "LOG": case "LOG10": case "SIGN": case "TRUNC":
            case "TRUNCATE": case "PI": case "RAND": case "RANDOM":
            // datetime
            case "YEAR": case "MONTH": case "DAY": case "HOUR": case "MINUTE": case "SECOND":
            case "CURRENT_DATE": case "CURRENT_TIME": case "CURRENT_TIMESTAMP": case "NOW":
            case "DATEDIFF": case "DATEADD": case "DAYOFWEEK": case "DAYOFYEAR":
            case "QUARTER": case "WEEK": case "LAST_DAY": case "MONTHNAME": case "DAYNAME":
            case "TO_DATE": case "CAST":
                return true;
            default:
                return false;
        }
    }

    public static Object call(String name, List<Object> args) throws SQLException {
        switch (name.toUpperCase()) {
            case "ISNULL":
                // 1 arg: VFP-style boolean null test; 2 args: SQL-Server coalesce
                if (args.size() == 1) {
                    return args.get(0) == null;
                }
                requireArgs(name, args, 2);
                return args.get(0) != null ? args.get(0) : args.get(1);

            case "ISBLANK": {
                requireArgs(name, args, 1);
                Object v = args.get(0);
                return v == null || v.toString().trim().isEmpty();
            }

            case "NVL":
                requireArgs(name, args, 2);
                return args.get(0) != null ? args.get(0) : args.get(1);

            case "COALESCE":
                for (Object a : args) {
                    if (a != null) return a;
                }
                return null;

            case "IIF":
                requireArgs(name, args, 3);
                return toBoolean(args.get(0)) ? args.get(1) : args.get(2);

            case "UPPER":
            case "UCASE":
                return args.get(0) == null ? null : str(args.get(0)).toUpperCase();

            case "LOWER":
            case "LCASE":
                return args.get(0) == null ? null : str(args.get(0)).toLowerCase();

            case "TRIM":
            case "ALLTRIM":
                return args.get(0) == null ? null : str(args.get(0)).trim();

            case "LTRIM":
                return args.get(0) == null ? null : stripLeading(str(args.get(0)));

            case "RTRIM":
                return args.get(0) == null ? null : stripTrailing(str(args.get(0)));

            case "LEN":
            case "LENGTH":
                return args.get(0) == null ? 0L : (long) str(args.get(0)).length();

            case "LEFT": {
                requireArgs(name, args, 2);
                if (args.get(0) == null) return null;
                String s = str(args.get(0));
                int n = (int) toLong(args.get(1));
                return s.substring(0, Math.max(0, Math.min(n, s.length())));
            }

            case "RIGHT": {
                requireArgs(name, args, 2);
                if (args.get(0) == null) return null;
                String s = str(args.get(0));
                int n = (int) toLong(args.get(1));
                n = Math.max(0, Math.min(n, s.length()));
                return s.substring(s.length() - n);
            }

            case "SUBSTR":
            case "SUBSTRING":
            case "MID": {
                if (args.get(0) == null) return null;
                String s = str(args.get(0));
                int start = (int) toLong(args.get(1)); // 1-based, like xBase
                int from = Math.max(0, start - 1);
                if (from >= s.length()) return "";
                int len = args.size() >= 3 ? (int) toLong(args.get(2)) : s.length() - from;
                int to = Math.max(from, Math.min(s.length(), from + len));
                return s.substring(from, to);
            }

            case "STR": {
                if (args.get(0) == null) return null;
                double d = toDouble(args.get(0));
                if (args.size() >= 3) {
                    int dec = (int) toLong(args.get(2));
                    return String.valueOf(round(d, dec));
                }
                return String.valueOf((long) d);
            }

            case "VAL": {
                if (args.get(0) == null) return null;
                try {
                    return Double.parseDouble(str(args.get(0)).trim());
                } catch (NumberFormatException e) {
                    return 0.0;
                }
            }

            case "ABS":
                return args.get(0) == null ? null : Math.abs(toDouble(args.get(0)));

            case "ROUND":
                requireArgs(name, args, 2);
                return args.get(0) == null ? null
                    : round(toDouble(args.get(0)), (int) toLong(args.get(1)));

            case "INT":
                return args.get(0) == null ? null : (long) toDouble(args.get(0));

            case "MOD":
                requireArgs(name, args, 2);
                return toDouble(args.get(0)) % toDouble(args.get(1));

            case "CONCAT": {
                StringBuilder sb = new StringBuilder();
                for (Object a : args) {
                    if (a != null) sb.append(str(a));
                }
                return sb.toString();
            }

            case "YEAR":
                return args.get(0) == null ? null : (long) toDate(args.get(0)).getYear();
            case "MONTH":
                return args.get(0) == null ? null : (long) toDate(args.get(0)).getMonthValue();
            case "DAY":
                return args.get(0) == null ? null : (long) toDate(args.get(0)).getDayOfMonth();

            // ==================== conditional / null handling ====================

            case "NVL2": // NVL2(x, ifNotNull, ifNull)
                requireArgs(name, args, 3);
                return args.get(0) != null ? args.get(1) : args.get(2);

            case "NULLIF":
                requireArgs(name, args, 2);
                return valuesEqual(args.get(0), args.get(1)) ? null : args.get(0);

            case "GREATEST":
            case "LEAST": {
                Object best = null;
                for (Object a : args) {
                    if (a == null) continue;
                    if (best == null
                        || (name.equalsIgnoreCase("GREATEST") ? compareValues(a, best) > 0
                                                               : compareValues(a, best) < 0)) {
                        best = a;
                    }
                }
                return best;
            }

            case "DECODE": { // DECODE(expr, s1, r1, [s2, r2, ...], [default])
                requireArgs(name, args, 3);
                Object expr = args.get(0);
                int i = 1;
                for (; i + 1 < args.size(); i += 2) {
                    if (valuesEqual(expr, args.get(i))) return args.get(i + 1);
                }
                return i < args.size() ? args.get(i) : null; // trailing default, else null
            }

            // ==================== string ====================

            case "CHAR_LENGTH":
            case "CHARACTER_LENGTH":
                return args.get(0) == null ? null : (long) str(args.get(0)).length();

            case "OCTET_LENGTH":
                return args.get(0) == null ? null
                    : (long) str(args.get(0)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;

            case "REPLACE": { // REPLACE(s, search, replacement)
                requireArgs(name, args, 3);
                if (args.get(0) == null) return null;
                return str(args.get(0)).replace(str(args.get(1)), str(args.get(2)));
            }

            case "LPAD":
            case "RPAD": {
                requireArgs(name, args, 2);
                if (args.get(0) == null) return null;
                String s = str(args.get(0));
                int len = (int) toLong(args.get(1));
                String pad = args.size() >= 3 ? str(args.get(2)) : " ";
                return name.equalsIgnoreCase("LPAD") ? pad(s, len, pad, true)
                                                     : pad(s, len, pad, false);
            }

            case "REPEAT": {
                requireArgs(name, args, 2);
                if (args.get(0) == null) return null;
                int n = Math.max(0, (int) toLong(args.get(1)));
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < n; i++) sb.append(str(args.get(0)));
                return sb.toString();
            }

            case "SPACE": {
                int n = Math.max(0, (int) toLong(args.get(0)));
                char[] sp = new char[n];
                java.util.Arrays.fill(sp, ' ');
                return new String(sp);
            }

            case "REVERSE":
                return args.get(0) == null ? null
                    : new StringBuilder(str(args.get(0))).reverse().toString();

            case "ASCII":
                if (args.get(0) == null) return null;
                String as = str(args.get(0));
                return as.isEmpty() ? 0L : (long) as.charAt(0);

            case "CHR":
            case "CHAR":
                return args.get(0) == null ? null
                    : String.valueOf((char) (int) toLong(args.get(0)));

            case "INITCAP": {
                if (args.get(0) == null) return null;
                StringBuilder sb = new StringBuilder();
                boolean start = true;
                for (char ch : str(args.get(0)).toCharArray()) {
                    if (Character.isLetterOrDigit(ch)) {
                        sb.append(start ? Character.toUpperCase(ch) : Character.toLowerCase(ch));
                        start = false;
                    } else {
                        sb.append(ch);
                        start = true;
                    }
                }
                return sb.toString();
            }

            case "LOCATE":   // LOCATE(sub, str [, start])  -> 1-based, 0 if not found
            case "POSITION": // POSITION(sub, str)
            case "INSTR": {  // INSTR(str, sub)  (Oracle arg order)
                requireArgs(name, args, 2);
                String hay;
                String needle;
                int start = 0;
                if (name.equalsIgnoreCase("INSTR")) {
                    hay = str(args.get(0));
                    needle = str(args.get(1));
                } else {
                    needle = str(args.get(0));
                    hay = str(args.get(1));
                    if (args.size() >= 3) start = Math.max(0, (int) toLong(args.get(2)) - 1);
                }
                int idx = hay.indexOf(needle, start);
                return (long) (idx + 1);
            }

            // ==================== numeric ====================

            case "CEIL":
            case "CEILING":
                return args.get(0) == null ? null : (long) Math.ceil(toDouble(args.get(0)));

            case "FLOOR":
                return args.get(0) == null ? null : (long) Math.floor(toDouble(args.get(0)));

            case "POWER":
            case "POW":
                requireArgs(name, args, 2);
                return Math.pow(toDouble(args.get(0)), toDouble(args.get(1)));

            case "SQRT":
                return args.get(0) == null ? null : Math.sqrt(toDouble(args.get(0)));

            case "EXP":
                return args.get(0) == null ? null : Math.exp(toDouble(args.get(0)));

            case "LN":
                return args.get(0) == null ? null : Math.log(toDouble(args.get(0)));

            case "LOG": // LOG(x) = natural log; LOG(base, x)
                if (args.get(0) == null) return null;
                if (args.size() >= 2) {
                    return Math.log(toDouble(args.get(1))) / Math.log(toDouble(args.get(0)));
                }
                return Math.log(toDouble(args.get(0)));

            case "LOG10":
                return args.get(0) == null ? null : Math.log10(toDouble(args.get(0)));

            case "SIGN":
                return args.get(0) == null ? null : (long) Math.signum(toDouble(args.get(0)));

            case "TRUNC":
            case "TRUNCATE": {
                if (args.get(0) == null) return null;
                int dec = args.size() >= 2 ? (int) toLong(args.get(1)) : 0;
                double factor = Math.pow(10, dec);
                double v = toDouble(args.get(0));
                return (v < 0 ? Math.ceil(v * factor) : Math.floor(v * factor)) / factor;
            }

            case "PI":
                return Math.PI;

            case "RAND":
            case "RANDOM":
                return Math.random();

            // ==================== datetime ====================

            case "HOUR":
                return args.get(0) == null ? null : (long) toTimestamp(args.get(0)).toLocalDateTime().getHour();
            case "MINUTE":
                return args.get(0) == null ? null : (long) toTimestamp(args.get(0)).toLocalDateTime().getMinute();
            case "SECOND":
                return args.get(0) == null ? null : (long) toTimestamp(args.get(0)).toLocalDateTime().getSecond();

            case "CURRENT_DATE":
                return new java.sql.Date(System.currentTimeMillis());
            case "CURRENT_TIME":
                return new java.sql.Time(System.currentTimeMillis());
            case "CURRENT_TIMESTAMP":
            case "NOW":
                return new java.sql.Timestamp(System.currentTimeMillis());

            case "DATEDIFF": { // DATEDIFF(d1, d2) -> whole days d1 - d2
                requireArgs(name, args, 2);
                if (args.get(0) == null || args.get(1) == null) return null;
                return java.time.temporal.ChronoUnit.DAYS.between(
                    toDate(args.get(1)), toDate(args.get(0)));
            }

            case "DATEADD": { // DATEADD(date, days) -> date + days
                requireArgs(name, args, 2);
                if (args.get(0) == null) return null;
                java.time.LocalDate d = toDate(args.get(0)).plusDays(toLong(args.get(1)));
                return java.sql.Date.valueOf(d);
            }

            case "DAYOFWEEK": // 1=Sunday .. 7=Saturday (SQL/ODBC convention)
                return args.get(0) == null ? null
                    : (long) (toDate(args.get(0)).getDayOfWeek().getValue() % 7 + 1);
            case "DAYOFYEAR":
                return args.get(0) == null ? null : (long) toDate(args.get(0)).getDayOfYear();
            case "QUARTER":
                return args.get(0) == null ? null
                    : (long) ((toDate(args.get(0)).getMonthValue() - 1) / 3 + 1);
            case "WEEK":
                return args.get(0) == null ? null : (long) toDate(args.get(0))
                    .get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            case "LAST_DAY": {
                if (args.get(0) == null) return null;
                java.time.LocalDate d = toDate(args.get(0));
                return java.sql.Date.valueOf(d.withDayOfMonth(d.lengthOfMonth()));
            }
            case "MONTHNAME":
                return args.get(0) == null ? null
                    : toDate(args.get(0)).getMonth().getDisplayName(
                        java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH);
            case "DAYNAME":
                return args.get(0) == null ? null
                    : toDate(args.get(0)).getDayOfWeek().getDisplayName(
                        java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH);

            case "TO_DATE":
                return args.get(0) == null ? null : java.sql.Date.valueOf(toDate(args.get(0)));

            case "CAST": { // CAST(value, 'TYPE') -- desugared from CAST(value AS TYPE)
                requireArgs(name, args, 2);
                Object v = args.get(0);
                if (v == null) return null;
                String type = str(args.get(1)).toUpperCase().trim();
                if (type.startsWith("INT") || type.equals("BIGINT") || type.equals("SMALLINT")
                        || type.equals("TINYINT")) {
                    return (long) toDouble(v);
                }
                if (type.startsWith("NUMERIC") || type.startsWith("DECIMAL")
                        || type.startsWith("FLOAT") || type.startsWith("DOUBLE")
                        || type.startsWith("REAL") || type.startsWith("DEC")) {
                    return toDouble(v);
                }
                if (type.startsWith("BOOL") || type.equals("LOGICAL")) {
                    return toBoolean(v);
                }
                if (type.startsWith("DATE")) {
                    return java.sql.Date.valueOf(toDate(v));
                }
                // CHAR/VARCHAR/CHARACTER/TEXT/STRING and anything else -> string
                return str(v);
            }

            default:
                throw new SQLException("Unknown function: " + name);
        }
    }

    private static void requireArgs(String name, List<Object> args, int n) throws SQLException {
        if (args.size() < n) {
            throw new SQLException(name + " requires " + n + " arguments, got " + args.size());
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static boolean toBoolean(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof Number) return ((Number) o).doubleValue() != 0;
        String s = o.toString().trim();
        return s.equalsIgnoreCase("Y") || s.equalsIgnoreCase("T")
            || s.equalsIgnoreCase("TRUE") || s.equals("1");
    }

    private static long toLong(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        return (long) toDouble(o);
    }

    private static double toDouble(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        try {
            return Double.parseDouble(o.toString().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Coerces a value to a LocalDate for YEAR/MONTH/DAY. */
    private static java.time.LocalDate toDate(Object o) throws SQLException {
        if (o instanceof java.sql.Date) {
            return ((java.sql.Date) o).toLocalDate();
        }
        if (o instanceof java.util.Date) {
            return new java.sql.Date(((java.util.Date) o).getTime()).toLocalDate();
        }
        String s = o.toString().trim();
        try {
            // ISO yyyy-MM-dd, or compact yyyyMMdd as stored in DBF
            if (s.matches("\\d{8}")) {
                return java.time.LocalDate.of(Integer.parseInt(s.substring(0, 4)),
                    Integer.parseInt(s.substring(4, 6)), Integer.parseInt(s.substring(6, 8)));
            }
            return java.time.LocalDate.parse(s);
        } catch (RuntimeException e) {
            throw new SQLException("Not a date: '" + s + "'");
        }
    }

    private static double round(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }

    /** Equality for NULLIF/DECODE: numeric when both numeric, else trimmed string. */
    private static boolean valuesEqual(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (isNumeric(a) && isNumeric(b)) return toDouble(a) == toDouble(b);
        return a.toString().trim().equals(b.toString().trim());
    }

    /** Ordering for GREATEST/LEAST: numeric when both numeric, else case-insensitive string. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareValues(Object a, Object b) {
        if (isNumeric(a) && isNumeric(b)) {
            return Double.compare(toDouble(a), toDouble(b));
        }
        if (a instanceof Comparable && b != null && a.getClass() == b.getClass()) {
            return ((Comparable) a).compareTo(b);
        }
        return a.toString().trim().compareToIgnoreCase(b.toString().trim());
    }

    private static boolean isNumeric(Object o) {
        if (o instanceof Number) return true;
        if (o == null) return false;
        try {
            Double.parseDouble(o.toString().trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Pads {@code s} to {@code len} using {@code pad} on the left or right; truncates if longer. */
    private static String pad(String s, int len, String pad, boolean left) {
        if (len <= 0) return "";
        if (s.length() >= len) return s.substring(0, len);
        if (pad == null || pad.isEmpty()) pad = " ";
        StringBuilder fill = new StringBuilder();
        while (fill.length() < len - s.length()) fill.append(pad);
        fill.setLength(len - s.length());
        return left ? fill + s : s + fill;
    }

    /** Coerces a value to a Timestamp for HOUR/MINUTE/SECOND. */
    private static java.sql.Timestamp toTimestamp(Object o) throws SQLException {
        if (o instanceof java.sql.Timestamp) return (java.sql.Timestamp) o;
        if (o instanceof java.util.Date) return new java.sql.Timestamp(((java.util.Date) o).getTime());
        String s = o.toString().trim();
        try {
            return java.sql.Timestamp.valueOf(s);
        } catch (RuntimeException e) {
            // fall back to a date-only value at midnight
            return java.sql.Timestamp.valueOf(toDate(o).atStartOfDay());
        }
    }

    private static String stripLeading(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return s.substring(i);
    }

    private static String stripTrailing(String s) {
        int i = s.length();
        while (i > 0 && Character.isWhitespace(s.charAt(i - 1))) i--;
        return s.substring(0, i);
    }
}
