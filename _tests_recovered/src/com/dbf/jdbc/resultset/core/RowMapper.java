package com.dbf.jdbc.resultset.core;

import com.dbf.jdbc.resultset.TypeConverter;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;

/**
 * Maps raw row values to Java types - pure functions, no state
 */
public class RowMapper {
    private final String[] columnNames;
    private final int[] columnTypes;
    
    public RowMapper(String[] columnNames, int[] columnTypes) {
        this.columnNames = columnNames;
        this.columnTypes = columnTypes;
    }
    
    // Getter methods - ADD THESE
    public String[] getColumnNames() {
        return columnNames;
    }
    
    public int[] getColumnTypes() {
        return columnTypes;
    }
    
    public int getColumnCount() {
        return columnNames.length;
    }
    
    public String getColumnName(int index) {
        if (index >= 0 && index < columnNames.length) {
            return columnNames[index];
        }
        return null;
    }
    
    public int getColumnType(int index) {
        if (index >= 0 && index < columnTypes.length) {
            return columnTypes[index];
        }
        return Types.VARCHAR;
    }
    
    // Type conversion methods
    public String getString(Object value) { 
        return TypeConverter.toString(value); 
    }
    
    public boolean getBoolean(Object value) { 
        return TypeConverter.toBoolean(value); 
    }
    
    public byte getByte(Object value) { 
        return TypeConverter.toByte(value); 
    }
    
    public short getShort(Object value) { 
        return TypeConverter.toShort(value); 
    }
    
    public int getInt(Object value) { 
        return TypeConverter.toInt(value); 
    }
    
    public long getLong(Object value) { 
        return TypeConverter.toLong(value); 
    }
    
    public float getFloat(Object value) { 
        return TypeConverter.toFloat(value); 
    }
    
    public double getDouble(Object value) { 
        return TypeConverter.toDouble(value); 
    }
    
    public BigDecimal getBigDecimal(Object value, int scale) { 
        return TypeConverter.toBigDecimal(value, scale); 
    }
    
    public byte[] getBytes(Object value) { 
        return TypeConverter.toBytes(value); 
    }
    
    public Date getDate(Object value) { 
        return TypeConverter.toDate(value); 
    }
    
    public Time getTime(Object value) { 
        return TypeConverter.toTime(value); 
    }
    
    public Timestamp getTimestamp(Object value) { 
        return TypeConverter.toTimestamp(value); 
    }
    
    public int findColumn(String columnLabel) {
        for (int i = 0; i < columnNames.length; i++) {
            if (columnNames[i].equalsIgnoreCase(columnLabel)) {
                return i;
            }
        }
        return -1;
    }
}