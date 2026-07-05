package com.dbf.jdbc;

import java.io.File;
import java.sql.*;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * JDBC Driver for DBF (dBase/FoxPro) files
 * 
 * URL format: jdbc:dbf:/path/to/directory
 * Example: jdbc:dbf:/home/user/data
 */
public class DBFDriver implements Driver {
    
    static {
        try {
            DriverManager.registerDriver(new DBFDriver());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to register DBFDriver", e);
        }
    }
    
    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        
        // Extract path from URL
        String path = url.substring("jdbc:dbf:".length());
        if (path.startsWith("/") && File.separatorChar == '\\') {
            path = path.substring(1);
        }
        
        // Validate path exists
        File dir = new File(path);
        if (!dir.exists() || !dir.isDirectory()) {
            throw new SQLException("Invalid DBF directory: " + path);
        }
        
        // Get charset from properties or default to UTF-8
        String charset = info.getProperty("charset", "UTF-8");
        
        return new DBFConnection(path, charset);
    }
    
    @Override
    public boolean acceptsURL(String url) throws SQLException {
        return url != null && url.startsWith("jdbc:dbf:");
    }
    
    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
        return new DriverPropertyInfo[] {
            new DriverPropertyInfo("charset", "UTF-8")
        };
    }
    
    @Override
    public int getMajorVersion() {
        return 1;
    }
    
    @Override
    public int getMinorVersion() {
        return 0;
    }
    
    @Override
    public boolean jdbcCompliant() {
        return false;
    }
    
    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("getParentLogger not supported");
    }
}