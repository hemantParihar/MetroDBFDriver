package com.dbf.jdbc.resultset.core;

import java.sql.SQLWarning;

/**
 * Tracks only the state of a ResultSet - no business logic
 */
public class ResultSetState {
    private boolean closed = false;
    private boolean wasNull = false;
    private SQLWarning warnings = null;
    private int fetchSize = 100;
    private int fetchDirection = java.sql.ResultSet.FETCH_FORWARD;
    private int type = java.sql.ResultSet.TYPE_FORWARD_ONLY;
    private int concurrency = java.sql.ResultSet.CONCUR_READ_ONLY;
    private int holdability = java.sql.ResultSet.HOLD_CURSORS_OVER_COMMIT;
    
    public boolean isClosed() { return closed; }
    public void setClosed(boolean closed) { this.closed = closed; }
    
    public boolean wasNull() { return wasNull; }
    public void setWasNull(boolean wasNull) { this.wasNull = wasNull; }
    
    public SQLWarning getWarnings() { return warnings; }
    public void setWarnings(SQLWarning warnings) { this.warnings = warnings; }
    public void clearWarnings() { this.warnings = null; }
    
    public int getFetchSize() { return fetchSize; }
    public void setFetchSize(int fetchSize) { this.fetchSize = fetchSize; }
    
    public int getFetchDirection() { return fetchDirection; }
    public void setFetchDirection(int fetchDirection) { this.fetchDirection = fetchDirection; }
    
    public int getType() { return type; }
    public void setType(int type) { this.type = type; }
    
    public int getConcurrency() { return concurrency; }
    public void setConcurrency(int concurrency) { this.concurrency = concurrency; }
    
    public int getHoldability() { return holdability; }
    public void setHoldability(int holdability) { this.holdability = holdability; }
}