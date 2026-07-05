package com.dbf.jdbc.dbf.decode;

import com.dbf.jdbc.dbf.DBFField;
import java.nio.charset.Charset;

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

        char type = field.getType();

        // Character: return the bytes EXACTLY as stored (no trim); NULL only when
        // wholly blank. Decoded straight from recordData -- no intermediate copy.
        if (type == 'C') {
            if (isBlank(recordData, offset, length)) {
                return null;
            }
            return new String(recordData, offset, length, charset);
        }

        // Every other type works off the trimmed text. Decoding directly from
        // recordData avoids the per-field byte[] copy that used to run on every
        // field of every row.
        String str = new String(recordData, offset, length, charset).trim();

        switch (type) {
            case 'N': // Numeric
            case 'F': // Float
                if (str.isEmpty()) return null;
                try {
                    if (field.getDecimalCount() > 0 || str.indexOf('.') >= 0) {
                        return Double.parseDouble(str);
                    }
                    return Long.parseLong(str);
                } catch (NumberFormatException e) {
                    return null;
                }

            case 'D': // Date (YYYYMMDD)
                return parseDbfDate(str);

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

    /** True if every byte in the range is blank (space or below), per String.trim() semantics. */
    private static boolean isBlank(byte[] data, int offset, int length) {
        for (int i = offset, end = offset + length; i < end; i++) {
            if ((data[i] & 0xFF) > ' ') {
                return false;
            }
        }
        return true;
    }

    /** Parses an 8-char YYYYMMDD date without regex or Calendar; null if not 8 digits. */
    private static java.sql.Date parseDbfDate(String str) {
        if (str.length() != 8) return null;
        for (int i = 0; i < 8; i++) {
            char c = str.charAt(i);
            if (c < '0' || c > '9') return null;
        }
        try {
            int year = Integer.parseInt(str.substring(0, 4));
            int month = Integer.parseInt(str.substring(4, 6));
            int day = Integer.parseInt(str.substring(6, 8));
            return java.sql.Date.valueOf(java.time.LocalDate.of(year, month, day));
        } catch (RuntimeException e) {
            return null;
        }
    }

    public int getMemoBlockNumber(byte[] recordData, DBFField field) {
        int offset = field.getOffset();
        int length = field.getLength();

        if (offset + length > recordData.length) return 0;

        // dBASE III/IV store the block number as ASCII digits in a
        // 10-character field (space-padded)
        String text = new String(recordData, offset, length,
            java.nio.charset.StandardCharsets.US_ASCII).trim();
        if (!text.isEmpty()) {
            boolean digits = true;
            for (int i = 0; i < text.length(); i++) {
                if (!Character.isDigit(text.charAt(i))) {
                    digits = false;
                    break;
                }
            }
            if (digits) {
                try {
                    return Integer.parseInt(text);
                } catch (NumberFormatException ignored) {
                    // fall through to binary interpretation
                }
            }
        }

        // Visual FoxPro uses a 4-byte little-endian binary block number
        if (length == 4) {
            return (recordData[offset] & 0xFF)
                | ((recordData[offset + 1] & 0xFF) << 8)
                | ((recordData[offset + 2] & 0xFF) << 16)
                | ((recordData[offset + 3] & 0xFF) << 24);
        }
        return 0;
    }
}