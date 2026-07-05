package com.dbf.jdbc.dbf;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.sql.Date;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.dbf.jdbc.DBFConstants;
import com.dbf.jdbc.lock.RowLockManager;

public class DBFWriter implements Closeable {
    private final RandomAccessFile file;
    private final DBFHeader header;
    private final Charset charset;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ReentrantReadWriteLock fileLock = new ReentrantReadWriteLock();
    private final RowLockManager lockManager = new RowLockManager();
    
    // Batch support
    private final List<BatchOperation> batchBuffer = new ArrayList<>();
    private int batchSize = 1000;
    
    // Memo support
    private RandomAccessFile memoFile;
    private boolean hasMemo = false;
    
    private static class BatchOperation {
        final OperationType type;
        final Object[] values;
        final int recordNumber;
        final String whereCondition;
        
        BatchOperation(OperationType type, Object[] values, int recordNumber, String whereCondition) {
            this.type = type;
            this.values = values;
            this.recordNumber = recordNumber;
            this.whereCondition = whereCondition;
        }
    }
    
    private enum OperationType { INSERT, UPDATE, DELETE }
    
    public DBFWriter(String filePath, Charset charset) throws IOException {
        this.file = new RandomAccessFile(filePath, "rw");
        this.charset = charset;
        
        this.header = new DBFHeader();
        if (file.length() >= DBFHeader.FIXED_HEADER_SIZE) {
            this.header.read(this.file, charset);
        } else {
            initializeNewHeader();
        }
        
        // Initialize memo file if needed
        if (header.hasMemo()) {
            String memoPath = filePath.substring(0, filePath.length() - 4);
            File memoFileObj = new File(memoPath + ".fpt");
            if (!memoFileObj.exists()) {
                memoFileObj = new File(memoPath + ".dbt");
            }
            if (memoFileObj.exists()) {
                this.memoFile = new RandomAccessFile(memoFileObj, "rw");
                this.hasMemo = true;
            }
        }
    }
    
    private void initializeNewHeader() {
        header.setVersion(DBFConstants.DBF_III_PLUS_NO_MEMO);
        header.setLastUpdate(new java.util.Date());
        header.setRecordCount(0);
        header.setFields(new ArrayList<>());
    }
    
    // Batch operations
    public void setBatchSize(int size) { this.batchSize = size; }
    
