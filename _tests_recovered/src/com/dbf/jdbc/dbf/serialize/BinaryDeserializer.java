package com.dbf.jdbc.dbf.serialize;

import com.dbf.jdbc.dbf.DBFField;
import java.nio.charset.Charset;

/**
 * Handles binary data deserialization from DBF fields
 * Supports:
 * - Integer (4 bytes, little-endian)
 * - Double (8 bytes, IEEE 754)
 * - Currency (8 bytes, scaled integer)
 * - Timestamp (8 bytes, FoxPro format)
 */
public class BinaryDeserializer {
    private final Charset charset;
    
    public BinaryDeserializer(Charset charset) {
        this.charset = charset;
    }
    
    public Object deserialize(byte[] data, DBFField field) {
        if (data == null || data.length < field.getLength()) return null;
        
        switch (field.getType()) {
            case 'I': // Integer (4 bytes, little-endian)
                if (field.getLength() >= 4) {
                    int value = (data[0] & 0xFF) |
                                ((data[1] & 0xFF) << 8) |
                                ((data[2] & 0xFF) << 16) |
                                ((data[3] & 0xFF) << 24);
                    return value;
                }
                break;
                
            case 'O': // Double (8 bytes, IEEE 754)
                if (field.getLength() >= 8) {
                    long bits = ((long)(data[0] & 0xFF)) |
                                ((long)(data[1] & 0xFF) << 8) |
                                ((long)(data[2] & 0xFF) << 16) |
                                ((long)(data[3] & 0xFF) << 24) |
                                ((long)(data[4] & 0xFF) << 32) |
                                ((long)(data[5] & 0xFF) << 40) |
                                ((long)(data[6] & 0xFF) << 48) |
                                ((long)(data[7] & 0xFF) << 56);
                    return Double.longBitsToDouble(bits);
                }
                break;
                
            case 'Y': // Currency (8 bytes, scaled by 10000)
                if (field.getLength() >= 8) {
                    long scaled = ((long)(data[0] & 0xFF)) |
                                  ((long)(data[1] & 0xFF) << 8) |
                                  ((long)(data[2] & 0xFF) << 16) |
                                  ((long)(data[3] & 0xFF) << 24) |
                                  ((long)(data[4] & 0xFF) << 32) |
                                  ((long)(data[5] & 0xFF) << 40) |
                                  ((long)(data[6] & 0xFF) << 48) |
                                  ((long)(data[7] & 0xFF) << 56);
                    return scaled / 10000.0;
                }
                break;
                
            case 'T': // Timestamp (FoxPro datetime)
                if (field.getLength() >= 8) {
                    long days = ((long)(data[0] & 0xFF)) |
                                ((long)(data[1] & 0xFF) << 8) |
                                ((long)(data[2] & 0xFF) << 16) |
                                ((long)(data[3] & 0xFF) << 24);
                    long ms = ((long)(data[4] & 0xFF)) |
                              ((long)(data[5] & 0xFF) << 8) |
                              ((long)(data[6] & 0xFF) << 16) |
                              ((long)(data[7] & 0xFF) << 24);
                    // FoxPro date epoch: 1899-12-30
                    long millis = (days - 25569) * 86400000 + ms;
                    return new java.sql.Timestamp(millis);
                }
                break;
                
            case 'B': // Binary (double or OLE)
                // Fall through to string representation
                break;
        }
        
        // Fallback to string decoding
        String str = new String(data, 0, field.getLength(), charset).trim();
        return str.isEmpty() ? null : str;
    }
}