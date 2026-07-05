package com.dbf.jdbc;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Blob;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Arrays;

public class DBFBlob implements Blob {
    private byte[] data;
    private boolean closed = false;
    
    public DBFBlob(byte[] data) {
        this.data = data != null ? data : new byte[0];
    }
    
    @Override
    public long length() throws SQLException {
        checkClosed();
        return data.length;
    }
    
    @Override
    public byte[] getBytes(long pos, int length) throws SQLException {
        checkClosed();
        if (pos < 1 || pos > data.length) {
            throw new SQLException("Position out of range: " + pos);
        }
        int start = (int) pos - 1;
        int len = Math.min(length, data.length - start);
        byte[] result = new byte[len];
        System.arraycopy(data, start, result, 0, len);
        return result;
    }
    
    @Override
    public InputStream getBinaryStream() throws SQLException {
        checkClosed();
        return new ByteArrayInputStream(data);
    }
    
    @Override
    public long position(byte[] pattern, long start) throws SQLException {
        checkClosed();
        if (start < 1 || start > data.length) {
            return -1;
        }
        int startIdx = (int) start - 1;
        for (int i = startIdx; i <= data.length - pattern.length; i++) {
            boolean found = true;
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    found = false;
                    break;
                }
            }
            if (found) {
                return i + 1;
            }
        }
        return -1;
    }
    
    @Override
    public long position(Blob pattern, long start) throws SQLException {
        return position(pattern.getBytes(1, (int) pattern.length()), start);
    }
    
    @Override
    public int setBytes(long pos, byte[] bytes) throws SQLException {
        throw new SQLFeatureNotSupportedException("setBytes not supported");
    }
    
    @Override
    public int setBytes(long pos, byte[] bytes, int offset, int len) throws SQLException {
        throw new SQLFeatureNotSupportedException("setBytes not supported");
    }
    
    @Override
    public OutputStream setBinaryStream(long pos) throws SQLException {
        throw new SQLFeatureNotSupportedException("setBinaryStream not supported");
    }
    
    @Override
    public void truncate(long len) throws SQLException {
        checkClosed();
        if (len < 0 || len > data.length) {
            throw new SQLException("Invalid length: " + len);
        }
        data = Arrays.copyOf(data, (int) len);
    }
    
    @Override
    public void free() throws SQLException {
        closed = true;
        data = null;
    }
    
    @Override
    public InputStream getBinaryStream(long pos, long length) throws SQLException {
        checkClosed();
        if (pos < 1 || pos > data.length) {
            throw new SQLException("Position out of range: " + pos);
        }
        int start = (int) pos - 1;
        int len = (int) Math.min(length, data.length - start);
        return new ByteArrayInputStream(data, start, len);
    }
    
    private void checkClosed() throws SQLException {
        if (closed) {
            throw new SQLException("Blob has been freed");
        }
    }
}