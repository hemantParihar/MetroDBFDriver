// ConnectionPool.java
package com.dbf.jdbc.pool;

import com.dbf.jdbc.DBFConnection;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class ConnectionPool {
    private final String url;
    private final Properties properties;
    private final int maxPoolSize;
    private final int minPoolSize;
    private final int connectionTimeoutMs;
    
    private final Queue<PooledConnection> availableConnections = new ConcurrentLinkedQueue<>();
    private final Set<PooledConnection> activeConnections = Collections.synchronizedSet(new HashSet<>());
    private final AtomicInteger totalConnections = new AtomicInteger(0);
    
    public ConnectionPool(String url, Properties properties, int minPoolSize, int maxPoolSize, int connectionTimeoutMs) {
        this.url = url;
        this.properties = properties;
        this.minPoolSize = minPoolSize;
        this.maxPoolSize = maxPoolSize;
        this.connectionTimeoutMs = connectionTimeoutMs;
        
        // Initialize min pool size
        for (int i = 0; i < minPoolSize; i++) {
            createConnection();
        }
    }
    
    private void createConnection() {
        try {
            PooledConnection conn = new PooledConnection(url, properties);
            availableConnections.offer(conn);
            totalConnections.incrementAndGet();
        } catch (SQLException e) {
            // Log error
        }
    }
    
    public Connection getConnection() throws SQLException {
        long startTime = System.currentTimeMillis();
        
        while (true) {
            PooledConnection conn = availableConnections.poll();
            if (conn != null && conn.isValid()) {
                activeConnections.add(conn);
                return conn;
            }
            
            // Create new connection if under max pool size
            if (totalConnections.get() < maxPoolSize) {
                createConnection();
                continue;
            }
            
            // Wait for connection
            if (System.currentTimeMillis() - startTime > connectionTimeoutMs) {
                throw new SQLException("Connection pool timeout: no available connections");
            }
            
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("Interrupted while waiting for connection", e);
            }
        }
    }
    
    public void returnConnection(PooledConnection connection) {
        if (activeConnections.remove(connection)) {
            if (connection.isValid()) {
                availableConnections.offer(connection);
            } else {
                totalConnections.decrementAndGet();
            }
        }
    }
    
    public void close() throws SQLException {
        SQLException lastException = null;
        
        for (PooledConnection conn : availableConnections) {
            try {
                conn.realClose();
            } catch (SQLException e) {
                lastException = e;
            }
        }
        
        for (PooledConnection conn : activeConnections) {
            try {
                conn.realClose();
            } catch (SQLException e) {
                lastException = e;
            }
        }
        
        availableConnections.clear();
        activeConnections.clear();
        
        if (lastException != null) {
            throw lastException;
        }
    }
    
    public int getActiveCount() {
        return activeConnections.size();
    }
    
    public int getIdleCount() {
        return availableConnections.size();
    }
    
    public int getTotalCount() {
        return totalConnections.get();
    }
    
    private class PooledConnection extends DBFConnection {
        private boolean closed = false;
        
        PooledConnection(String url, Properties properties) throws SQLException {
            super(extractPath(url), properties.getProperty("charset", "UTF-8"));
        }
        
        @Override
        public void close() throws SQLException {
            if (!closed) {
                closed = true;
                returnConnection(this);
            }
        }
        
        void realClose() throws SQLException {
            super.close();
        }
        
        boolean isValid() {
            try {
                return !isClosed();
            } catch (SQLException e) {
                return false;
            }
        }
    }
    
    private static String extractPath(String url) {
        return url.substring("jdbc:dbf:".length());
    }
}