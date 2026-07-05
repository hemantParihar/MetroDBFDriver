package com.dbf.jdbc.dbf.decode;

import com.dbf.jdbc.dbf.DBFField;
import java.nio.charset.Charset;
import java.util.Calendar;

/**
 * Pure field decoder - converts raw bytes to Java objects
 * No navigation, no I/O, no memo handling
 */
public class FieldDecoder {
    private final Charset charset;
    
    public FieldDecoder(Charset charset) {
        this.charset = charset;
    }
    
    public Object decode(byte[] recordData, DBFField field) {
        int offset = field.getOffset();
        int length = field.getLength();
        
        if (offset + length > recordData.length) {
            return null;
        }
        
        byte[] fieldData = new byte[length];
        System.arraycopy(recordData, offset, fieldData, 0, length);
        
        String str = new String(fieldData, charset).trim();
        
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
                
            case 'D': // Date (YYYYMMDD)
                if (str.length() == 8 && str.matches("\\d{8}")) {
                    try {
                        int year = Integer.parseInt(str.substring(0, 4));
                        int month = Integer.parseInt(str.substring(4, 6));
                        int day = Integer.parseInt(str.substring(6, 8));
                        Calendar cal = Calendar.getInstance();
                        cal.set(year, month - 1, day, 0, 0, 0);
                        cal.set(Calendar.MILLISECOND, 0);
                        return new java.sql.Date(cal.getTimeInMillis());
                    } catch (Exception e) {
                        return null;
                    }
                }
                return null;
                
            case 'L': // Logical (Y/y/T/t = true)
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
                
            default:
                return str;
        }
    }
    
    public int getMemoBlockNumber(byte[] recordData, DBFField field) {
        int offset = field.getOffset();
        int length = field.getLength();
        
        if (offset + 4 > recordData.length) return 0;
        
        int blockNumber = 0;
        for (int i = 0; i < Math.min(length, 4); i++) {
            blockNumber |= (recordData[offset + i] & 0xFF) << (i * 8);
        }
        return blockNumber;
    }
}