    public void addInsertBatch(Object[] values) {
        batchBuffer.add(new BatchOperation(OperationType.INSERT, values, -1, null));
        if (batchBuffer.size() >= batchSize) {
            try {
				executeBatch();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
        }
    }
    
    public void addUpdateBatch(int recordNumber, Object[] values) {
        batchBuffer.add(new BatchOperation(OperationType.UPDATE, values, recordNumber, null));
        if (batchBuffer.size() >= batchSize) {
            try {
				executeBatch();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
        }
    }
    
    public void addDeleteBatch(int recordNumber) {
        batchBuffer.add(new BatchOperation(OperationType.DELETE, null, recordNumber, null));
        if (batchBuffer.size() >= batchSize) {
            try {
				executeBatch();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
        }
    }
    
    public int[] executeBatch() throws IOException {
        if (batchBuffer.isEmpty()) {
            return new int[0];
        }
        
        fileLock.writeLock().lock();
        int[] results = new int[batchBuffer.size()];
        
        try {
            for (int i = 0; i < batchBuffer.size(); i++) {
                BatchOperation op = batchBuffer.get(i);
                try {
                    switch (op.type) {
                        case INSERT:
                            results[i] = doInsert(op.values);
                            break;
                        case UPDATE:
                            results[i] = doUpdate(op.recordNumber, op.values);
                            break;
                        case DELETE:
                            results[i] = doDelete(op.recordNumber);
                            break;
                    }
                } catch (Exception e) {
                    results[i] = -3; // SQLException
                }
            }
            
            // Update header
            header.setRecordCount(getRecordCount());
            header.write(file, charset);
            batchBuffer.clear();
            
        } finally {
            fileLock.writeLock().unlock();
        }
        
        return results;
    }
    
    public void clearBatch() {
        batchBuffer.clear();
    }
    
 // In DBFWriter.java, add method to get next record number:

    public int getNextRecordNumber() throws IOException {
        return getRecordCount() + 1;
    }

    // Modify insertRecord to return the record number:
    public int insertRecord(Object[] values) throws IOException {
        fileLock.writeLock().lock();
        try {
            int newRecordNumber = getRecordCount() + 1;
            byte[] recordData = createRecordData(values);
            recordData[0] = DBFConstants.RECORD_ACTIVE;
            
            long offset = file.length();
            file.seek(offset);
            file.write(recordData);
            
            getHeader().setRecordCount(getRecordCount());
            getHeader().write(file, getCharset());
            
            return newRecordNumber;
        } finally {
            fileLock.writeLock().unlock();
        }
    }
    private int doInsert(Object[] values) throws IOException {
        byte[] recordData = createRecordData(values);
        recordData[0] = DBFConstants.RECORD_ACTIVE;
        
        long offset = file.length();
        file.seek(offset);
        file.write(recordData);
        
        return getRecordCount();
    }
    
    public boolean updateRecord(int recordNumber, Object[] values) throws IOException {
        fileLock.writeLock().lock();
        try {
            return doUpdate(recordNumber, values) > 0;
        } finally {
            fileLock.writeLock().unlock();
        }
    }
    
    private int doUpdate(int recordNumber, Object[] values) throws IOException {
        if (recordNumber < 1 || recordNumber > getRecordCount()) {
            return 0;
        }
        
        if (!lockManager.tryLock(recordNumber, 5000)) {
            return 0;
        }
        
        try {
            byte[] recordData = readRecordData(recordNumber - 1);
            if (recordData[0] == DBFConstants.RECORD_DELETED) {
                return 0;
            }
            
            byte[] newData = createRecordData(values);
            newData[0] = DBFConstants.RECORD_ACTIVE;
            
            long offset = header.getHeaderSize() + (long) (recordNumber - 1) * header.getRecordSize();
            file.seek(offset);
            file.write(newData);
            
            return 1;
        } finally {
            lockManager.unlock(recordNumber);
        }
    }
    
    public boolean deleteRecord(int recordNumber) throws IOException {
        fileLock.writeLock().lock();
        try {
            return doDelete(recordNumber) > 0;
        } finally {
            fileLock.writeLock().unlock();
        }
    }
    
    private int doDelete(int recordNumber) throws IOException {
        if (recordNumber < 1 || recordNumber > getRecordCount()) {
            return 0;
        }
        
        if (!lockManager.tryLock(recordNumber, 5000)) {
            return 0;
        }
        
        try {
            long offset = header.getHeaderSize() + (long) (recordNumber - 1) * header.getRecordSize();
            file.seek(offset);
            file.writeByte(DBFConstants.RECORD_DELETED);
            return 1;
        } finally {
            lockManager.unlock(recordNumber);
        }
    }
    
    public boolean undeleteRecord(int recordNumber) throws IOException {
        fileLock.writeLock().lock();
        try {
            if (recordNumber < 1 || recordNumber > getRecordCount()) {
                return false;
            }
            
            long offset = header.getHeaderSize() + (long) (recordNumber - 1) * header.getRecordSize();
            file.seek(offset);
            file.writeByte(DBFConstants.RECORD_ACTIVE);
            return true;
        } finally {
            fileLock.writeLock().unlock();
        }
    }
    
    public void pack() throws IOException {
        fileLock.writeLock().lock();
        try {
            List<byte[]> validRecords = new ArrayList<>();
            int recordCount = getRecordCount();
            
            for (int i = 0; i < recordCount; i++) {
                byte[] record = readRecordData(i);
                if (record[0] == DBFConstants.RECORD_ACTIVE) {
                    validRecords.add(record);
                }
            }
            
            long dataStart = header.getHeaderSize();
            file.seek(dataStart);
            
            for (byte[] record : validRecords) {
                file.write(record);
            }
            
            file.setLength(dataStart + (long) validRecords.size() * header.getRecordSize());
            header.setRecordCount(validRecords.size());
            header.write(file, charset);
            
        } finally {
            fileLock.writeLock().unlock();
        }
    }
    
    private byte[] readRecordData(int recordIndex) throws IOException {
        long offset = header.getHeaderSize() + (long) recordIndex * header.getRecordSize();
        byte[] data = new byte[header.getRecordSize()];
        file.seek(offset);
        file.readFully(data);
        return data;
    }
    
    private byte[] createRecordData(Object[] values) throws IOException {
        byte[] data = new byte[header.getRecordSize()];
        data[0] = DBFConstants.RECORD_ACTIVE;
        
        List<DBFField> fields = header.getFields();
        int fieldCount = Math.min(fields.size(), values.length);
        
        for (int i = 0; i < fieldCount; i++) {
            DBFField field = fields.get(i);
            Object value = values[i];
            byte[] fieldData = formatValue(value, field);
            
            int offset = field.getOffset();
            int length = Math.min(fieldData.length, field.getLength());
            System.arraycopy(fieldData, 0, data, offset, length);
        }
        
        return data;
    }
    
    private byte[] formatValue(Object value, DBFField field) throws IOException {
        if (value == null) {
            return spaces(field.getLength());
        }
        
        String str;
        switch (field.getType()) {
            case DBFConstants.FIELD_TYPE_CHARACTER:
                str = value.toString();
                if (str.length() > field.getLength()) {
                    str = str.substring(0, field.getLength());
                }
                return padRight(str.getBytes(charset), field.getLength());
                
            case DBFConstants.FIELD_TYPE_NUMERIC:
            case DBFConstants.FIELD_TYPE_FLOAT:
                if (value instanceof Number) {
                    if (field.getDecimalCount() == 0) {
                        str = String.format("%" + field.getLength() + "d", ((Number) value).longValue());
                    } else {
                        String format = "%" + field.getLength() + "." + field.getDecimalCount() + "f";
                        str = String.format(format, ((Number) value).doubleValue());
                    }
                } else {
                    str = value.toString();
                }
                if (str.length() > field.getLength()) {
                    str = str.substring(0, field.getLength());
                }
                return padLeft(str.getBytes(charset), field.getLength());
                
            case DBFConstants.FIELD_TYPE_DATE:
                if (value instanceof Date) {
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
                    str = sdf.format((Date) value);
                } else if (value instanceof java.util.Date) {
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
                    str = sdf.format((java.util.Date) value);
                } else {
                    str = value.toString();
                }
                return str.getBytes(charset);
                
            case DBFConstants.FIELD_TYPE_LOGICAL:
                boolean bool = false;
                if (value instanceof Boolean) {
                    bool = (Boolean) value;
                } else if (value instanceof String) {
                    String s = ((String) value).toUpperCase();
                    bool = s.equals("Y") || s.equals("T") || s.equals("TRUE");
                }
                return new byte[] { (byte) (bool ? 'Y' : 'N') };
                
            case DBFConstants.FIELD_TYPE_INTEGER:
                int intVal = (value instanceof Number) ? ((Number) value).intValue() : 0;
                str = String.format("%" + field.getLength() + "d", intVal);
                return padLeft(str.getBytes(charset), field.getLength());
                
            case DBFConstants.FIELD_TYPE_MEMO:
                if (memoFile != null && hasMemo) {
                    return writeMemo(value.toString(), field);
                }
                return spaces(field.getLength());
                
            default:
                str = value.toString();
                if (str.length() > field.getLength()) {
                    str = str.substring(0, field.getLength());
                }
                return padRight(str.getBytes(charset), field.getLength());
        }
    }
    
    private byte[] writeMemo(String text, DBFField field) throws IOException {
        // Simplified memo write - in production, implement full memo block management
        byte[] textBytes = text.getBytes(charset);
        memoFile.seek(memoFile.length());
        long blockNumber = memoFile.length() / header.getMemoBlockSize();
        memoFile.write(textBytes);
        
        // Write block number to field
        byte[] blockBytes = new byte[field.getLength()];
        for (int i = 0; i < 4 && i < blockBytes.length; i++) {
            blockBytes[i] = (byte) ((blockNumber >> (i * 8)) & 0xFF);
        }
        return blockBytes;
    }
    
    private byte[] spaces(int length) {
        byte[] result = new byte[length];
        Arrays.fill(result, (byte) ' ');
        return result;
    }
    
    private byte[] padRight(byte[] data, int length) {
        if (data.length >= length) return data;
        byte[] result = new byte[length];
        System.arraycopy(data, 0, result, 0, data.length);
        Arrays.fill(result, data.length, length, (byte) ' ');
        return result;
    }
    
    private byte[] padLeft(byte[] data, int length) {
        if (data.length >= length) return data;
        byte[] result = new byte[length];
        int offset = length - data.length;
        System.arraycopy(data, 0, result, offset, data.length);
        Arrays.fill(result, 0, offset, (byte) ' ');
        return result;
    }
    
    private int getRecordCount() throws IOException {
        return (int) ((file.length() - header.getHeaderSize()) / header.getRecordSize());
    }
    
    public DBFHeader getHeader() { return header; }
    public Charset getCharset() { return charset; }
    RandomAccessFile getFile() { return file; }
    
    public boolean tryLockRow(int recordNumber, int timeoutMs) {
        return lockManager.tryLock(recordNumber, timeoutMs);
    }
    
    public void unlockRow(int recordNumber) {
        lockManager.unlock(recordNumber);
    }
    
    @Override
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            if (!batchBuffer.isEmpty()) {
                executeBatch();
            }
            file.close();
            if (memoFile != null) {
                memoFile.close();
            }
        }
    }
}