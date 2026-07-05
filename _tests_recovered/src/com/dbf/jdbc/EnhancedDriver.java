// EnhancedDriver.java
package com.dbf.jdbc;

import com.dbf.jdbc.pool.ConnectionPool;

import java.io.File;
import java.sql.*;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class EnhancedDriver implements Driver {
    private static final Map<String, ConnectionPool> pools = new ConcurrentHashMap<>();
    
    static {
        try {
            DriverManager.registerDriver(new EnhancedDriver());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to register EnhancedDriver", e);
        }
    }
    
    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        
        // Check if connection pool is requested
        if (info.getProperty("pool") != null && "true".equals(info.getProperty("pool"))) {
            int minPoolSize = Integer.parseInt(info.getProperty("minPoolSize", "2"));
            int maxPoolSize = Integer.parseInt(info.getProperty("maxPoolSize", "10"));
            int connectionTimeout = Integer.parseInt(info.getProperty("connectionTimeout", "30000"));
            
            ConnectionPool pool = pools.computeIfAbsent(url, k -> 
                new ConnectionPool(url, info, minPoolSize, maxPoolSize, connectionTimeout)
            );
            return pool.getConnection();
        }
        
        // Regular connection
        return new DBFConnection(extractPath(url), info.getProperty("charset", "UTF-8"));
    }
    
    @Override
    public boolean acceptsURL(String url) throws SQLException {
        return url != null && url.startsWith("jdbc:dbf:");
    }
    
    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return new DriverPropertyInfo[] {
            new DriverPropertyInfo("charset", "UTF-8"),
            new DriverPropertyInfo("pool", "false"),
            new DriverPropertyInfo("minPoolSize", "2"),
            new DriverPropertyInfo("maxPoolSize", "10"),
            new DriverPropertyInfo("connectionTimeout", "30000")
        };
    }
    
    @Override
    public int getMajorVersion() {
        return 1;
    }
    
    @Override
    public int getMinorVersion() {
        return 1;
    }
    
    @Override
    public boolean jdbcCompliant() {
        return false;
    }
    
    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("getParentLogger not supported");
    }
    
    private static String extractPath(String url) {
        String path = url.substring("jdbc:dbf:".length());
        if (path.startsWith("/") && File.separatorChar == '\\') {
            path = path.substring(1);
        }
        return path;
    }
    
    public static void closeAllPools() throws SQLException {
        SQLException lastException = null;
        for (ConnectionPool pool : pools.values()) {
            try {
                pool.close();
            } catch (SQLException e) {
                lastException = e;
            }
        }
        pools.clear();
        if (lastException != null) {
            throw lastException;
        }
    }
}