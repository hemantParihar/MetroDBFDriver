package com.dbf.jdbc.execution.streaming;

import com.dbf.jdbc.execution.Operator;
import java.io.IOException;
import java.sql.SQLException;

/**
 * Adapter to wrap Operator-based LimitOperator as RowStream
 */
public class LimitOperatorAdapter implements RowStream {
    private final Operator limitOperator;
    private final RowStream input;
    private final String[] columnNames;
    private final int[] columnTypes;
    private boolean opened = false;
    
    public LimitOperatorAdapter(Operator limitOperator, RowStream input) {
        this.limitOperator = limitOperator;
        this.input = input;
        this.columnNames = input.getColumnNames();
        this.columnTypes = input.getColumnTypes();
    }
    
    @Override
    public Object[] next() throws IOException, SQLException {
        if (!opened) {
            limitOperator.open();
            opened = true;
        }
        
        try {
            return limitOperator.next();
        } catch (Exception e) {
            throw new SQLException(e);
        }
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
        if (opened) {
            limitOperator.close();
            opened = false;
        }
        input.reset();
    }
    
    @Override
    public boolean supportsReset() {
        return false;
    }
    
    @Override
    public long estimateRowCount() {
        return input.estimateRowCount();
    }
    
    @Override
    public void close() throws IOException {
        if (opened) {
            try {
				limitOperator.close();
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
            opened = false;
        }
        input.close();
    }
}