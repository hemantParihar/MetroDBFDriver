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

    // Sequential fast path: remember the last page so consecutive records in the
    // same page skip the cache HashMap lookup entirely.
    private long lastPageNum = -1;
    private byte[] lastPage;
    
    // Larger pages mean far fewer read syscalls on a sequential scan (a 48 MB
    // table is ~750 reads at 64 KB vs ~5,800 at 8 KB), which dominates scan time.
    // Keep the cache cap at ~1 MB (16 x 64 KB) -- same footprint as the old
    // 128 x 8 KB -- so memory-bounded joins/sorts stay within budget; a
    // sequential scan only needs 1-2 live pages anyway.
    private static final int PAGE_SIZE = 65536;
    private static final int MAX_PAGES = 16;
    
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

        byte[] page;
        if (pageNum == lastPageNum) {
            page = lastPage; // sequential fast path: same page as the previous record
        } else {
            page = pageCache.get(pageNum);
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
            lastPageNum = pageNum;
            lastPage = page;
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

    /**
     * The internal record buffer WITHOUT copying -- for callers that decode it
     * immediately and do not retain it (the streaming scan path). The contents
     * are overwritten on the next navigation call, so never hold this reference.
     */
    public byte[] getCurrentRecordBuffer() {
        if (currentRecordNumber < 0 || currentRecordNumber >= totalRecords) {
            return null;
        }
        return currentRecordBuffer;
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