package com.dbf.jdbc.dbf;

/**
 * DBF field descriptor - 32 bytes per field
 * Based on public XBase file format specification
 */
public class DBFField {
    private String name;
    private char type;
    private int offset;      // Field offset in record (calculated, not stored)
    private int length;
    private int decimalCount;
    private byte flags;
    private int autoIncrementNext;
    private byte step;
    
    // Calculated fields
    private int position;    // 1-based field position

    public DBFField() {}
    
    public DBFField(String name, char type, int length, int decimalCount) {
        this.name = name;
        this.type = type;
        this.length = length;
        this.decimalCount = decimalCount;
    }
    
    // Getters and setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public char getType() { return type; }
    public void setType(char type) { this.type = type; }
    
    public int getOffset() { return offset; }
    public void setOffset(int offset) { this.offset = offset; }
    
    public int getLength() { return length; }
    public void setLength(int length) { this.length = length; }
    
    public int getDecimalCount() { return decimalCount; }
    public void setDecimalCount(int decimalCount) { this.decimalCount = decimalCount; }
    
    public byte getFlags() { return flags; }
    public void setFlags(byte flags) { this.flags = flags; }
    
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    
    public int getAutoIncrementNext() {
		return autoIncrementNext;
	}

	public void setAutoIncrementNext(int autoIncrementNext) {
		this.autoIncrementNext = autoIncrementNext;
	}

	public byte getStep() {
		return step;
	}

	public void setStep(byte step) {
		this.step = step;
	}

	@Override
    public String toString() {
        return String.format("%s(%c,%d,%d)", name, type, length, decimalCount);
    }
}