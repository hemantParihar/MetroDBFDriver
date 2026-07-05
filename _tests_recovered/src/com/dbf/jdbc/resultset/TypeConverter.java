package com.dbf.jdbc.resultset;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.RowId;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Calendar;

import com.dbf.jdbc.dbf.DBFField;

/**
 * Converts DBF field values to Java/ JDBC types
 */
public class TypeConverter {
    
    public static Object convertToJavaType(byte[] data, DBFField field, String charset) {
        String str = new String(data, java.nio.charset.Charset.forName(charset)).trim();
        
        switch (field.getType()) {
            case 'C': // Character
                return str.isEmpty() ? null : str;
                
            case 'N': // Numeric
            case 'F': // Float
                if (str.isEmpty()) return null;
                try {
                    if (field.getDecimalCount() > 0 || str.contains(".")) {
                        return Double.parseDouble(str);
                    }
                    return Long.parseLong(str);
                } catch (NumberFormatException e) {
                    return null;
                }
                
            case 'D': // Date
                if (str.length() == 8 && str.matches("\\d{8}")) {
                    try {
                        int year = Integer.parseInt(str.substring(0, 4));
                        int month = Integer.parseInt(str.substring(4, 6));
                        int day = Integer.parseInt(str.substring(6, 8));
                        Calendar cal = Calendar.getInstance();
                        cal.set(year, month - 1, day, 0, 0, 0);
                        cal.set(Calendar.MILLISECOND, 0);
                        return new Date(cal.getTimeInMillis());
                    } catch (Exception e) {
                        return null;
                    }
                }
                return null;
                
            case 'L': // Logical
                if (str.length() > 0) {
                    char c = str.charAt(0);
                    return c == 'Y' || c == 'y' || c == 'T' || c == 't';
                }
                return false;
                
            case 'I': // Integer (4 bytes)
                if (str.isEmpty()) return null;
                try {
                    return Integer.parseInt(str);
                } catch (NumberFormatException e) {
                    return null;
                }
                
            case 'O': // Double
                if (str.isEmpty()) return null;
                try {
                    return Double.parseDouble(str);
                } catch (NumberFormatException e) {
                    return null;
                }
                
            case 'M': // Memo
                return str;
                
            default:
                return str;
        }
    }
    
    public static String toString(Object value) {
        return value != null ? value.toString() : null;
    }
    
    public static boolean toBoolean(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        String s = value.toString();
        return s.equalsIgnoreCase("Y") || s.equalsIgnoreCase("T") || s.equalsIgnoreCase("TRUE");
    }
    
    public static byte toByte(Object value) {
        Number n = toNumber(value);
        return n != null ? n.byteValue() : 0;
    }
    
    public static short toShort(Object value) {
        Number n = toNumber(value);
        return n != null ? n.shortValue() : 0;
    }
    
    public static int toInt(Object value) {
        Number n = toNumber(value);
        return n != null ? n.intValue() : 0;
    }
    
    public static long toLong(Object value) {
        Number n = toNumber(value);
        return n != null ? n.longValue() : 0;
    }
    
    public static float toFloat(Object value) {
        Number n = toNumber(value);
        return n != null ? n.floatValue() : 0;
    }
    
    public static double toDouble(Object value) {
        Number n = toNumber(value);
        return n != null ? n.doubleValue() : 0;
    }
    
    public static Number toNumber(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return (Number) value;
        try {
            String s = value.toString().trim();
            if (s.isEmpty()) return null;
            if (s.contains(".")) {
                return Double.parseDouble(s);
            }
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
    
    public static BigDecimal toBigDecimal(Object value, int scale) {
        Number n = toNumber(value);
        if (n == null) return null;
        BigDecimal bd = BigDecimal.valueOf(n.doubleValue());
        return bd.setScale(scale, RoundingMode.HALF_UP);
    }
    
    public static Date toDate(Object value) {
        if (value == null) return null;
        if (value instanceof Date) return (Date) value;
        if (value instanceof java.util.Date) return new Date(((java.util.Date) value).getTime());
        try {
            return Date.valueOf(value.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
    
    public static Time toTime(Object value) {
        Date date = toDate(value);
        return date != null ? new Time(date.getTime()) : null;
    }
    
    public static Timestamp toTimestamp(Object value) {
        Date date = toDate(value);
        return date != null ? new Timestamp(date.getTime()) : null;
    }
    
    public static byte[] toBytes(Object value) {
        if (value == null) return null;
        if (value instanceof byte[]) return (byte[]) value;
        return value.toString().getBytes();
    }
 // Add to TypeConverter.java

    public static int guessSqlType(Object value) {
        if (value == null) return Types.NULL;
        if (value instanceof String) return Types.VARCHAR;
        if (value instanceof Integer) return Types.INTEGER;
        if (value instanceof Long) return Types.BIGINT;
        if (value instanceof Double) return Types.DOUBLE;
        if (value instanceof Float) return Types.FLOAT;
        if (value instanceof Boolean) return Types.BOOLEAN;
        if (value instanceof Date) return Types.DATE;
        if (value instanceof Timestamp) return Types.TIMESTAMP;
        if (value instanceof Time) return Types.TIME;
        if (value instanceof BigDecimal) return Types.DECIMAL;
        if (value instanceof byte[]) return Types.VARBINARY;
        if (value instanceof Blob) return Types.BLOB;
        if (value instanceof Clob) return Types.CLOB;
        if (value instanceof Array) return Types.ARRAY;
        if (value instanceof Ref) return Types.REF;
        if (value instanceof RowId) return Types.ROWID;
        if (value instanceof NClob) return Types.NCLOB;
        if (value instanceof SQLXML) return Types.SQLXML;
        if (value instanceof URL) return Types.DATALINK;
        return Types.JAVA_OBJECT;
    }

    public static String getClassNameForType(int sqlType) {
        switch (sqlType) {
            case Types.INTEGER: return "java.lang.Integer";
            case Types.BIGINT: return "java.lang.Long";
            case Types.SMALLINT: return "java.lang.Short";
            case Types.TINYINT: return "java.lang.Byte";
            case Types.FLOAT:
            case Types.DOUBLE: return "java.lang.Double";
            case Types.DECIMAL:
            case Types.NUMERIC: return "java.math.BigDecimal";
            case Types.VARCHAR:
            case Types.CHAR:
            case Types.LONGVARCHAR: return "java.lang.String";
            case Types.DATE: return "java.sql.Date";
            case Types.TIME: return "java.sql.Time";
            case Types.TIMESTAMP: return "java.sql.Timestamp";
            case Types.BOOLEAN: return "java.lang.Boolean";
            case Types.BLOB: return "java.sql.Blob";
            case Types.CLOB: return "java.sql.Clob";
            case Types.ARRAY: return "java.sql.Array";
            case Types.REF: return "java.sql.Ref";
            case Types.ROWID: return "java.sql.RowId";
            case Types.NCLOB: return "java.sql.NClob";
            case Types.SQLXML: return "java.sql.SQLXML";
            case Types.DATALINK: return "java.net.URL";
            default: return "java.lang.Object";
        }
    }
}