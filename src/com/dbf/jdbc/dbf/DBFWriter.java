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
    private com.dbf.jdbc.memo.MemoFile memoFile;
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
        
        // Initialize memo file if needed (created if missing)
        if (header.hasMemo()) {
            this.memoFile = new com.dbf.jdbc.memo.MemoFile(filePath, charset, true);
            this.hasMemo = this.memoFile.hasMemoFile();
        }
    }
    
    private void initializeNewHeader() {
        header.setVersion(DBFConstants.DBF_III_PLUS_NO_MEMO);
        header.setLastUpdate(new java.util.Date());
        header.setRecordCount(0);
        header.setFields(new ArrayList<>());
    }

    /**
     * Creates a new, empty DBF table in dBASE III PLUS format
     * (version 0x03, or 0x83 plus an empty .DBT memo file when a memo
     * column is present).
     */
    public static void createTable(String filePath, List<DBFField> fields, Charset charset)
            throws IOException {
        validateTableStructure(fields);

        boolean hasMemoField = false;
        for (DBFField field : fields) {
            if (field.getType() == DBFConstants.FIELD_TYPE_MEMO) {
                hasMemoField = true;
                break;
            }
        }

        DBFHeader newHeader = new DBFHeader();
        // Version byte sourced from the central version table (dBASE III:
        // 0x03, or 0x83 when a memo file is present).
        newHeader.setVersion((byte) DbfVersion.DBASE_3.versionByte(hasMemoField));
        newHeader.setLastUpdate(new java.util.Date());
        newHeader.setRecordCount(0);
        newHeader.setFields(new ArrayList<>(fields));

        try (RandomAccessFile out = new RandomAccessFile(filePath, "rw")) {
            out.setLength(0);
            newHeader.write(out, charset);
            out.writeByte(0x1A); // end-of-file marker
        }

        if (hasMemoField) {
            // Empty dBASE III .DBT: 512-byte header whose first 4 bytes
            // (little-endian) point at the next free block
            String memoPath = filePath.substring(0, filePath.length() - 4) + ".dbt";
            try (RandomAccessFile memo = new RandomAccessFile(memoPath, "rw")) {
                memo.setLength(0);
                byte[] memoHeader = new byte[512];
                memoHeader[0] = 1; // next free block = 1 (block 0 is this header)
                memoHeader[16] = 0x03; // dBASE III version marker
                memo.write(memoHeader);
            }
        }
    }

    /**
     * Guards against structurally impossible tables. The record length and
     * field count are both stored in fixed-width header fields, so a table
     * whose total record width exceeds 65535 bytes (or whose descriptor
     * area exceeds 65535 bytes) cannot be represented. Mirrors the DANS DBF
     * library's RecordTooLargeException / InvalidFieldLengthException.
     */
    private static void validateTableStructure(List<DBFField> fields)
            throws DbfValidationException {
        if (fields == null || fields.isEmpty()) {
            throw new DbfValidationException("A table must have at least one column",
                "42000");
        }

        // 1 deletion-flag byte plus the sum of all field widths
        long recordLength = 1;
        for (DBFField field : fields) {
            if (field.getLength() <= 0 || field.getLength() > 255) {
                throw new DbfValidationException("Invalid length " + field.getLength()
                    + " for column " + field.getName(), "42000");
            }
            recordLength += field.getLength();
        }
        if (recordLength > 0xFFFF) {
            throw new DbfValidationException("Record length " + recordLength
                + " bytes exceeds the DBF maximum of 65535", "54000");
        }

        // Header length (32-byte info block + 32 bytes per field + terminator)
        // is also a 16-bit field
        long headerLength = 32L + 32L * fields.size() + 1;
        if (headerLength > 0xFFFF) {
            throw new DbfValidationException("Too many columns (" + fields.size()
                + "): the header would exceed 65535 bytes", "54000");
        }
    }
    
    // Batch operations
    public void setBatchSize(int size) { this.batchSize = size; }
    
    public void addInsertBatch(Object[] values) throws IOException {
        batchBuffer.add(new BatchOperation(OperationType.INSERT, values, -1, null));
        if (batchBuffer.size() >= batchSize) {
            executeBatch();
        }
    }

    public void addUpdateBatch(int recordNumber, Object[] values) throws IOException {
        batchBuffer.add(new BatchOperation(OperationType.UPDATE, values, recordNumber, null));
        if (batchBuffer.size() >= batchSize) {
            executeBatch();
        }
    }

    public void addDeleteBatch(int recordNumber) throws IOException {
        batchBuffer.add(new BatchOperation(OperationType.DELETE, null, recordNumber, null));
        if (batchBuffer.size() >= batchSize) {
            executeBatch();
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
    /** DBF files end with a single 0x1A end-of-file marker. */
    private static final byte EOF_MARKER = 0x1A;

    public int insertRecord(Object[] values) throws IOException {
        fileLock.writeLock().lock();
        try {
            int newRecordNumber = doInsert(values);

            getHeader().setRecordCount(getRecordCount());
            getHeader().write(file, getCharset());

            return newRecordNumber;
        } finally {
            fileLock.writeLock().unlock();
        }
    }

    /** Bulk-write buffer target (bytes); records are flushed in chunks of this size. */
    private static final int INSERT_CHUNK_BYTES = 1 << 20; // 1 MB

    /**
     * Inserts many records. The records are serialized into a bounded in-memory
     * buffer and written to disk in large chunks -- ONE positioned write per
     * ~1 MB, then a single {@code setLength} and a single header rewrite -- so a
     * batch of N rows costs O(N/chunk) syscalls instead of the per-row
     * seek+write+setLength that {@link #doInsert} would do N times. Returns the
     * 1-based record number of each inserted row.
     */
    public int[] insertRecords(List<Object[]> rows) throws IOException {
        fileLock.writeLock().lock();
        try {
            int n = rows.size();
            int[] recordNumbers = new int[n];
            if (n == 0) {
                return recordNumbers;
            }
            // Escape hatch: -Ddbf.insert.bulk=off restores the per-row path.
            if ("off".equalsIgnoreCase(System.getProperty("dbf.insert.bulk"))) {
                for (int i = 0; i < n; i++) {
                    recordNumbers[i] = doInsert(rows.get(i));
                }
                getHeader().setRecordCount(getRecordCount());
                getHeader().write(file, getCharset());
                return recordNumbers;
            }

            int recordSize = header.getRecordSize();
            int startCount = getRecordCount();
            long offset = header.getHeaderSize() + (long) startCount * recordSize;
            file.seek(offset);

            int rowsPerChunk = Math.max(1, INSERT_CHUNK_BYTES / recordSize);
            byte[] buffer = new byte[Math.min(n, rowsPerChunk) * recordSize];
            int inBuf = 0;
            for (int i = 0; i < n; i++) {
                byte[] rec = createRecordData(rows.get(i)); // also appends any memo blocks
                rec[0] = DBFConstants.RECORD_ACTIVE;
                System.arraycopy(rec, 0, buffer, inBuf * recordSize, recordSize);
                recordNumbers[i] = startCount + i + 1;
                if (++inBuf == rowsPerChunk) {
                    file.write(buffer, 0, inBuf * recordSize); // one bulk write per chunk
                    inBuf = 0;
                }
            }
            if (inBuf > 0) {
                file.write(buffer, 0, inBuf * recordSize);
            }
            file.writeByte(EOF_MARKER);
            file.setLength(offset + (long) n * recordSize + 1); // one setLength for the whole batch

            header.setRecordCount(startCount + n);
            header.write(file, charset);
            return recordNumbers;
        } finally {
            fileLock.writeLock().unlock();
        }
    }

    private int doInsert(Object[] values) throws IOException {
        byte[] recordData = createRecordData(values);
        recordData[0] = DBFConstants.RECORD_ACTIVE;

        // Write over the EOF marker, not after it
        int newRecordNumber = getRecordCount() + 1;
        long offset = header.getHeaderSize()
            + (long) getRecordCount() * header.getRecordSize();
        file.seek(offset);
        file.write(recordData);
        file.writeByte(EOF_MARKER);
        file.setLength(offset + header.getRecordSize() + 1);

        return newRecordNumber;
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
                Number num;
                if (value instanceof Number) {
                    num = (Number) value;
                } else {
                    // Strings must actually be numeric - never store text
                    // in a numeric field
                    String text = value.toString().trim();
                    if (text.isEmpty()) {
                        return spaces(field.getLength());
                    }
                    try {
                        num = Double.parseDouble(text);
                    } catch (NumberFormatException e) {
                        throw new DbfValidationException("Value '" + text
                            + "' is not numeric (column " + field.getName() + ")",
                            DbfValidationException.DATA_MISMATCH);
                    }
                }
                // Format without java.util.Formatter (which re-parses a freshly
                // built format string on every field of every row -- a major
                // batch-insert cost). Produces the identical right-justified,
                // space-padded text that "%<len>d" / "%<len>.<dec>f" did.
                if (field.getDecimalCount() == 0) {
                    str = Long.toString(num.longValue());
                } else {
                    double d = num.doubleValue();
                    if (Double.isNaN(d) || Double.isInfinite(d)) {
                        throw new DbfValidationException("Value " + num + " is not a finite number"
                            + " (column " + field.getName() + ")",
                            DbfValidationException.NUMERIC_OUT_OF_RANGE);
                    }
                    // BigDecimal.valueOf(d) == new BigDecimal(Double.toString(d)),
                    // i.e. the shortest round-trip decimal -- the same basis the
                    // JDK Formatter uses, so HALF_UP here matches "%.Nf" exactly
                    // (e.g. 1.005 -> "1.01", 2.675 -> "2.68").
                    str = java.math.BigDecimal.valueOf(d)
                        .setScale(field.getDecimalCount(), java.math.RoundingMode.HALF_UP)
                        .toPlainString();
                    // BigDecimal has no signed zero, but Formatter keeps the sign
                    // ("%.2f" of -0.001 -> "-0.00"). Match it for byte-identical output.
                    if (str.charAt(0) != '-'
                            && (Double.doubleToRawLongBits(d) & Long.MIN_VALUE) != 0L) {
                        str = "-" + str;
                    }
                }
                // Truncating digits would silently corrupt the value
                if (str.length() > field.getLength()) {
                    throw new DbfValidationException("Value " + num + " does not fit column "
                        + field.getName() + " " + field.getType() + "(" + field.getLength()
                        + (field.getDecimalCount() > 0 ? "," + field.getDecimalCount() : "") + ")",
                        DbfValidationException.NUMERIC_OUT_OF_RANGE);
                }
                return padLeft(str.getBytes(charset), field.getLength());

            case DBFConstants.FIELD_TYPE_DATE:
                if (value instanceof java.util.Date) {
                    str = new SimpleDateFormat("yyyyMMdd").format((java.util.Date) value);
                } else {
                    // Accept yyyyMMdd or yyyy-MM-dd strings; reject the rest
                    String text = value.toString().trim();
                    if (text.isEmpty()) {
                        return spaces(field.getLength());
                    }
                    String digits = text.replace("-", "");
                    if (!digits.matches("\\d{8}")) {
                        throw new DbfValidationException("Invalid date '" + text
                            + "' for column " + field.getName()
                            + " (expected yyyy-MM-dd or yyyyMMdd)",
                            DbfValidationException.INVALID_DATETIME);
                    }
                    str = digits;
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
        int blockNumber = memoFile.writeMemo(text);
        // dBASE stores the block number as right-aligned ASCII digits
        String blockText = String.format("%" + field.getLength() + "d", blockNumber);
        return blockText.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
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

    /** Rewrites the header in place (e.g. after a column rename). */
    public void flushHeader() throws IOException {
        fileLock.writeLock().lock();
        try {
            header.write(file, charset);
        } finally {
            fileLock.writeLock().unlock();
        }
    }
    
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