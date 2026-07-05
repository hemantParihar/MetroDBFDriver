package com.dbf.jdbc;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import com.dbf.jdbc.dbf.DBFReader;
import com.dbf.jdbc.dbf.DBFWriter;
import com.dbf.jdbc.execution.streaming.RowStream;
import com.dbf.jdbc.execution.streaming.StreamingFilterOperator;
import com.dbf.jdbc.execution.streaming.StreamingTableScanOperator;
import com.dbf.jdbc.index.NDXIndex;
import com.dbf.jdbc.join.HashJoinOperator;
import com.dbf.jdbc.join.IndexNestedLoopJoinOperator;
import com.dbf.jdbc.join.JoinStrategySelector;
import com.dbf.jdbc.join.SortMergeJoinOperator;
import com.dbf.jdbc.parser.Lexer;
import com.dbf.jdbc.parser.Parser;
import com.dbf.jdbc.parser.ast.JoinNode;
import com.dbf.jdbc.parser.ast.SelectNode;

public class DBFStatement implements Statement {
    protected DBFConnection connection;
    protected DBFResultSet currentResultSet;
    protected List<String> batchCommands = new ArrayList<>();
    protected boolean closed = false;
    protected int fetchSize = 100;
    protected int maxRows = 0;
    protected int queryTimeout = 0;
    protected boolean escapeProcessing = true;
    protected SQLWarning warnings = null;
    protected List<Integer> batchResults = new ArrayList<>();
    
    public DBFStatement(DBFConnection connection) {
        this.connection = connection;
    }
    
 // Update the executeQuery method in DBFStatement.java to use QueryExecutor

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        checkClosed();
        closeCurrentResultSet();
        
