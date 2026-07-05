package com.dbf.jdbc.execution.streaming;

import java.io.Closeable;
import java.io.IOException;
import java.sql.SQLException;

/**
 * Streaming row iterator - NEVER materializes all rows
 */
public interface RowStream extends Closeable, AutoCloseable {
    /**
     * Get next row, or null if no more rows
     * Implementations should NOT cache rows
     */
    Object[] next() throws IOException, SQLException;
    
    /**
     * Get column names for this stream
     */
    String[] getColumnNames();
    
    /**
     * Get column types for this stream
     */
    int[] getColumnTypes();
    
    /**
     * Reset the stream to beginning (if supported)
     */
    void reset() throws IOException, SQLException;
    
    /**
     * Estimate total rows (may be approximate)
     */
    long estimateRowCount();
    
    /**
     * Check if this stream supports reset
     */
    boolean supportsReset();
    
    @Override
    void close() throws IOException;
}