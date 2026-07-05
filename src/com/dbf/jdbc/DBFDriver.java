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
        
        // Extract path from URL, splitting off any ;key=value options.
        String raw = url.substring("jdbc:dbf:".length());
        Properties merged = new Properties();
        if (info != null) {
            merged.putAll(info);
        }
        String path = raw;
        int opt = raw.indexOf(';');
        if (opt >= 0) {
            path = raw.substring(0, opt);
            for (String pair : raw.substring(opt + 1).split(";")) {
                if (pair.isEmpty()) continue;
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    merged.setProperty(pair.substring(0, eq).trim(),
                        pair.substring(eq + 1).trim());
                }
            }
        }
        // URL forms like "jdbc:dbf:/D:/data" carry a leading slash before the
        // drive letter -- strip it on Windows. But leave UNC network paths
        // ("//server/share/dir" or "\\server\share\dir") intact: multi-user
        // deployments point the driver at a shared folder over SMB.
        if (path.startsWith("/") && !path.startsWith("//")
                && File.separatorChar == '\\'
                && path.length() > 2 && path.charAt(2) == ':') {
            path = path.substring(1);
        }

        // Validate path exists
        File dir = new File(path);
        if (!dir.exists() || !dir.isDirectory()) {
            throw new SQLException("Invalid DBF directory: " + path);
        }

        // Get charset from properties or default to UTF-8
        String charset = merged.getProperty("charset", "UTF-8");

        DBFConnection conn = new DBFConnection(path, charset);

        // Index flags, both OFF by default (opt-in). "index=on" is a shortcut
        // to enable index reads; "index=off" forces everything off; "indexRead"
        // and "indexWrite" override individually.
        String master = merged.getProperty("index", "");
        boolean masterOff = "off".equalsIgnoreCase(master);
        boolean masterOn = "on".equalsIgnoreCase(master);
        conn.setIndexRead(!masterOff
            && parseBool(merged.getProperty("indexRead"), masterOn));
        conn.setIndexWrite(!masterOff
            && parseBool(merged.getProperty("indexWrite"), false));

        // Write-lock scheme. Default "clipper" (DBFNTX byte-range lock on the
        // .dbf) so writes coordinate with a running Clipper app (e.g. METRO).
        // "clipper2" = ntxlock2 large-file scheme; "sidecar"/"none" = the private
        // <table>.dbf.lck file (no coordination with native apps).
        conn.setLockScheme(com.dbf.jdbc.tx.LockScheme.parse(
            merged.getProperty("lockScheme", merged.getProperty("lock"))));
        return conn;
    }

    private static boolean parseBool(String value, boolean defaultValue) {
        if (value == null) return defaultValue;
        String v = value.trim();
        return v.equalsIgnoreCase("true") || v.equalsIgnoreCase("on")
            || v.equalsIgnoreCase("yes") || v.equals("1");
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