package com.dbf.jdbc.resultset.core;

import java.io.IOException;
import java.sql.SQLException;

import com.dbf.jdbc.dbf.DBFReader;
import com.dbf.jdbc.execution.streaming.ExternalSortOperator;
import com.dbf.jdbc.execution.streaming.HashAggregateOperator;
import com.dbf.jdbc.execution.streaming.RowStream;
import com.dbf.jdbc.execution.streaming.StreamingFilterOperator;
import com.dbf.jdbc.execution.streaming.StreamingTableScanOperator;
import com.dbf.jdbc.parser.ast.SelectNode;

/**
 * Handles query execution - produces RowStream
 */
public class ResultSetExecutor {
    private final String path;
    private final String charset;
    
    public ResultSetExecutor(String path, String charset) {
        this.path = path;
        this.charset = charset;
    }
    
    public RowStream executeSelect(SelectNode selectNode) throws SQLException, IOException {
        String tableName = selectNode.getFrom().getTableName();
        String filePath = path + "/" + tableName + ".dbf";
        DBFReader reader = new DBFReader(filePath, java.nio.charset.Charset.forName(charset));
        
        // Build streaming pipeline
        RowStream stream = new StreamingTableScanOperator(reader, tableName, selectNode.getFrom().getAlias());
        
        if (selectNode.getWhere() != null) {
            stream = new StreamingFilterOperator(stream, selectNode.getWhere().getCondition());
        }
        
        if (selectNode.getOrderBy() != null) {
            stream = new ExternalSortOperator(stream, selectNode.getOrderBy(), stream.getColumnNames());
        }
        
        if (selectNode.getGroupBy() != null || !selectNode.getAggregates().isEmpty()) {
            stream = new HashAggregateOperator(stream, selectNode, stream.getColumnNames());
        }
        
        return stream;
    }
}