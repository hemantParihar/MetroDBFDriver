package com.dbf.jdbc.dbf.filter;

/**
 * Manages deleted record filtering and packing
 */
public class DeletedRecordManager {
    private final boolean includeDeleted;
    
    public DeletedRecordManager(boolean includeDeleted) {
        this.includeDeleted = includeDeleted;
    }
    
    public boolean shouldInclude(boolean isDeleted) {
        if (includeDeleted) return true;
        return !isDeleted;
    }
    
    public boolean isDeleted(byte[] recordData) {
        if (recordData == null || recordData.length == 0) return false;
        return recordData[0] == 0x2A;
    }
    
    public byte[] packRecords(byte[][] records, int recordSize) {
        int validCount = 0;
        for (byte[] record : records) {
            if (!isDeleted(record)) validCount++;
        }
        
        byte[] packed = new byte[validCount * recordSize];
        int pos = 0;
        for (byte[] record : records) {
            if (!isDeleted(record)) {
                System.arraycopy(record, 0, packed, pos, recordSize);
                pos += recordSize;
            }
        }
        return packed;
    }
}