        try {
            // Parse SQL
            Lexer lexer = new Lexer(new StringReader(sql));
            Parser parser = new Parser(new StringReader(sql));
            SelectNode selectNode = parser.parseSelect();
            
            // Open DBF file
            String tableName = selectNode.getFrom().getTableName();
            String filePath = connection.getPath() + "/" + tableName + ".dbf";
            
            DBFReader reader = new DBFReader(filePath, getCharset());
            
            // Check if we have a JOIN
            boolean hasJoin = selectNode.getJoin() != null;
            // Check if we have aggregation
            boolean hasAggregation = selectNode.hasAggregates() || selectNode.getGroupBy() != null;
            
            DBFResultSet resultSet;
            
         // In DBFStatement.executeQuery() - replace join handling with:

            if (hasJoin) {
                JoinNode join = selectNode.getJoin();
                String rightTable = join.getRightTable();
                String rightPath = connection.getPath() + "/" + rightTable + ".dbf";
                DBFReader rightReader = new DBFReader(rightPath, getCharset());
                
                // Get sizes
                long leftSize = reader.getHeader().getRecordCount();
                long rightSize = rightReader.getHeader().getRecordCount();
                
                // Check for index on join column
                String indexColumn = join.getCondition().getRight().getValue();
                String indexPath = connection.getPath() + "/" + rightTable + ".ndx";
                boolean hasIndex = new java.io.File(indexPath).exists();
                
                // Select optimal strategy
                JoinStrategySelector.JoinStrategy strategy = JoinStrategySelector.selectStrategy(
                    leftSize, rightSize, hasIndex, false);
                
                RowStream leftStream = new StreamingTableScanOperator(reader, tableName, null);
                RowStream rightStream = new StreamingTableScanOperator(rightReader, rightTable, null);
                RowStream joinStream;
                
                switch (strategy.strategy) {
                    case HASH_JOIN:
                        joinStream = new HashJoinOperator(leftStream, rightStream,
                            join.getCondition().getLeft().getValue(),
                            join.getCondition().getRight().getValue(),
                            HashJoinOperator.JoinType.valueOf(join.getJoinType().name()));
                        break;
                    case INDEX_NESTED:
                        NDXIndex index = new NDXIndex(indexPath);
                        index.load();
                        joinStream = new IndexNestedLoopJoinOperator(leftStream, rightReader, index,
                            join.getCondition().getLeft().getValue(),
                            join.getCondition().getRight().getValue());
                        break;
                    case SORT_MERGE:
                        // Sort both sides first
                        joinStream = new SortMergeJoinOperator(leftStream, rightStream,
                            join.getCondition().getLeft().getValue(),
                            join.getCondition().getRight().getValue(),
                            SortMergeJoinOperator.JoinType.valueOf(join.getJoinType().name()));
                        break;
                    default:
                        joinStream = new HashJoinOperator(leftStream, rightStream,
                            join.getCondition().getLeft().getValue(),
                            join.getCondition().getRight().getValue(),
                            HashJoinOperator.JoinType.valueOf(join.getJoinType().name()));
                }
                
                // Add filter after join
                if (selectNode.getWhere() != null) {
                    joinStream = new StreamingFilterOperator(joinStream, selectNode.getWhere().getCondition());
                }
                
                resultSet = new DBFResultSet(joinStream, selectNode);
            }else if (hasAggregation) {
                // Handle aggregation - pass to DBFResultSet constructor which handles it
                resultSet = new DBFResultSet(reader, selectNode);
                
            } else {
                // Regular query with possible WHERE and ORDER BY
                resultSet = new DBFResultSet(reader, selectNode);
            }
            
            resultSet.setFetchSize(fetchSize);
            currentResultSet = resultSet;
            
            return currentResultSet;
            
        } catch (IOException e) {
            throw new SQLException("Error executing query: " + e.getMessage(), e);
        } catch (Parser.ParseException e) {
            throw new SQLException("SQL parse error: " + e.getMessage(), e);
        }
    }
    
    private Charset getCharset() {
        String charsetName = connection.getCharset();
        if (charsetName == null || charsetName.isEmpty()) {
            return Charset.forName("UTF-8");
        }
        try {
            return Charset.forName(charsetName);
        } catch (Exception e) {
            return Charset.forName("UTF-8");
        }
    }
    
    @Override
    public int executeUpdate(String sql) throws SQLException {
        checkClosed();
        
        String upperSql = sql.trim().toUpperCase();
        
        if (upperSql.startsWith("INSERT")) {
            return executeInsert(sql);
        } else if (upperSql.startsWith("UPDATE")) {
            return executeUpdateStatement(sql);
        } else if (upperSql.startsWith("DELETE")) {
            return executeDelete(sql);
        }
        
        throw new SQLException("Unsupported SQL statement: " + sql);
    }
    
    private int executeInsert(String sql) throws SQLException {
        try {
            // Parse INSERT INTO table (col1, col2) VALUES (val1, val2)
            int intoPos = sql.toUpperCase().indexOf("INTO") + 4;
            int parenPos = sql.indexOf("(", intoPos);
            String tableName = sql.substring(intoPos, parenPos).trim();
            
            int closeParen = sql.indexOf(")", parenPos);
            String columnsStr = sql.substring(parenPos + 1, closeParen);
            String[] columns = columnsStr.split(",");
            for (int i = 0; i < columns.length; i++) {
                columns[i] = columns[i].trim();
            }
            
            int valuesPos = sql.toUpperCase().indexOf("VALUES") + 6;
            int valuesStart = sql.indexOf("(", valuesPos);
            int valuesEnd = sql.lastIndexOf(")");
            String valuesStr = sql.substring(valuesStart + 1, valuesEnd);
            
            // Parse values
            List<String> valueList = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean inQuote = false;
            for (char c : valuesStr.toCharArray()) {
                if (c == '\'' && (current.length() == 0 || current.charAt(current.length() - 1) != '\\')) {
                    inQuote = !inQuote;
                    current.append(c);
                } else if (c == ',' && !inQuote) {
                    valueList.add(current.toString().trim());
                    current = new StringBuilder();
                } else {
                    current.append(c);
                }
            }
            if (current.length() > 0) {
                valueList.add(current.toString().trim());
            }
            
            Object[] values = new Object[valueList.size()];
            for (int i = 0; i < valueList.size(); i++) {
                values[i] = parseValue(valueList.get(i));
            }
            
            String filePath = connection.getPath() + "/" + tableName + ".dbf";
            try (DBFWriter writer = new DBFWriter(filePath, getCharset())) {
                return writer.insertRecord(values);
            }
        } catch (IOException e) {
            throw new SQLException("Insert failed: " + e.getMessage(), e);
        }
    }
    
    private int executeUpdateStatement(String sql) throws SQLException {
        try {
            // Parse UPDATE table SET col1 = val1, col2 = val2 WHERE condition
            int setPos = sql.toUpperCase().indexOf("SET") + 3;
            String beforeSet = sql.substring(6, setPos - 3).trim();
            String tableName = beforeSet;
            
            int wherePos = sql.toUpperCase().indexOf("WHERE");
            String setClause;
            String whereClause = null;
            
            if (wherePos > 0) {
                setClause = sql.substring(setPos, wherePos).trim();
                whereClause = sql.substring(wherePos + 5).trim();
            } else {
                setClause = sql.substring(setPos).trim();
            }
            
            // Parse SET assignments
            String[] assignments = setClause.split(",");
            String[] setColumns = new String[assignments.length];
            Object[] setValues = new Object[assignments.length];
            
            for (int i = 0; i < assignments.length; i++) {
                String[] parts = assignments[i].split("=");
                setColumns[i] = parts[0].trim();
                setValues[i] = parseValue(parts[1].trim());
            }
            
            String filePath = connection.getPath() + "/" + tableName + ".dbf";
            try (DBFReader reader = new DBFReader(filePath, getCharset());
                 DBFWriter writer = new DBFWriter(filePath, getCharset())) {
                
                int updated = 0;
                reader.beforeFirst();
                while (reader.next()) {
                    if (whereClause == null || evaluateWhereCondition(reader, whereClause)) {
                        if (writer.updateRecord(reader.getCurrentRecord() + 1, setValues)) {
                            updated++;
                        }
                    }
                }
                return updated;
            }
        } catch (IOException e) {
            throw new SQLException("Update failed: " + e.getMessage(), e);
        }
    }
    
    private int executeDelete(String sql) throws SQLException {
        try {
            // Parse DELETE FROM table WHERE condition
            int fromPos = sql.toUpperCase().indexOf("FROM") + 4;
            int wherePos = sql.toUpperCase().indexOf("WHERE");
            String tableName;
            String whereClause = null;
            
            if (wherePos > 0) {
                tableName = sql.substring(fromPos, wherePos).trim();
                whereClause = sql.substring(wherePos + 5).trim();
            } else {
                tableName = sql.substring(fromPos).trim();
            }
            
            String filePath = connection.getPath() + "/" + tableName + ".dbf";
            try (DBFReader reader = new DBFReader(filePath, getCharset());
                 DBFWriter writer = new DBFWriter(filePath, getCharset())) {
                
                int deleted = 0;
                reader.beforeFirst();
                while (reader.next()) {
                    if (whereClause == null || evaluateWhereCondition(reader, whereClause)) {
                        if (writer.deleteRecord(reader.getCurrentRecord() + 1)) {
                            deleted++;
                        }
                    }
                }
                return deleted;
            }
        } catch (IOException e) {
            throw new SQLException("Delete failed: " + e.getMessage(), e);
        }
    }
    
    private Object parseValue(String value) {
        value = value.trim();
        if (value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e1) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException e2) {
                return value;
            }
        }
    }
    
    private boolean evaluateWhereCondition(DBFReader reader, String whereClause) throws IOException, SQLException {
        // Simple WHERE clause evaluation
        String[] parts = whereClause.split("=");
        if (parts.length == 2) {
            String colName = parts[0].trim();
            String expected = parts[1].trim().replace("'", "");
            Object actual = reader.getValue(colName);
            if (actual == null) return false;
            return expected.equalsIgnoreCase(actual.toString());
        }
        return true;
    }
    
    @Override
    public void addBatch(String sql) throws SQLException {
        checkClosed();
        batchCommands.add(sql);
    }
    
    @Override
    public void clearBatch() throws SQLException {
        checkClosed();
        batchCommands.clear();
        batchResults.clear();
    }
    
    @Override
    public int[] executeBatch() throws SQLException {
        checkClosed();
        
        if (batchCommands.isEmpty()) {
            return new int[0];
        }
        
        int[] results = new int[batchCommands.size()];
        
        for (int i = 0; i < batchCommands.size(); i++) {
            try {
                results[i] = executeUpdate(batchCommands.get(i));
            } catch (SQLException e) {
                results[i] = -3; // Batch update failed
            }
        }
        
        batchCommands.clear();
        return results;
    }
    
    @Override
    public boolean execute(String sql) throws SQLException {
        String upperSql = sql.trim().toUpperCase();
        if (upperSql.startsWith("SELECT")) {
            executeQuery(sql);
            return true;
        } else {
            executeUpdate(sql);
            return false;
        }
    }
    
    @Override
    public ResultSet getResultSet() throws SQLException {
        return currentResultSet;
    }
    
    @Override
    public int getUpdateCount() throws SQLException {
        return -1;
    }
    
    @Override
    public boolean getMoreResults() throws SQLException {
        closeCurrentResultSet();
        return false;
    }
    
    @Override
    public void setFetchSize(int rows) throws SQLException {
        if (rows < 0) throw new SQLException("Fetch size cannot be negative");
        this.fetchSize = rows;
        if (currentResultSet != null) {
            currentResultSet.setFetchSize(rows);
        }
    }
    
    @Override
    public int getFetchSize() throws SQLException {
        return fetchSize;
    }
    
    @Override
    public int getResultSetConcurrency() throws SQLException {
        return ResultSet.CONCUR_READ_ONLY;
    }
    
    @Override
    public int getResultSetType() throws SQLException {
        return ResultSet.TYPE_FORWARD_ONLY;
    }
    
    @Override
    public Connection getConnection() throws SQLException {
        return connection;
    }
    
    @Override
    public void close() throws SQLException {
        if (!closed) {
            closeCurrentResultSet();
            closed = true;
        }
    }
    
    @Override
    public boolean isClosed() throws SQLException {
        return closed;
    }
    
    @Override
    public void setMaxRows(int max) throws SQLException {
        if (max < 0) throw new SQLException("Max rows cannot be negative");
        this.maxRows = max;
    }
    
    @Override
    public int getMaxRows() throws SQLException {
        return maxRows;
    }
    
    @Override
    public void setQueryTimeout(int seconds) throws SQLException {
        if (seconds < 0) throw new SQLException("Query timeout cannot be negative");
        this.queryTimeout = seconds;
    }
    
    @Override
    public int getQueryTimeout() throws SQLException {
        return queryTimeout;
    }
    
    @Override
    public SQLWarning getWarnings() throws SQLException {
        return warnings;
    }
    
    @Override
    public void clearWarnings() throws SQLException {
        warnings = null;
    }
    
    @Override
    public void setCursorName(String name) throws SQLException {
        // Not supported
    }
    
    @Override
    public void setEscapeProcessing(boolean enable) throws SQLException {
        this.escapeProcessing = enable;
    }
    
    @Override
    public int getMaxFieldSize() throws SQLException {
        return 0;
    }
    
    @Override
    public void setMaxFieldSize(int max) throws SQLException {
        // Ignore
    }
    
    @Override
    public void setFetchDirection(int direction) throws SQLException {
        // Not implemented
    }
    
    @Override
    public int getFetchDirection() throws SQLException {
        return ResultSet.FETCH_FORWARD;
    }
    
    @Override
    public boolean getMoreResults(int current) throws SQLException {
        return getMoreResults();
    }
    
    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        throw new SQLFeatureNotSupportedException("getGeneratedKeys not supported");
    }
    
    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        return executeUpdate(sql);
    }
    
    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        return executeUpdate(sql);
    }
    
    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        return executeUpdate(sql);
    }
    
    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        return execute(sql);
    }
    
    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        return execute(sql);
    }
    
    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        return execute(sql);
    }
    
    @Override
    public int getResultSetHoldability() throws SQLException {
        return ResultSet.HOLD_CURSORS_OVER_COMMIT;
    }
    
    @Override
    public void setPoolable(boolean poolable) throws SQLException {
        // Not implemented
    }
    
    @Override
    public boolean isPoolable() throws SQLException {
        return false;
    }
    
    @Override
    public void closeOnCompletion() throws SQLException {
        // Not implemented
    }
    
    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        return false;
    }
    
    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("Cannot unwrap to " + iface);
    }
    
    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this);
    }
    
    protected void checkClosed() throws SQLException {
        if (closed) {
            throw new SQLException("Statement is closed");
        }
    }
    
    protected void closeCurrentResultSet() throws SQLException {
        if (currentResultSet != null) {
            currentResultSet.close();
            currentResultSet = null;
        }
    }
    
    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }

	@Override
	public void cancel() throws SQLException {
		// TODO Auto-generated method stub
		
	}
}