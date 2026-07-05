package com.dbf.jdbc;

import java.io.IOException;
import java.nio.charset.Charset;
import java.sql.*;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

import com.dbf.jdbc.tx.Transaction;
import com.dbf.jdbc.tx.WriteLock;

/**
 * JDBC connection to a folder of DBF tables.
 *
 * <p>Thread-safety: this connection is safe to share across threads. Its
 * mutable state (closed/autoCommit/isolation/index flags) is held in
 * {@code volatile} fields, the warning chain is guarded by a lock, and
 * {@code clientInfo} uses a synchronized {@link Properties}. Each
 * {@link #createStatement()} / {@link #prepareStatement(String)} returns an
 * independent statement that opens its own file handles, so concurrent
 * <em>reads</em> across statements are fully parallel. Concurrent
 * <em>writes</em> to the <em>same table</em> should still be serialized by the
 * caller (DBF/NTX files have no record locking); writes to different tables are
 * independent.
 */
public class DBFConnection implements Connection {
    private final String path;
    private final String charset;
    // Index feature flags (see DBFDriver for the URL/property names).
    // Both default OFF and are strictly opt-in: index seeking (read) only
    // kicks in with indexRead=on, and .NTX maintenance (write) only with
    // indexWrite=on. With the defaults, every query is a plain table scan and
    // no index file is ever touched.
    private volatile boolean indexRead = false;
    private volatile boolean indexWrite = false;
    // How table write locks are taken. Default CLIPPER: an OS byte-range lock on
    // the .dbf at the offsets Clipper's DBFNTX RDD uses, so writes coordinate
    // with a running Clipper application (e.g. METRO) on the same files.
    private volatile com.dbf.jdbc.tx.LockScheme lockScheme = com.dbf.jdbc.tx.LockScheme.CLIPPER;
    private volatile boolean closed = false;
    private volatile boolean autoCommit = true;
    private volatile int transactionIsolation = TRANSACTION_NONE;
    private final Object warningLock = new Object();
    private SQLWarning warnings = null;
    // Properties is Hashtable-backed, so individual reads/writes are synchronized.
    private final Properties clientInfo = new Properties();
    // Manual-transaction state (only used when autoCommit is off). Guarded by txnLock.
    private final Object txnLock = new Object();
    private Transaction currentTxn = null;

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

    public boolean isIndexReadEnabled() {
        return indexRead;
    }

    public boolean isIndexWriteEnabled() {
        return indexWrite;
    }

    public void setIndexRead(boolean indexRead) {
        this.indexRead = indexRead;
    }

    public void setIndexWrite(boolean indexWrite) {
        this.indexWrite = indexWrite;
    }

    public com.dbf.jdbc.tx.LockScheme getLockScheme() {
        return lockScheme;
    }

    public void setLockScheme(com.dbf.jdbc.tx.LockScheme lockScheme) {
        this.lockScheme = lockScheme;
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
        // Turning autoCommit back on commits any pending manual transaction.
        if (autoCommit && !this.autoCommit) {
            synchronized (txnLock) {
                if (currentTxn != null) {
                    currentTxn.commit();
                    currentTxn = null;
                }
            }
        }
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
        synchronized (txnLock) {
            if (currentTxn != null) {
                currentTxn.commit();
                currentTxn = null;
            }
        }
    }

    @Override
    public void rollback() throws SQLException {
        checkClosed();
        if (autoCommit) {
            throw new SQLException("Cannot rollback when autoCommit is enabled");
        }
        synchronized (txnLock) {
            if (currentTxn != null) {
                currentTxn.rollback();
                currentTxn = null;
            }
        }
    }

    @Override
    public void close() throws SQLException {
        // Closing with an open transaction rolls it back (and frees its locks).
        synchronized (txnLock) {
            if (currentTxn != null) {
                currentTxn.rollback();
                currentTxn = null;
            }
        }
        closed = true;
    }

    // ==================== write coordination (locking + transactions) ====================

    /**
     * Acquires the write lock for a table before a DML write. In autoCommit mode
     * an exclusive {@link WriteLock} is returned and must be released via
     * {@link #endWrite}. Inside a manual transaction the lock is taken once and
     * held by the transaction until commit/rollback, and null is returned.
     */
    public WriteLock beginWrite(String tableName) throws SQLException {
        checkClosed();
        String table = normalizeTableName(tableName);
        if (autoCommit) {
            try {
                return lockScheme.acquire(path + "/" + table + ".dbf");
            } catch (IOException e) {
                throw new SQLException("Could not lock table " + table + ": " + e.getMessage(), e);
            }
        }
        synchronized (txnLock) {
            if (currentTxn == null) {
                currentTxn = new Transaction(path, resolveCharset(), indexWrite, lockScheme);
            }
            try {
                currentTxn.beginTable(table);
            } catch (IOException e) {
                throw new SQLException("Could not begin table " + table + ": " + e.getMessage(), e);
            }
        }
        return null;
    }

    /**
     * Normalizes a table name for lock/transaction keying so the lock file is
     * always {@code <TABLE>.dbf.lck} and matches the data file regardless of how
     * the caller spelled the name. Strips surrounding quote characters
     * (backtick, brackets, double-quote) and an optional trailing {@code .dbf}
     * extension. Without this, e.g. {@code `MASTER`} produced a stray
     * {@code `MASTER`.dbf.lck} that did not guard the real {@code MASTER.dbf}.
     */
    static String normalizeTableName(String tableName) {
        if (tableName == null) {
            return null;
        }
        String t = tableName.trim();
        if (t.length() >= 2) {
            char first = t.charAt(0);
            char last = t.charAt(t.length() - 1);
            if ((first == '`' && last == '`')
                || (first == '[' && last == ']')
                || (first == '"' && last == '"')) {
                t = t.substring(1, t.length() - 1).trim();
            }
        }
        if (t.length() > 4 && t.regionMatches(true, t.length() - 4, ".dbf", 0, 4)) {
            t = t.substring(0, t.length() - 4);
        }
        return t;
    }

    /** Releases a per-statement write lock (no-op inside a transaction). */
    public void endWrite(WriteLock handle) {
        if (handle != null) {
            handle.close();
        }
    }

    /** Saves a record's original bytes for rollback (no-op outside a transaction). */
    public void captureOriginal(String tableName, int recno, byte[] originalBytes) {
        synchronized (txnLock) {
            if (currentTxn != null) {
                currentTxn.captureOriginal(normalizeTableName(tableName), recno, originalBytes);
            }
        }
    }

    /** True while a manual transaction is in progress (autoCommit off and a write happened). */
    public boolean inTransaction() {
        synchronized (txnLock) {
            return currentTxn != null;
        }
    }

    private Charset resolveCharset() {
        try {
            return (charset == null || charset.isEmpty())
                ? Charset.forName("UTF-8") : Charset.forName(charset);
        } catch (Exception e) {
            return Charset.forName("UTF-8");
        }
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
        synchronized (warningLock) {
            return warnings;
        }
    }

    @Override
    public void clearWarnings() throws SQLException {
        checkClosed();
        synchronized (warningLock) {
            warnings = null;
        }
    }

    /** Appends a warning to the chain (thread-safe). */
    void addWarning(SQLWarning warning) {
        if (warning == null) {
            return;
        }
        synchronized (warningLock) {
            if (warnings == null) {
                warnings = warning;
            } else {
                warnings.setNextWarning(warning);
            }
        }
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