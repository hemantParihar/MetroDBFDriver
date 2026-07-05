// MDXIndex.java
package com.dbf.jdbc.index;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.*;

/**
 * FoxPro MDX index file handler (multiple-index file)
 */
public class MDXIndex {
    private final RandomAccessFile indexFile;
    private final Map<String, MDXTag> tags = new HashMap<>();
    
    private static class MDXTag {
        final String name;
        final String expression;
        final List<IndexEntry> entries = new ArrayList<>();
        
        MDXTag(String name, String expression) {
            this.name = name;
            this.expression = expression;
        }
    }
    
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
    
    public MDXIndex(String indexPath) throws IOException {
        this.indexFile = new RandomAccessFile(indexPath, "r");
        
        // Read header
        indexFile.seek(0);
        byte[] signature = new byte[3];
        indexFile.readFully(signature);
        
        // Check for FoxPro signature
        if (signature[0] != 0x00 || signature[1] != 0x00 || signature[2] != 0x00) {
            throw new IOException("Invalid MDX file format");
        }
        
        // Skip next pointer
        indexFile.skipBytes(1);
        
        // Read tag count
        int tagCount = indexFile.readUnsignedShort();
        
        // Read tag offsets
        List<Integer> tagOffsets = new ArrayList<>();
        for (int i = 0; i < tagCount; i++) {
            tagOffsets.add(indexFile.readInt());
        }
        
        // Load each tag
        for (int offset : tagOffsets) {
            loadTag(offset);
        }
    }
    
    private void loadTag(int offset) throws IOException {
        indexFile.seek(offset);
        
        // Read tag name (10 bytes, null-terminated)
        byte[] nameBytes = new byte[10];
        indexFile.readFully(nameBytes);
        int nameLen = 0;
        while (nameLen < nameBytes.length && nameBytes[nameLen] != 0) nameLen++;
        String name = new String(nameBytes, 0, nameLen, Charset.forName("UTF-8"));
        
        // Skip some metadata
        indexFile.skipBytes(2);
        
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
        String expression = new String(exprArray, Charset.forName("UTF-8"));
        
        MDXTag tag = new MDXTag(name, expression);
        
        // Read root page
        int rootPage = indexFile.readInt();
        loadTagEntries(tag, rootPage);
        
        tags.put(name.toLowerCase(), tag);
    }
    
    private void loadTagEntries(MDXTag tag, int pageNumber) throws IOException {
        if (pageNumber == 0) return;
        
        indexFile.seek(pageNumber);
        
        // Read entries on this page
        int entryCount = indexFile.readUnsignedShort();
        
        for (int i = 0; i < entryCount; i++) {
            // Read key length
            int keyLen = indexFile.readUnsignedByte();
            
            // Read key
            byte[] keyBytes = new byte[keyLen];
            indexFile.readFully(keyBytes);
            String key = new String(keyBytes, Charset.forName("UTF-8"));
            
            // Read record number
            int recordNumber = indexFile.readInt();
            
            tag.entries.add(new IndexEntry(key, recordNumber));
            
            // Read child page pointer
            int childPage = indexFile.readInt();
            if (childPage != 0) {
                loadTagEntries(tag, childPage);
            }
        }
        
        // Sort entries
        Collections.sort(tag.entries);
    }
    
    public MDXTag getTag(String tagName) {
        return tags.get(tagName.toLowerCase());
    }
    
    public List<String> getTagNames() {
        return new ArrayList<>(tags.keySet());
    }
    
    public int findRecord(String tagName, String key) {
        MDXTag tag = tags.get(tagName.toLowerCase());
        if (tag == null) return -1;
        
        // Binary search
        int left = 0, right = tag.entries.size() - 1;
        while (left <= right) {
            int mid = (left + right) / 2;
            IndexEntry entry = tag.entries.get(mid);
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
    
    public List<Integer> findRange(String tagName, String startKey, String endKey) {
        MDXTag tag = tags.get(tagName.toLowerCase());
        List<Integer> results = new ArrayList<>();
        if (tag == null) return results;
        
        for (IndexEntry entry : tag.entries) {
            if (entry.key.compareToIgnoreCase(startKey) >= 0 &&
                entry.key.compareToIgnoreCase(endKey) <= 0) {
                results.add(entry.recordNumber);
            }
        }
        return results;
    }
    
    public void close() throws IOException {
        indexFile.close();
    }
}