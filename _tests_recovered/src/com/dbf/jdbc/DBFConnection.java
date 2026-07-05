package com.dbf.jdbc;

import java.nio.charset.Charset;
import java.sql.*;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

public class DBFConnection implements Connection {
    private final String path;
    private final String charset;
    private boolean closed = false;
    private boolean autoCommit = true;
    private int transactionIsolation = TRANSACTION_NONE;
    private SQLWarning warnings = null;
    private Properties clientInfo = new Properties();
    
    public DBFConnection(String path, String charset) {
        this.path = path;
        this.charset = charset;
    }
    
    public String getPath() {
        return path;
    }
    
    public String getCharset() {
        return charset;
    }
    
    @Override
    public Statement createStatement() throws SQLException {
        checkClosed();
        return new DBFStatement(this);
    }
    
//    @Override
//    public PreparedStatement prepareStatement(String sql) throws SQLException {
//        checkClosed();
//        return new DBFPreparedStatement(this, sql);
//    }
    
    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        throw new SQLFeatureNotSupportedException("CallableStatement not supported");
    }
    
    @Override
    public String nativeSQL(String sql) throws SQLException {
        checkClosed();
        return sql;
    }
    
    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        checkClosed();
        this.autoCommit = autoCommit;
    }
    
    @Override
    public boolean getAutoCommit() throws SQLException {
        checkClosed();
        return autoCommit;
    }
    
    @Override
    public void commit() throws SQLException {
        checkClosed();
        if (autoCommit) {
            throw new SQLException("Cannot commit when autoCommit is enabled");
        }
        // DBF doesn't support transactions, so nothing to do
    }
    
    @Override
    public void rollback() throws SQLException {
        checkClosed();
        if (autoCommit) {
            throw new SQLException("Cannot rollback when autoCommit is enabled");
        }
        // DBF doesn't support transactions, so nothing to do
    }
    
    @Override
    public void close() throws SQLException {
        closed = true;
    }
    
    @Override
    public boolean isClosed() throws SQLException {
        return closed;
    }
    
    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        checkClosed();
        return new DBFDatabaseMetaData(this);
    }
    
    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        checkClosed();
        // DBF is always read-only in this version
    }
    
    @Override
    public boolean isReadOnly() throws SQLException {
        checkClosed();
        return true;
    }
    
    @Override
    public void setCatalog(String catalog) throws SQLException {
        checkClosed();
        // Ignore
    }
    
    @Override
    public String getCatalog() throws SQLException {
        checkClosed();
        return path;
    }
    
    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        checkClosed();
        if (level != TRANSACTION_NONE) {
            throw new SQLException("DBF only supports TRANSACTION_NONE");
        }
        this.transactionIsolation = level;
    }
    
    @Override
    public int getTransactionIsolation() throws SQLException {
        checkClosed();
        return transactionIsolation;
    }
    
    @Override
    public SQLWarning getWarnings() throws SQLException {
        checkClosed();
        return warnings;
    }
    
    @Override
    public void clearWarnings() throws SQLException {
        checkClosed();
        warnings = null;
    }
    
    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        checkClosed();
        return createStatement();
    }
    
//    @Override
//    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
//        checkClosed();
//        return prepareStatement(sql);
//    }
    
    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        throw new SQLFeatureNotSupportedException("CallableStatement not supported");
    }
    
    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        throw new SQLFeatureNotSupportedException("getTypeMap not supported");
    }
    
    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        throw new SQLFeatureNotSupportedException("setTypeMap not supported");
    }
    
    @Override
    public void setHoldability(int holdability) throws SQLException {
        checkClosed();
        // Ignore
    }
    
    @Override
    public int getHoldability() throws SQLException {
        checkClosed();
        return ResultSet.HOLD_CURSORS_OVER_COMMIT;
    }
    
    @Override
    public Savepoint setSavepoint() throws SQLException {
        throw new SQLFeatureNotSupportedException("Savepoints not supported");
    }
    
    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        throw new SQLFeatureNotSupportedException("Savepoints not supported");
    }
    
    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        throw new SQLFeatureNotSupportedException("Savepoints not supported");
    }
    
    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        throw new SQLFeatureNotSupportedException("Savepoints not supported");
    }
    
    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        return createStatement();
    }
    
 
//    @Override
//    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
//        return prepareStatement(sql);
//    }
    
    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        throw new SQLFeatureNotSupportedException("CallableStatement not supported");
    }
    

    
    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        return prepareStatement(sql);
    }
    
    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        return prepareStatement(sql);
    }
    
    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        return prepareStatement(sql);
    }
    
    @Override
    public Clob createClob() throws SQLException {
        return new DBFClob("");
    }
    
    @Override
    public Blob createBlob() throws SQLException {
        return new DBFBlob(new byte[0]);
    }
    
    @Override
    public NClob createNClob() throws SQLException {
        return new DBFNClob("");
    }
    
    @Override
    public SQLXML createSQLXML() throws SQLException {
        throw new SQLFeatureNotSupportedException("SQLXML not supported");
    }
    
    @Override
    public boolean isValid(int timeout) throws SQLException {
        return !closed;
    }
    
    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        clientInfo.setProperty(name, value);
    }
    
    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        clientInfo.putAll(properties);
    }
    
    @Override
    public String getClientInfo(String name) throws SQLException {
        return clientInfo.getProperty(name);
    }
    
    @Override
    public Properties getClientInfo() throws SQLException {
        return clientInfo;
    }
    
    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        throw new SQLFeatureNotSupportedException("Array not supported");
    }
    
    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        throw new SQLFeatureNotSupportedException("Struct not supported");
    }
    
    @Override
    public void setSchema(String schema) throws SQLException {
        // Ignore
    }
    
    @Override
    public String getSchema() throws SQLException {
        return null;
    }
    
    @Override
    public void abort(Executor executor) throws SQLException {
        closed = true;
    }
    
    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        // Ignore
    }
    
    @Override
    public int getNetworkTimeout() throws SQLException {
        return 0;
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
    
    private void checkClosed() throws SQLException {
        if (closed) {
            throw new SQLException("Connection is closed");
        }
    }
    
    @Override
    protected void finalize() throws Throwable {
        close();
        super.finalize();
    }
 // In DBFConnection.java

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        checkClosed();
        // Use the enhanced prepared statement
        return new DBFPreparedStatement(this, sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        return prepareStatement(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        return prepareStatement(sql);
    }
}