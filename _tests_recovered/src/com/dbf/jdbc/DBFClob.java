package com.dbf.jdbc;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.sql.Clob;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

public class DBFClob implements Clob {
    private String data;
    private boolean closed = false;
    
    public DBFClob(String data) {
        this.data = data != null ? data : "";
    }
    
    @Override
    public long length() throws SQLException {
        checkClosed();
        return data.length();
    }
    
    @Override
    public String getSubString(long pos, int length) throws SQLException {
        checkClosed();
        if (pos < 1 || pos > data.length()) {
            throw new SQLException("Position out of range: " + pos);
        }
        int start = (int) pos - 1;
        int end = Math.min(start + length, data.length());
        return data.substring(start, end);
    }
    
    @Override
    public Reader getCharacterStream() throws SQLException {
        checkClosed();
        return new StringReader(data);
    }
    
    @Override
    public InputStream getAsciiStream() throws SQLException {
        checkClosed();
        return new java.io.ByteArrayInputStream(data.getBytes());
    }
    
    @Override
    public long position(String searchstr, long start) throws SQLException {
        checkClosed();
        if (start < 1 || start > data.length()) {
            return -1;
        }
        int idx = data.indexOf(searchstr, (int) start - 1);
        return idx >= 0 ? idx + 1 : -1;
    }
    
    @Override
    public long position(Clob searchstr, long start) throws SQLException {
        return position(searchstr.getSubString(1, (int) searchstr.length()), start);
    }
    
    @Override
    public int setString(long pos, String str) throws SQLException {
        throw new SQLFeatureNotSupportedException("setString not supported");
    }
    
    @Override
    public int setString(long pos, String str, int offset, int len) throws SQLException {
        throw new SQLFeatureNotSupportedException("setString not supported");
    }
    
    @Override
    public OutputStream setAsciiStream(long pos) throws SQLException {
        throw new SQLFeatureNotSupportedException("setAsciiStream not supported");
    }
    
    @Override
    public Writer setCharacterStream(long pos) throws SQLException {
        throw new SQLFeatureNotSupportedException("setCharacterStream not supported");
    }
    
    @Override
    public void truncate(long len) throws SQLException {
        checkClosed();
        if (len < 0 || len > data.length()) {
            throw new SQLException("Invalid length: " + len);
        }
        data = data.substring(0, (int) len);
    }
    
    @Override
    public void free() throws SQLException {
        closed = true;
        data = null;
    }
    
    @Override
    public Reader getCharacterStream(long pos, long length) throws SQLException {
        checkClosed();
        if (pos < 1 || pos > data.length()) {
            throw new SQLException("Position out of range: " + pos);
        }
        int start = (int) pos - 1;
        int end = (int) Math.min(start + length, data.length());
        return new StringReader(data.substring(start, end));
    }
    
    private void checkClosed() throws SQLException {
        if (closed) {
            throw new SQLException("Clob has been freed");
        }
    }
}

// NClob implementation extends Clob
class DBFNClob extends DBFClob implements java.sql.NClob {
    public DBFNClob(String data) {
        super(data);
    }
}