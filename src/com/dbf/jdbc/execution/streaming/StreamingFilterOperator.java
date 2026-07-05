package com.dbf.jdbc.execution.streaming;

import com.dbf.jdbc.parser.TokenType;
import com.dbf.jdbc.parser.ast.ExpressionNode;
import java.io.IOException;
import java.sql.SQLException;

/**
 * Streaming filter - passes through rows that match condition
 */
public class StreamingFilterOperator implements RowStream {
    private final RowStream input;
    private final ExpressionNode condition;
    private final String[] columnNames;
    private final int[] columnTypes;
    private boolean closed = false;
    
    public StreamingFilterOperator(RowStream input, ExpressionNode condition) {
        this.input = input;
        this.condition = condition;
        this.columnNames = input.getColumnNames();
        this.columnTypes = input.getColumnTypes();
    }
    
    @Override
    public Object[] next() throws IOException, SQLException {
        while (true) {
            Object[] row = input.next();
            if (row == null) return null;
            
            if (evaluateCondition(row, condition)) {
                return row;
            }
        }
    }
    
    private boolean evaluateCondition(Object[] row, ExpressionNode node) {
        if (node == null) return true;
        
        if (node.isBinaryOp()) {
            Object left = evaluateExpression(node.getLeft(), row);
            Object right = evaluateExpression(node.getRight(), row);
            return compareValues(left, right, node.getType());
        } else if (node.getType() == TokenType.NOT) {
            return !evaluateCondition(row, (ExpressionNode) node.getLeft());
        }
        
        Object val = evaluateExpression(node, row);
        if (val instanceof Boolean) return (Boolean) val;
        return val != null;
    }
    
    private Object evaluateExpression(ExpressionNode node, Object[] row) {
        if (node == null) return null;
        
        if (node.isLiteral()) {
            String val = node.getValue();
            if (node.getType() == TokenType.NUMBER) {
                if (val.contains(".")) return Double.parseDouble(val);
                try { return Long.parseLong(val); }
                catch (NumberFormatException e) { return Double.parseDouble(val); }
            }
            if (node.getType() == TokenType.STRING) return val;
            return null;
        }
        
        if (node.isColumn()) {
            String colName = node.getColumnName() != null ? node.getColumnName() : node.getValue();
            for (int i = 0; i < columnNames.length; i++) {
                if (columnNames[i].equalsIgnoreCase(colName)) {
                    return row[i];
                }
            }
            return null;
        }
        
        if (node.isBinaryOp()) {
            Object left = evaluateExpression(node.getLeft(), row);
            Object right = evaluateExpression(node.getRight(), row);
            return applyOperator(left, right, node.getType());
        }
        
        return null;
    }
    
    private Object applyOperator(Object left, Object right, TokenType op) {
        if (left == null || right == null) return null;
        
        try {
            double lnum = toDouble(left);
            double rnum = toDouble(right);
            
            switch (op) {
                case EQ: return Math.abs(lnum - rnum) < 1e-10;
                case NE: return Math.abs(lnum - rnum) >= 1e-10;
                case LT: return lnum < rnum;
                case GT: return lnum > rnum;
                case LE: return lnum <= rnum;
                case GE: return lnum >= rnum;
                default: return null;
            }
        } catch (NumberFormatException e) {
            String lstr = left.toString();
            String rstr = right.toString();
            
            switch (op) {
                case EQ: return lstr.equalsIgnoreCase(rstr);
                case NE: return !lstr.equalsIgnoreCase(rstr);
                case LT: return lstr.compareToIgnoreCase(rstr) < 0;
                case GT: return lstr.compareToIgnoreCase(rstr) > 0;
                case LE: return lstr.compareToIgnoreCase(rstr) <= 0;
                case GE: return lstr.compareToIgnoreCase(rstr) >= 0;
                default: return false;
            }
        }
    }
    
    private boolean compareValues(Object left, Object right, TokenType op) {
        if (left == null && right == null) return op == TokenType.EQ;
        if (left == null || right == null) return op == TokenType.NE;
        
        try {
            double lnum = toDouble(left);
            double rnum = toDouble(right);
            
            switch (op) {
                case EQ: return Math.abs(lnum - rnum) < 1e-10;
                case NE: return Math.abs(lnum - rnum) >= 1e-10;
                case LT: return lnum < rnum;
                case GT: return lnum > rnum;
                case LE: return lnum <= rnum;
                case GE: return lnum >= rnum;
                default: return false;
            }
        } catch (NumberFormatException e) {
            String lstr = left.toString();
            String rstr = right.toString();
            
            switch (op) {
                case EQ: return lstr.equalsIgnoreCase(rstr);
                case NE: return !lstr.equalsIgnoreCase(rstr);
                case LT: return lstr.compareToIgnoreCase(rstr) < 0;
                case GT: return lstr.compareToIgnoreCase(rstr) > 0;
                case LE: return lstr.compareToIgnoreCase(rstr) <= 0;
                case GE: return lstr.compareToIgnoreCase(rstr) >= 0;
                default: return false;
            }
        }
    }
    
    private double toDouble(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        return Double.parseDouble(value.toString());
    }
    
    @Override
    public String[] getColumnNames() {
        return columnNames;
    }
    
    @Override
    public int[] getColumnTypes() {
        return columnTypes;
    }
    
    @Override
    public void reset() throws IOException, SQLException {
        input.reset();
    }
    
    @Override
    public boolean supportsReset() {
        return input.supportsReset();
    }
    
    @Override
    public long estimateRowCount() {
        return input.estimateRowCount() / 2; // Rough estimate
    }
    
    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            input.close();
        }
    }
}