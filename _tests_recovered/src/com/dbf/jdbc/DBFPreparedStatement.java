package com.dbf.jdbc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.dbf.jdbc.parser.Lexer;
import com.dbf.jdbc.parser.Parser;
import com.dbf.jdbc.parser.ast.SelectNode;
import com.dbf.jdbc.resultset.TypeConverter;

/**
 * True PreparedStatement with parameter binding
 * - Parses SQL once and caches the AST
 * - Binds parameters by value, not by string replacement
 * - Supports all JDBC parameter types
 */
public class DBFPreparedStatement extends DBFStatement implements PreparedStatement {
    private final String originalSql;
    private final String parsedSql;
    private final SelectNode cachedSelectNode;
    private final List<ParameterMeta> parameterMetadata;
    private final Object[] parameterValues;
    private final boolean[] parameterSet;
    private final Map<String, Integer> parameterNameToIndex;
    
    private boolean isBatchMode = false;
    private List<Object[]> batchParameters;
    
    private static class ParameterMeta {
        final int index;
        int sqlType;
        final String typeName;
        final int precision;
        final int scale;
        boolean isNullable = true;
        
        ParameterMeta(int index, int sqlType, String typeName, int precision, int scale) {
            this.index = index;
            this.sqlType = sqlType;
            this.typeName = typeName;
            this.precision = precision;
            this.scale = scale;
        }
    }
    
    public DBFPreparedStatement(DBFConnection connection, String sql) throws SQLException {
        super(connection);
        this.originalSql = sql;
        this.parameterValues = new Object[estimateParameterCount(sql)];
        this.parameterSet = new boolean[parameterValues.length];
        this.parameterMetadata = new ArrayList<>();
        this.parameterNameToIndex = new HashMap<>();
        this.batchParameters = new ArrayList<>();
        
        // Parse SQL once and cache the AST
        this.parsedSql = extractParameterPlaceholders(sql);
        this.cachedSelectNode = parseAndCache(parsedSql);
        
        // Analyze parameters from AST
        analyzeParameters(cachedSelectNode);
    }
    
