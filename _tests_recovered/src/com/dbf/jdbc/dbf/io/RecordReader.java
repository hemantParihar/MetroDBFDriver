package com.dbf.jdbc.dbf.io;

import com.dbf.jdbc.cache.PageCache;
import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Pure I/O reader for DBF records
 * Responsibilities:
 * - Physical record reading
 * - Page caching
 * - Record navigation (next, previous, absolute)
 * - Deleted flag detection
 * 
 * Does NOT handle:
 * - Field decoding
 * - Memo resolution
 * - Data type conversion
 */
public class RecordReader implements Closeable {
    private final RandomAccessFile file;
    private final PageCache pageCache;
    private final int headerSize;
    private final int recordSize;
    private final int totalRecords;
    private final boolean hasMemo;
    
    private byte[] currentRecordBuffer;
    private int currentRecordNumber = -1;
    private boolean currentRecordDeleted = false;
    private boolean closed = false;
    
    private static final int PAGE_SIZE = 8192;
    private static final int MAX_PAGES = 128;
    
    public RecordReader(RandomAccessFile file, int headerSize, int recordSize, int totalRecords, boolean hasMemo) {
        this.file = file;
        this.headerSize = headerSize;
        this.recordSize = recordSize;
        this.totalRecords = totalRecords;
        this.hasMemo = hasMemo;
        this.pageCache = new PageCache(PAGE_SIZE, MAX_PAGES);
        this.currentRecordBuffer = new byte[recordSize];
    }
    
    public boolean next() throws IOException {
        return moveToRecord(currentRecordNumber + 1);
    }
    
    public boolean previous() throws IOException {
        if (currentRecordNumber <= 0) return false;
        return moveToRecord(currentRecordNumber - 1);
    }
    
    public boolean moveToRecord(int recordNumber) throws IOException {
        if (recordNumber < 0 || recordNumber >= totalRecords) {
            return false;
        }
        
        long offset = headerSize + (long) recordNumber * recordSize;
        readRecordData(offset, currentRecordBuffer);
        
        currentRecordNumber = recordNumber;
        currentRecordDeleted = (currentRecordBuffer[0] == 0x2A);
        
        return true;
    }
    
    public boolean absolute(int recordNumber) throws IOException {
        int target = recordNumber > 0 ? recordNumber - 1 : totalRecords + recordNumber;
        return moveToRecord(target);
    }
    
    public void beforeFirst() {
        currentRecordNumber = -1;
    }
    
    public void afterLast() {
        currentRecordNumber = totalRecords;
    }
    
    private void readRecordData(long offset, byte[] buffer) throws IOException {
        long pageNum = offset / PAGE_SIZE;
        int pageOffset = (int) (offset % PAGE_SIZE);
        
        byte[] page = pageCache.get(pageNum);
        if (page == null) {
            page = new byte[PAGE_SIZE];
            file.seek(pageNum * PAGE_SIZE);
            int bytesRead = file.read(page);
            if (bytesRead < PAGE_SIZE) {
                byte[] trimmed = new byte[bytesRead];
                System.arraycopy(page, 0, trimmed, 0, bytesRead);
                page = trimmed;
            }
            pageCache.put(pageNum, page);
        }
        
        int bytesToCopy = Math.min(recordSize, page.length - pageOffset);
        System.arraycopy(page, pageOffset, buffer, 0, bytesToCopy);
        
        if (bytesToCopy < recordSize) {
            file.seek(offset + bytesToCopy);
            file.readFully(buffer, bytesToCopy, recordSize - bytesToCopy);
        }
    }
    
    public byte[] getCurrentRecordData() {
        if (currentRecordNumber < 0 || currentRecordNumber >= totalRecords) {
            return null;
        }
        byte[] copy = new byte[recordSize];
        System.arraycopy(currentRecordBuffer, 0, copy, 0, recordSize);
        return copy;
    }
    
    public int getCurrentRecordNumber() {
        return currentRecordNumber;
    }
    
    public boolean isCurrentRecordDeleted() {
        return currentRecordDeleted;
    }
    
    public int getTotalRecords() {
        return totalRecords;
    }
    
    public int getRecordSize() {
        return recordSize;
    }
    
    public boolean hasMemo() {
        return hasMemo;
    }
    
    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            pageCache.clear();
            file.close();
        }
    }
}