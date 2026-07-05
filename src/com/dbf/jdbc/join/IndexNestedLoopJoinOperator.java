package com.dbf.jdbc.join;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.dbf.jdbc.dbf.DBFField;
import com.dbf.jdbc.dbf.DBFReader;
import com.dbf.jdbc.execution.streaming.RowStream;
import com.dbf.jdbc.index.NDXIndex;

/**
 * Index Nested Loop Join - O(n log m) when index exists
 * Uses existing NDX/MDX index on the join column
 */
public class IndexNestedLoopJoinOperator implements RowStream {
    private final RowStream outer;
    private final DBFReader innerReader;
    private final NDXIndex index;
    private final String outerKey;
    private final String innerKey;
    private final String[] outerColumns;
    private final String[] innerColumns;
    private final String[] outputColumns;
    private final int[] outputTypes;
    
    private Object[] currentOuterRow;
    private Object[] currentInnerRow;
    private boolean hasMore = true;
    
    public IndexNestedLoopJoinOperator(RowStream outer, DBFReader innerReader, NDXIndex index,
                                         String outerKey, String innerKey) {
        this.outer = outer;
        this.innerReader = innerReader;
        this.index = index;
        this.outerKey = outerKey;
        this.innerKey = innerKey;
        
        this.outerColumns = outer.getColumnNames();
        this.innerColumns = getColumnNames(innerReader);
        
        List<String> cols = new ArrayList<>();
        cols.addAll(Arrays.asList(outerColumns));
        cols.addAll(Arrays.asList(innerColumns));
        this.outputColumns = cols.toArray(new String[0]);
        this.outputTypes = new int[outputColumns.length];
        Arrays.fill(outputTypes, java.sql.Types.VARCHAR);
    }
    
    private String[] getColumnNames(DBFReader reader) {
        List<DBFField> fields = reader.getHeader().getFields();
        String[] names = new String[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            names[i] = fields.get(i).getName();
        }
        return names;
    }
    
    @Override
    public Object[] next() throws IOException, SQLException {
        while (true) {
            if (currentInnerRow != null) {
                // Return current match
                Object[] result = combineRows(currentOuterRow, currentInnerRow);
                currentInnerRow = null;
                return result;
            }
            
            // Get next outer row
            currentOuterRow = outer.next();
            if (currentOuterRow == null) {
                return null;
            }
            
            // Get key value
            Object key = getKey(currentOuterRow, outerColumns, outerKey);
            if (key == null) continue;
            
            // Look up in index
            int recordNumber = index.findRecord(key.toString());
            if (recordNumber > 0) {
                innerReader.absolute(recordNumber);
                currentInnerRow = readInnerRow();
            }
        }
    }
    
    private Object getKey(Object[] row, String[] columns, String keyName) {
        for (int i = 0; i < columns.length; i++) {
            if (columns[i].equalsIgnoreCase(keyName)) {
                return row[i];
            }
        }
        return null;
    }
    
    private Object[] readInnerRow() throws IOException {
        Object[] row = new Object[innerColumns.length];
        for (int i = 0; i < innerColumns.length; i++) {
            try {
				row[i] = innerReader.getValue(i);
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
        }
        return row;
    }
    
    private Object[] combineRows(Object[] outerRow, Object[] innerRow) {
        Object[] result = new Object[outerColumns.length + innerColumns.length];
        System.arraycopy(outerRow, 0, result, 0, outerColumns.length);
        System.arraycopy(innerRow, 0, result, outerColumns.length, innerColumns.length);
        return result;
    }
    
    @Override public String[] getColumnNames() { return outputColumns; }
    @Override public int[] getColumnTypes() { return outputTypes; }
    @Override public void reset() throws IOException, SQLException { outer.reset(); innerReader.beforeFirst(); }
    @Override public boolean supportsReset() { return outer.supportsReset(); }
    @Override public long estimateRowCount() { return outer.estimateRowCount(); }
    @Override public void close() throws IOException { outer.close(); innerReader.close(); }
}