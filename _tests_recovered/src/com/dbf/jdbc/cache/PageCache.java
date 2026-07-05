package com.dbf.jdbc.cache;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LRU Page Cache for DBF file pages
 * Prevents loading entire file into memory
 */
public class PageCache {
    private final int pageSize;
    private final int maxPages;
    private final Map<Long, byte[]> cache;
    
    public PageCache(int pageSize, int maxPages) {
        this.pageSize = pageSize;
        this.maxPages = maxPages;
        this.cache = new LinkedHashMap<Long, byte[]>(maxPages, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
                return size() > maxPages;
            }
        };
    }
    
    public byte[] get(long pageNumber) {
        synchronized (cache) {
            return cache.get(pageNumber);
        }
    }
    
    public void put(long pageNumber, byte[] data) {
        synchronized (cache) {
            cache.put(pageNumber, data);
        }
    }
    
    public void remove(long pageNumber) {
        synchronized (cache) {
            cache.remove(pageNumber);
        }
    }
    
    public void clear() {
        synchronized (cache) {
            cache.clear();
        }
    }
    
    public int getPageSize() {
        return pageSize;
    }
    
    public int getMaxPages() {
        return maxPages;
    }
    
    public int size() {
        synchronized (cache) {
            return cache.size();
        }
    }
}