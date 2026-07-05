package com.dbf.jdbc.index;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.*;

/**
 * dBASE NDX index file handler (single-index file)
 */
public class NDXIndex {
    private final RandomAccessFile indexFile;
    private final String expression;
    private final List<IndexEntry> entries = new ArrayList<>();
    private boolean loaded = false;
    private String indexFilePath;
    
    // Add these fields
    private String columnName;
    private boolean isUnique;
    private long entryCount;
    
    private static class IndexEntry implements Comparable<IndexEntry> {
        final String key;
        final int recordNumber;
        
        IndexEntry(String key, int recordNumber) {
            this.key = key;
            this.recordNumber = recordNumber;
        }
        
        @Override
        public int compareTo(IndexEntry o) {
            return key.compareToIgnoreCase(o.key);
        }
    }
    
    public NDXIndex(String indexPath) throws IOException {
        this.indexFilePath = indexPath;
        this.indexFile = new RandomAccessFile(indexPath, "r");
        
        // Read NDX header
        indexFile.seek(0);
        byte[] header = new byte[4];
        indexFile.readFully(header);
        
        // Read expression (null-terminated)
        List<Byte> exprBytes = new ArrayList<>();
        byte b;
        while ((b = indexFile.readByte()) != 0) {
            exprBytes.add(b);
        }
        byte[] exprArray = new byte[exprBytes.size()];
        for (int i = 0; i < exprBytes.size(); i++) {
            exprArray[i] = exprBytes.get(i);
        }
        this.expression = new String(exprArray, Charset.forName("UTF-8"));
        
        // Parse column name from expression
        this.columnName = parseColumnName(expression);
        this.isUnique = false; // NDX files are not necessarily unique
    }
    
    private String parseColumnName(String expression) {
        // Simple parsing - remove UPPER(), LOWER(), TRIM(), etc.
        String cleaned = expression.toUpperCase();
        cleaned = cleaned.replace("UPPER(", "");
        cleaned = cleaned.replace("LOWER(", "");
        cleaned = cleaned.replace("TRIM(", "");
        cleaned = cleaned.replace(")", "");
        cleaned = cleaned.trim();
        return cleaned;
    }
    
    public void load() throws IOException {
        if (loaded) return;
        
        // Read index entries
        long position = indexFile.getFilePointer();
        indexFile.seek(position);
        
        while (indexFile.getFilePointer() < indexFile.length()) {
            // Read key length
            int keyLen = indexFile.readUnsignedByte();
            
            // Read key
            byte[] keyBytes = new byte[keyLen];
            indexFile.readFully(keyBytes);
            String key = new String(keyBytes, Charset.forName("UTF-8"));
            
            // Read record number
            int recordNumber = indexFile.readInt();
            
            entries.add(new IndexEntry(key, recordNumber));
            
            // Skip next pointer and other metadata
            indexFile.skipBytes(8);
        }
        
        // Sort entries for binary search
        Collections.sort(entries);
        this.entryCount = entries.size();
        loaded = true;
    }
    
    public int findRecord(String key) {
        if (!loaded) return -1;
        
        // Binary search
        int left = 0, right = entries.size() - 1;
        while (left <= right) {
            int mid = (left + right) / 2;
            IndexEntry entry = entries.get(mid);
            int cmp = entry.key.compareToIgnoreCase(key);
            
            if (cmp == 0) {
                return entry.recordNumber;
            } else if (cmp < 0) {
                left = mid + 1;
            } else {
                right = mid - 1;
            }
        }
        return -1;
    }
    
    public List<Integer> findRange(String startKey, String endKey) {
        List<Integer> results = new ArrayList<>();
        if (!loaded) return results;
        
        for (IndexEntry entry : entries) {
            if (entry.key.compareToIgnoreCase(startKey) >= 0 &&
                entry.key.compareToIgnoreCase(endKey) <= 0) {
                results.add(entry.recordNumber);
            }
        }
        return results;
    }
    
    // Getter methods - ADD THESE
    public String getColumnName() {
        return columnName;
    }
    
    public String getExpression() {
        return expression;
    }
    
    public boolean isUnique() {
        return isUnique;
    }
    
    public long getEntryCount() {
        return entryCount;
    }
    
    public int getSize() {
        return entries.size();
    }
    
    public String getIndexFilePath() {
        return indexFilePath;
    }
    
    public void close() throws IOException {
        indexFile.close();
    }
}