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

    /** Raw bytes of the current record (including the deleted flag), for undo capture. */
    public byte[] getCurrentRecordRaw() {
        byte[] data = recordReader.getCurrentRecordData();
        return data == null ? null : data.clone();
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

    /**
     * Raw fixed-width field text exactly as stored, WITHOUT the trimming that
     * {@link #getValue} applies. Needed to reproduce Clipper index keys, where
     * character fields contribute their full padded width (including leading
     * and trailing spaces). Returns null if there is no current record.
     */
    public String getRawString(String columnName) throws IOException {
        byte[] recordData = recordReader.getCurrentRecordData();
        if (recordData == null) {
            return null;
        }
        int offset = 1; // skip the deleted flag
        for (DBFField f : header.getFields()) {
            int len = f.getLength();
            if (f.getName().equalsIgnoreCase(columnName)) {
                if (offset + len > recordData.length) {
                    return null;
                }
                return new String(recordData, offset, len, charset);
            }
            offset += len;
        }
        return null;
    }
    
    private Object[] getCurrentRow() throws IOException, SQLException {
        if (currentRowCache != null) {
            return currentRowCache;
        }
        
        // No-copy buffer: decoded immediately below into currentRowCache and not
        // retained, so we can read the reader's internal buffer directly.
        byte[] recordData = recordReader.getCurrentRecordBuffer();
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
    
    /**
     * Decodes ONLY the fields flagged true in {@code needed} for the current
     * record; unflagged slots are left null. Same column layout/indexes as
     * {@link #getCurrentRow()} -- callers that never read the skipped columns get
     * identical results while avoiding the cost of decoding them. Reads the
     * record buffer directly (decoded immediately, not retained).
     */
    public Object[] getCurrentRowPruned(boolean[] needed) throws IOException, SQLException {
        byte[] recordData = recordReader.getCurrentRecordBuffer();
        if (recordData == null) {
            return null;
        }
        java.util.List<DBFField> fields = header.getFields();
        Object[] row = new Object[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            if (!needed[i]) {
                continue;
            }
            DBFField field = fields.get(i);
            if (field.getType() == 'M' && memoResolver != null && memoResolver.isAvailable()) {
                int blockNumber = fieldDecoder.getMemoBlockNumber(recordData, field);
                row[i] = memoResolver.resolveMemo(blockNumber);
            } else if (isBinaryType(field.getType())) {
                row[i] = binaryDeserializer.deserialize(extractFieldData(recordData, field), field);
            } else {
                row[i] = fieldDecoder.decode(recordData, field);
            }
        }
        return row;
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