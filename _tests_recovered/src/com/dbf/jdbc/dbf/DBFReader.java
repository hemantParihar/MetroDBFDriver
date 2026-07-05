package com.dbf.jdbc.dbf;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.sql.SQLException;

import com.dbf.jdbc.dbf.decode.FieldDecoder;
import com.dbf.jdbc.dbf.io.RecordReader;
import com.dbf.jdbc.dbf.serialize.BinaryDeserializer;
import com.dbf.jdbc.memo.MemoResolver;

/**
 * Refactored DBFReader - Now just orchestrates the components
 * Size reduced from ~21KB to ~5KB
 */  
public class DBFReader implements Closeable {
    private final RecordReader recordReader;
    private final DBFHeader header;
    private final FieldDecoder fieldDecoder;
    private final BinaryDeserializer binaryDeserializer;
    private final MemoResolver memoResolver;
    private final Charset charset;
    
    private Object[] currentRowCache;
    private boolean closed = false;
    
    public DBFReader(String filePath, Charset charset) throws IOException {
        this.charset = charset;
        RandomAccessFile file = new RandomAccessFile(filePath, "r");
        
        this.header = new DBFHeader();
        this.header.read(file, charset);
        
        this.recordReader = new RecordReader(
            file,
            header.getHeaderSize(),
            header.getRecordSize(),
            header.getRecordCount(),
            header.hasMemo()
        );
        
        this.fieldDecoder = new FieldDecoder(charset);
        this.binaryDeserializer = new BinaryDeserializer(charset);
        
        if (header.hasMemo()) {
            this.memoResolver = new MemoResolver(filePath, charset);
        } else {
            this.memoResolver = null;
        }
    }
    
    public DBFHeader getHeader() {
        return header;
    }
    
    public boolean next() throws IOException {
        boolean hasNext = recordReader.next();
        if (hasNext) {
            currentRowCache = null;
        }
        return hasNext;
    }
    
    public boolean previous() throws IOException {
        boolean hasPrev = recordReader.previous();
        if (hasPrev) {
            currentRowCache = null;
        }
        return hasPrev;
    }
    
    public boolean absolute(int recordNumber) throws IOException {
        boolean success = recordReader.absolute(recordNumber);
        if (success) {
            currentRowCache = null;
        }
        return success;
    }
    
    public void beforeFirst() {
        recordReader.beforeFirst();
        currentRowCache = null;
    }
    
    public void afterLast() {
        recordReader.afterLast();
        currentRowCache = null;
    }
    
    public int getCurrentRecord() {
        return recordReader.getCurrentRecordNumber();
    }
    
    public boolean isDeleted() {
        return recordReader.isCurrentRecordDeleted();
    }
    
    public Object getValue(int columnIndex) throws IOException, SQLException {
        if (recordReader.getCurrentRecordNumber() < 0) {
            throw new IOException("No current record");
        }
        
        Object[] row = getCurrentRow();
        if (columnIndex < 0 || columnIndex >= row.length) {
            return null;
        }
        return row[columnIndex];
    }
    
    public Object getValue(String columnName) throws IOException, SQLException {
        DBFField field = header.getField(columnName);
        if (field == null) return null;
        return getValue(field.getPosition() - 1);
    }
    
    private Object[] getCurrentRow() throws IOException, SQLException {
        if (currentRowCache != null) {
            return currentRowCache;
        }
        
        byte[] recordData = recordReader.getCurrentRecordData();
        if (recordData == null) {
            return null;
        }
        
        java.util.List<DBFField> fields = header.getFields();
        currentRowCache = new Object[fields.size()];
        
        for (int i = 0; i < fields.size(); i++) {
            DBFField field = fields.get(i);
            
            if (field.getType() == 'M' && memoResolver != null && memoResolver.isAvailable()) {
                int blockNumber = fieldDecoder.getMemoBlockNumber(recordData, field);
                String memo = memoResolver.resolveMemo(blockNumber);
                currentRowCache[i] = memo;
            } else if (isBinaryType(field.getType())) {
                byte[] fieldData = extractFieldData(recordData, field);
                currentRowCache[i] = binaryDeserializer.deserialize(fieldData, field);
            } else {
                currentRowCache[i] = fieldDecoder.decode(recordData, field);
            }
        }
        
        return currentRowCache;
    }
    
    private byte[] extractFieldData(byte[] recordData, DBFField field) {
        int offset = field.getOffset();
        int length = field.getLength();
        byte[] data = new byte[length];
        System.arraycopy(recordData, offset, data, 0, length);
        return data;
    }
    
    private boolean isBinaryType(char type) {
        return type == 'I' || type == 'O' || type == 'Y' || type == 'T' || type == 'B';
    }
    
    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            recordReader.close();
            if (memoResolver != null) {
                memoResolver.close();
            }
        }
    }
}