package com.dbf.jdbc.execution.streaming;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Streaming limit operator - implements RowStream for streaming execution
 * Different from the Operator-based LimitOperator
 */
public class StreamingLimitOperator implements RowStream {
    private final RowStream input;
    private final long limit;
    private long rowsReturned = 0;
    private boolean limitReached = false;
    private boolean closed = false;
    private final String[] columnNames;
    private final int[] columnTypes;
    
    public StreamingLimitOperator(RowStream input, long limit) {
        this.input = input;
        this.limit = limit;
        this.columnNames = input.getColumnNames();
        this.columnTypes = input.getColumnTypes();
    }
    
    @Override
    public Object[] next() throws IOException, SQLException {
        if (limitReached || rowsReturned >= limit) {
            limitReached = true;
            return null;
        }
        
        Object[] row = input.next();
        if (row == null) return null;
        
        rowsReturned++;
        return row;
    }
    
    @Override
    public String[] getColumnNames() {
        return columnNames;
    }
    
    @Override
    public int[] getColumnTypes() {
        return columnTypes;
    }
    
    @Override
    public void reset() throws IOException, SQLException {
        input.reset();
        rowsReturned = 0;
        limitReached = false;
    }
    
    @Override
    public boolean supportsReset() {
        return input.supportsReset();
    }
    
    @Override
    public long estimateRowCount() {
        return Math.min(input.estimateRowCount(), limit);
    }
    
    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            input.close();
        }
    }
}