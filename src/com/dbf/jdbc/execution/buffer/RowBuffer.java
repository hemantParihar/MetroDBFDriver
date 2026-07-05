package com.dbf.jdbc.execution.buffer;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Memory-aware row buffer that spills to disk when memory threshold exceeded
 * Used for sort, group by, and join operations
 * Java 8 compatible
 */
public class RowBuffer implements Closeable {
    private static final long DEFAULT_MEMORY_LIMIT = 50 * 1024 * 1024; // 50MB
    private static final int DEFAULT_PAGE_SIZE = 10000;
    private static final AtomicLong tempFileCounter = new AtomicLong();
    
    private final long memoryLimit;
    private final int pageSize;
    private final List<Object[]> memoryBuffer;
    private final List<SpillFile> spillFiles;
    private long estimatedMemoryUsage = 0;
    private long totalRows = 0;
    private boolean spilled = false;
    private File tempDir;
    
    private static class SpillFile {
        final File file;
        final long rowCount;
        final int columnCount;
        final RandomAccessFile raf;
        
        SpillFile(File file, long rowCount, int columnCount) throws IOException {
            this.file = file;
            this.rowCount = rowCount;
            this.columnCount = columnCount;
            this.raf = new RandomAccessFile(file, "rw");
        }
        
        void close() throws IOException {
            raf.close();
        }
        
        void delete() {
            file.delete();
        }
    }
    
    public RowBuffer() {
        this(DEFAULT_MEMORY_LIMIT, DEFAULT_PAGE_SIZE);
    }
    
    public RowBuffer(long memoryLimit, int pageSize) {
        this.memoryLimit = memoryLimit;
        this.pageSize = pageSize;
        this.memoryBuffer = new ArrayList<>(pageSize);
        this.spillFiles = new ArrayList<>();
        this.tempDir = createTempDir();
    }
    
    private File createTempDir() {
        try {
            String tempDirPath = System.getProperty("java.io.tmpdir");
            File baseDir = new File(tempDirPath, "dbf_join_" + System.currentTimeMillis());
            baseDir.mkdirs();
            baseDir.deleteOnExit();
            return baseDir;
        } catch (Exception e) {
            // Fallback to system temp
            return new File(System.getProperty("java.io.tmpdir"));
        }
    }
    
    public void add(Object[] row) {
        totalRows++;
        
        // Estimate memory usage (rough approximation)
        estimatedMemoryUsage += estimateRowSize(row);
        
        if (estimatedMemoryUsage < memoryLimit) {
            memoryBuffer.add(copyRow(row));
        } else {
            spillToDisk();
            memoryBuffer.add(copyRow(row));
        }
    }
    
    private long estimateRowSize(Object[] row) {
        long size = 64; // Object overhead
        for (Object value : row) {
            if (value == null) {
                size += 8;
            } else if (value instanceof String) {
                size += ((String) value).length() * 2;
            } else {
                size += 16;
            }
        }
        return size;
    }
    
    private Object[] copyRow(Object[] row) {
        if (row == null) return null;
        Object[] copy = new Object[row.length];
        System.arraycopy(row, 0, copy, 0, row.length);
        return copy;
    }
    
    private void spillToDisk() {
        if (memoryBuffer.isEmpty()) return;
        
        try {
            File spillFile = new File(tempDir, "spill_" + tempFileCounter.incrementAndGet() + ".tmp");
            spillFile.deleteOnExit();
            SpillFile spill = new SpillFile(spillFile, memoryBuffer.size(), 
                memoryBuffer.isEmpty() ? 0 : memoryBuffer.get(0).length);
            
            // Write rows to spill file
            for (Object[] row : memoryBuffer) {
                writeRowToFile(spill.raf, row);
            }
            
            spillFiles.add(spill);
            memoryBuffer.clear();
            estimatedMemoryUsage = 0;
            spilled = true;
            
        } catch (IOException e) {
            throw new RuntimeException("Failed to spill to disk", e);
        }
    }
    