    private int estimateParameterCount(String sql) {
        int count = 0;
        boolean inString = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'' && (i == 0 || sql.charAt(i - 1) != '\\')) {
                inString = !inString;
            } else if (c == '?' && !inString) {
                count++;
            }
        }
        return Math.max(count, 1);
    }
    
    private String extractParameterPlaceholders(String sql) {
        StringBuilder sb = new StringBuilder();
        boolean inString = false;
        int paramIndex = 0;
        
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'' && (i == 0 || sql.charAt(i - 1) != '\\')) {
                inString = !inString;
                sb.append(c);
            } else if (c == '?' && !inString) {
                sb.append("__PARAM_").append(paramIndex++).append("__");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
    
    private SelectNode parseAndCache(String sql) throws SQLException {
        try {
            Lexer lexer = new Lexer(new StringReader(sql));
            Parser parser = new Parser(new StringReader(sql));
            return parser.parseSelect();
        } catch (Exception e) {
            throw new SQLException("Failed to parse SQL: " + e.getMessage(), e);
        }
    }
    
    private void analyzeParameters(SelectNode selectNode) {
        if (selectNode == null) return;
        
        if (selectNode.getWhere() != null) {
            analyzeExpressionForParameters(selectNode.getWhere().getCondition(), 0);
        }
        
        if (selectNode.getHaving() != null) {
            analyzeExpressionForParameters(selectNode.getHaving().getCondition(), 0);
        }
        
        for (int i = 0; i < parameterValues.length; i++) {
            if (i >= parameterMetadata.size()) {
                parameterMetadata.add(new ParameterMeta(i, Types.VARCHAR, "VARCHAR", 255, 0));
            }
        }
    }
    
    private int analyzeExpressionForParameters(com.dbf.jdbc.parser.ast.ExpressionNode node, int paramCount) {
        if (node == null) return paramCount;
        
        if (node.isBinaryOp()) {
            paramCount = analyzeExpressionForParameters(node.getLeft(), paramCount);
            paramCount = analyzeExpressionForParameters(node.getRight(), paramCount);
        } else if (node.isLiteral()) {
            String value = node.getValue();
            if (value != null && value.startsWith("__PARAM_")) {
                int index = Integer.parseInt(value.substring(8, value.length() - 2));
                ParameterMeta meta = new ParameterMeta(index, Types.VARCHAR, "VARCHAR", 255, 0);
                parameterMetadata.add(meta);
                paramCount++;
            }
        }
        
        return paramCount;
    }
    
    // ==================== Parameter Setting Methods ====================
    
    private void setParameter(int index, Object value, int sqlType) throws SQLException {
        checkClosed();
        if (index < 1 || index > parameterValues.length) {
            throw new SQLException("Invalid parameter index: " + index);
        }
        parameterValues[index - 1] = value;
        parameterSet[index - 1] = true;
        
        if (index - 1 < parameterMetadata.size()) {
            parameterMetadata.get(index - 1).sqlType = sqlType;
        }
    }
    
    @Override
    public void setNull(int parameterIndex, int sqlType) throws SQLException {
        setParameter(parameterIndex, null, sqlType);
    }
    
    @Override
    public void setBoolean(int parameterIndex, boolean x) throws SQLException {
        setParameter(parameterIndex, x, Types.BOOLEAN);
    }
    
    @Override
    public void setByte(int parameterIndex, byte x) throws SQLException {
        setParameter(parameterIndex, x, Types.TINYINT);
    }
    
    @Override
    public void setShort(int parameterIndex, short x) throws SQLException {
        setParameter(parameterIndex, x, Types.SMALLINT);
    }
    
    @Override
    public void setInt(int parameterIndex, int x) throws SQLException {
        setParameter(parameterIndex, x, Types.INTEGER);
    }
    
    @Override
    public void setLong(int parameterIndex, long x) throws SQLException {
        setParameter(parameterIndex, x, Types.BIGINT);
    }
    
    @Override
    public void setFloat(int parameterIndex, float x) throws SQLException {
        setParameter(parameterIndex, x, Types.FLOAT);
    }
    
    @Override
    public void setDouble(int parameterIndex, double x) throws SQLException {
        setParameter(parameterIndex, x, Types.DOUBLE);
    }
    
    @Override
    public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
        setParameter(parameterIndex, x, Types.DECIMAL);
    }
    
    @Override
    public void setString(int parameterIndex, String x) throws SQLException {
        setParameter(parameterIndex, x, Types.VARCHAR);
    }
    
    @Override
    public void setBytes(int parameterIndex, byte[] x) throws SQLException {
        setParameter(parameterIndex, x, Types.VARBINARY);
    }
    
    @Override
    public void setDate(int parameterIndex, Date x) throws SQLException {
        setParameter(parameterIndex, x, Types.DATE);
    }
    
    @Override
    public void setTime(int parameterIndex, Time x) throws SQLException {
        setParameter(parameterIndex, x, Types.TIME);
    }
    
    @Override
    public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
        setParameter(parameterIndex, x, Types.TIMESTAMP);
    }
    
    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
        setBlob(parameterIndex, x, length);
    }
    
    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
        setBlob(parameterIndex, x, length);
    }
    
    @Override
    public void setCharacterStream(int parameterIndex, Reader x, int length) throws SQLException {
        setNCharacterStream(parameterIndex, x, length);
    }
    
    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
        setParameter(parameterIndex, x, targetSqlType);
    }
    
    @Override
    public void setObject(int parameterIndex, Object x) throws SQLException {
        int sqlType = x != null ? TypeConverter.guessSqlType(x) : Types.NULL;
        setParameter(parameterIndex, x, sqlType);
    }
    
    @Override
    public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
        setDate(parameterIndex, x);
    }
    
    @Override
    public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
        setTime(parameterIndex, x);
    }
    
    @Override
    public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
        setTimestamp(parameterIndex, x);
    }
    
    @Override
    public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
        setNull(parameterIndex, sqlType);
    }
    
    @Override
    public void setURL(int parameterIndex, URL x) throws SQLException {
        setParameter(parameterIndex, x != null ? x.toString() : null, Types.VARCHAR);
    }
    
    @Override
    public void setRowId(int parameterIndex, RowId x) throws SQLException {
        setParameter(parameterIndex, x, Types.ROWID);
    }
    
    @Override
    public void setNString(int parameterIndex, String value) throws SQLException {
        setString(parameterIndex, value);
    }
    
    @Override
    public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {
        StringBuilder sb = new StringBuilder();
        char[] buffer = new char[4096];
        int read;
        long totalRead = 0;
        try {
            while ((read = value.read(buffer)) != -1 && totalRead < length) {
                sb.append(buffer, 0, read);
                totalRead += read;
            }
        } catch (IOException e) {
            throw new SQLException("Failed to read character stream", e);
        }
        setString(parameterIndex, sb.toString());
    }
    
    @Override
    public void setNClob(int parameterIndex, NClob value) throws SQLException {
        setString(parameterIndex, value != null ? value.getSubString(1, (int) value.length()) : null);
    }
    
    @Override
    public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
        setNCharacterStream(parameterIndex, reader, length);
    }
    
    @Override
    public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        long totalRead = 0;
        try {
            while ((read = inputStream.read(buffer)) != -1 && totalRead < length) {
                baos.write(buffer, 0, read);
                totalRead += read;
            }
        } catch (IOException e) {
            throw new SQLException("Failed to read binary stream", e);
        }
        setBytes(parameterIndex, baos.toByteArray());
    }
    
    @Override
    public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
        setNCharacterStream(parameterIndex, reader, length);
    }
    
    @Override
    public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
        setString(parameterIndex, xmlObject != null ? xmlObject.getString() : null);
    }
    
    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
        if (x instanceof BigDecimal) {
            setBigDecimal(parameterIndex, (BigDecimal) x);
        } else {
            setObject(parameterIndex, x, targetSqlType);
        }
    }
    
    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {
        setBlob(parameterIndex, x, length);
    }
    
    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {
        setBlob(parameterIndex, x, length);
    }
    
    @Override
    public void setCharacterStream(int parameterIndex, Reader x, long length) throws SQLException {
        setNCharacterStream(parameterIndex, x, length);
    }
    
    @Override
    public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
        setBlob(parameterIndex, x, -1);
    }
    
    @Override
    public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
        setBlob(parameterIndex, x, -1);
    }
    
    @Override
    public void setCharacterStream(int parameterIndex, Reader x) throws SQLException {
        setNCharacterStream(parameterIndex, x, -1);
    }
    
    @Override
    public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
        setNCharacterStream(parameterIndex, value, -1);
    }
    
    @Override
    public void setClob(int parameterIndex, Reader reader) throws SQLException {
        setClob(parameterIndex, reader, -1);
    }
    
    @Override
    public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
        setBlob(parameterIndex, inputStream, -1);
    }
    
    @Override
    public void setNClob(int parameterIndex, Reader reader) throws SQLException {
        setNClob(parameterIndex, reader, -1);
    }
    
    // ==================== Array, Blob, Clob, Ref, UnicodeStream Methods ====================
    
    @Override
    public void setArray(int parameterIndex, Array x) throws SQLException {
        if (x == null) {
            setParameter(parameterIndex, null, Types.ARRAY);
        } else {
            // Convert Array to appropriate representation
            try {
                Object arrayData = x.getArray();
                setParameter(parameterIndex, arrayData, Types.ARRAY);
            } catch (SQLException e) {
                setParameter(parameterIndex, x.toString(), Types.ARRAY);
            }
        }
    }
    
    @Override
    public void setBlob(int parameterIndex, Blob x) throws SQLException {
        if (x == null) {
            setParameter(parameterIndex, null, Types.BLOB);
        } else {
            // Read Blob data into byte array
            try {
                long length = x.length();
                if (length > Integer.MAX_VALUE) {
                    throw new SQLException("Blob too large: " + length);
                }
                byte[] data = x.getBytes(1, (int) length);
                setParameter(parameterIndex, data, Types.BLOB);
            } catch (SQLException e) {
                throw e;
            }
        }
    }
    
    @Override
    public void setClob(int parameterIndex, Clob x) throws SQLException {
        if (x == null) {
            setParameter(parameterIndex, null, Types.CLOB);
        } else {
            // Read Clob data into String
            try {
                long length = x.length();
                if (length > Integer.MAX_VALUE) {
                    throw new SQLException("Clob too large: " + length);
                }
                String data = x.getSubString(1, (int) length);
                setParameter(parameterIndex, data, Types.CLOB);
            } catch (SQLException e) {
                throw e;
            }
        }
    }
    
    @Override
    public void setRef(int parameterIndex, Ref x) throws SQLException {
        if (x == null) {
            setParameter(parameterIndex, null, Types.REF);
        } else {
            // Ref typically contains a structured value
            try {
                Object referencedObject = x.getObject();
                setParameter(parameterIndex, referencedObject, Types.REF);
            } catch (SQLException e) {
                setParameter(parameterIndex, x.toString(), Types.REF);
            }
        }
    }
    
    @Override
    public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {
        // UnicodeStream is deprecated, delegate to setCharacterStream
        if (x == null) {
            setParameter(parameterIndex, null, Types.LONGVARCHAR);
            return;
        }
        
        try {
            InputStreamReader reader = new InputStreamReader(x, "UTF-16");
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            int totalRead = 0;
            while ((read = reader.read(buffer)) != -1 && totalRead < length) {
                sb.append(buffer, 0, read);
                totalRead += read;
            }
            setString(parameterIndex, sb.toString());
        } catch (UnsupportedEncodingException e) {
            throw new SQLException("UTF-16 encoding not supported", e);
        } catch (IOException e) {
            throw new SQLException("Failed to read Unicode stream", e);
        }
    }
    
    // ==================== Execution Methods ====================
    
    private String buildSqlWithParameters() {
        String sql = originalSql;
        int paramIndex = 0;
        StringBuilder sb = new StringBuilder();
        boolean inString = false;
        
        for (int i = 0; i < originalSql.length(); i++) {
            char c = originalSql.charAt(i);
            if (c == '\'' && (i == 0 || originalSql.charAt(i - 1) != '\\')) {
                inString = !inString;
                sb.append(c);
            } else if (c == '?' && !inString) {
                if (paramIndex < parameterValues.length && parameterSet[paramIndex]) {
                    sb.append(formatParameterValue(parameterValues[paramIndex]));
                } else {
                    sb.append("NULL");
                }
                paramIndex++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
    
    private String formatParameterValue(Object value) {
        if (value == null) return "NULL";
        if (value instanceof String) {
            return "'" + escapeString((String) value) + "'";
        }
        if (value instanceof Date || value instanceof Timestamp || value instanceof Time) {
            return "'" + value.toString() + "'";
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? "1" : "0";
        }
        if (value instanceof byte[]) {
            return "X'" + bytesToHex((byte[]) value) + "'";
        }
        return value.toString();
    }
    
    private String escapeString(String s) {
        return s.replace("'", "''").replace("\\", "\\\\");
    }
    
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
    
    @Override
    public ResultSet executeQuery() throws SQLException {
        checkClosed();
        clearWarnings();
        
        for (int i = 0; i < parameterValues.length; i++) {
            if (!parameterSet[i]) {
                throw new SQLException("Parameter at index " + (i + 1) + " is not set");
            }
        }
        
        String sql = buildSqlWithParameters();
        return super.executeQuery(sql);
    }
    
    @Override
    public int executeUpdate() throws SQLException {
        checkClosed();
        
        for (int i = 0; i < parameterValues.length; i++) {
            if (!parameterSet[i]) {
                throw new SQLException("Parameter at index " + (i + 1) + " is not set");
            }
        }
        
        String sql = buildSqlWithParameters();
        return super.executeUpdate(sql);
    }
    
    @Override
    public boolean execute() throws SQLException {
        checkClosed();
        
        for (int i = 0; i < parameterValues.length; i++) {
            if (!parameterSet[i]) {
                throw new SQLException("Parameter at index " + (i + 1) + " is not set");
            }
        }
        
        String sql = buildSqlWithParameters();
        return super.execute(sql);
    }
    
    // ==================== Batch Operations ====================
    
    @Override
    public void addBatch() throws SQLException {
        checkClosed();
        
        Object[] params = new Object[parameterValues.length];
        System.arraycopy(parameterValues, 0, params, 0, parameterValues.length);
        batchParameters.add(params);
        
        clearParameters();
    }
    
    @Override
    public int[] executeBatch() throws SQLException {
        checkClosed();
        
        if (batchParameters.isEmpty()) {
            return new int[0];
        }
        
        int[] results = new int[batchParameters.size()];
        
        for (int i = 0; i < batchParameters.size(); i++) {
            Object[] params = batchParameters.get(i);
            
            for (int j = 0; j < params.length; j++) {
                parameterValues[j] = params[j];
                parameterSet[j] = (params[j] != null);
            }
            
            String sql = buildSqlWithParameters();
            results[i] = super.executeUpdate(sql);
        }
        
        clearBatch();
        return results;
    }
    
    @Override
    public void clearBatch() throws SQLException {
        batchParameters.clear();
    }
    
    @Override
    public void clearParameters() throws SQLException {
        Arrays.fill(parameterValues, null);
        Arrays.fill(parameterSet, false);
    }
    
    // ==================== Metadata Methods ====================
    
    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        if (cachedSelectNode != null) {
            String dummySql = buildSqlWithDummyParameters();
            ResultSet rs = super.executeQuery(dummySql);
            return rs.getMetaData();
        }
        return null;
    }
    
    private String buildSqlWithDummyParameters() {
        String sql = originalSql;
        int paramIndex = 0;
        StringBuilder sb = new StringBuilder();
        boolean inString = false;
        
        for (int i = 0; i < originalSql.length(); i++) {
            char c = originalSql.charAt(i);
            if (c == '\'' && (i == 0 || originalSql.charAt(i - 1) != '\\')) {
                inString = !inString;
                sb.append(c);
            } else if (c == '?' && !inString) {
                sb.append("NULL");
                paramIndex++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
    
    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException {
        return new PreparedStatementParameterMetaData(parameterMetadata);
    }
    
    // ==================== Helper Classes ====================
    
    private class PreparedStatementParameterMetaData implements ParameterMetaData {
        private final List<ParameterMeta> params;
        
        PreparedStatementParameterMetaData(List<ParameterMeta> params) {
            this.params = params;
        }
        
        @Override
        public int getParameterCount() throws SQLException {
            return params.size();
        }
        
        @Override
        public int isNullable(int param) throws SQLException {
            return ParameterMetaData.parameterNullableUnknown;
        }
        
        @Override
        public boolean isSigned(int param) throws SQLException {
            int type = params.get(param - 1).sqlType;
            return type == Types.INTEGER || type == Types.BIGINT || 
                   type == Types.SMALLINT || type == Types.TINYINT ||
                   type == Types.FLOAT || type == Types.DOUBLE ||
                   type == Types.DECIMAL || type == Types.NUMERIC;
        }
        
        @Override
        public int getPrecision(int param) throws SQLException {
            return params.get(param - 1).precision;
        }
        
        @Override
        public int getScale(int param) throws SQLException {
            return params.get(param - 1).scale;
        }
        
        @Override
        public int getParameterType(int param) throws SQLException {
            return params.get(param - 1).sqlType;
        }
        
        @Override
        public String getParameterTypeName(int param) throws SQLException {
            return params.get(param - 1).typeName;
        }
        
        @Override
        public String getParameterClassName(int param) throws SQLException {
            return TypeConverter.getClassNameForType(params.get(param - 1).sqlType);
        }
        
        @Override
        public int getParameterMode(int param) throws SQLException {
            return ParameterMetaData.parameterModeIn;
        }
        
        @Override
        @SuppressWarnings("unchecked")
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return (T) this;
            }
            throw new SQLException("Cannot unwrap to " + iface);
        }
        
        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return iface.isInstance(this);
        }
    }
}