    private void writeRowToFile(RandomAccessFile raf, Object[] row) throws IOException {
        for (Object value : row) {
            if (value == null) {
                raf.writeByte(0);
            } else if (value instanceof String) {
                raf.writeByte(1);
                byte[] bytes = ((String) value).getBytes("UTF-8");
                raf.writeInt(bytes.length);
                raf.write(bytes);
            } else if (value instanceof Integer) {
                raf.writeByte(2);
                raf.writeInt((Integer) value);
            } else if (value instanceof Long) {
                raf.writeByte(3);
                raf.writeLong((Long) value);
            } else if (value instanceof Double) {
                raf.writeByte(4);
                raf.writeDouble((Double) value);
            } else if (value instanceof Boolean) {
                raf.writeByte(5);
                raf.writeBoolean((Boolean) value);
            } else if (value instanceof java.sql.Date) {
                raf.writeByte(6);
                raf.writeLong(((java.sql.Date) value).getTime());
            } else {
                raf.writeByte(7);
                byte[] bytes = value.toString().getBytes("UTF-8");
                raf.writeInt(bytes.length);
                raf.write(bytes);
            }
        }
    }
    
    private Object[] readRowFromFile(RandomAccessFile raf, int columnCount) throws IOException {
        Object[] row = new Object[columnCount];
        for (int i = 0; i < columnCount; i++) {
            int type = raf.readByte();
            switch (type) {
                case 0:
                    row[i] = null;
                    break;
                case 1:
                    int len = raf.readInt();
                    byte[] bytes = new byte[len];
                    raf.readFully(bytes);
                    row[i] = new String(bytes, "UTF-8");
                    break;
                case 2:
                    row[i] = raf.readInt();
                    break;
                case 3:
                    row[i] = raf.readLong();
                    break;
                case 4:
                    row[i] = raf.readDouble();
                    break;
                case 5:
                    row[i] = raf.readBoolean();
                    break;
                case 6:
                    row[i] = new java.sql.Date(raf.readLong());
                    break;
                case 7:
                    int strLen = raf.readInt();
                    byte[] strBytes = new byte[strLen];
                    raf.readFully(strBytes);
                    row[i] = new String(strBytes, "UTF-8");
                    break;
            }
        }
        return row;
    }
    
    public RowIterator iterator() {
        return new RowIterator();
    }
    
    public class RowIterator implements Iterator<Object[]>, Closeable {
        private int memoryIndex = 0;
        private int spillFileIndex = 0;
        private SpillFile currentSpillFile = null;
        private boolean closed = false;
        
        @Override
        public boolean hasNext() {
            if (closed) return false;
            
            if (memoryIndex < memoryBuffer.size()) {
                return true;
            }
            
            if (spillFileIndex < spillFiles.size()) {
                return true;
            }
            
            return false;
        }
        
        @Override
        public Object[] next() {
            if (memoryIndex < memoryBuffer.size()) {
                return memoryBuffer.get(memoryIndex++);
            }
            
            if (spillFileIndex < spillFiles.size()) {
                try {
                    if (currentSpillFile == null) {
                        currentSpillFile = spillFiles.get(spillFileIndex);
                        currentSpillFile.raf.seek(0);
                    }
                    
                    if (currentSpillFile.raf.getFilePointer() < currentSpillFile.raf.length()) {
                        return readRowFromFile(currentSpillFile.raf, currentSpillFile.columnCount);
                    } else {
                        currentSpillFile.close();
                        spillFileIndex++;
                        currentSpillFile = null;
                        return next();
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            
            throw new NoSuchElementException();
        }
        
        public long getRemainingCount() {
            long remaining = memoryBuffer.size() - memoryIndex;
            for (int i = spillFileIndex; i < spillFiles.size(); i++) {
                remaining += spillFiles.get(i).rowCount;
            }
            return remaining;
        }
        
        @Override
        public void close() throws IOException {
            if (!closed) {
                closed = true;
                if (currentSpillFile != null) {
                    currentSpillFile.close();
                }
            }
        }
        
        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }
    
    public long getRowCount() {
        return totalRows;
    }
    
    public boolean hasSpilled() {
        return spilled;
    }
    
    @Override
    public void close() throws IOException {
        for (SpillFile spillFile : spillFiles) {
            try {
                spillFile.close();
                spillFile.delete();
            } catch (IOException e) {
                // Ignore
            }
        }
        spillFiles.clear();
        memoryBuffer.clear();
        
        // Clean up temp directory
        try {
            if (tempDir != null && tempDir.exists()) {
                tempDir.delete();
            }
        } catch (Exception e) {
            // Ignore
        }
    }
}