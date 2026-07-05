package com.dbf.jdbc.execution;

import com.dbf.jdbc.parser.TokenType;
import com.dbf.jdbc.parser.ast.ExpressionNode;
import com.dbf.jdbc.resultset.TypeConverter;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

/**
 * Filters rows based on a WHERE condition
 */
public class FilterOperator extends Operator {
    private final ExpressionNode condition;
    private final List<String> columnNames;
    private boolean hasMoreRows = true;
    private Object[] nextRow = null;
    
    public FilterOperator(ExpressionNode condition, List<String> columnNames) {
        this.condition = condition;
        this.columnNames = columnNames;
    }
    
    @Override
    public void open() throws SQLException, IOException {
        super.open();
        if (children.isEmpty()) {
            throw new SQLException("FilterOperator requires a child operator");
        }
        hasMoreRows = true;
        nextRow = null;
    }
    
    @Override
    public Object[] next() throws SQLException, IOException {
        if (!hasMoreRows) return null;
        
        // If we have a pre-fetched row from previous iteration
        if (nextRow != null) {
            Object[] result = nextRow;
            nextRow = null;
            incrementRowsProcessed();
            return result;
        }
        
        // Get next row from child and apply filter
        Object[] row;
        while ((row = children.get(0).next()) != null) {
            if (evaluateCondition(row)) {
                incrementRowsProcessed();
                return row;
            }
        }
        
        hasMoreRows = false;
        return null;
    }
    
    private boolean evaluateCondition(Object[] row) throws IOException {
        if (condition == null) return true;
        return evaluateExpression(condition, row);
    }
    
    private boolean evaluateExpression(ExpressionNode node, Object[] row) {
        if (node == null) return true;
        
        if (node.isBinaryOp()) {
            Object left = evaluateValue(node.getLeft(), row);
            Object right = evaluateValue(node.getRight(), row);
            return compareValues(left, right, node.getType());
        } else if (node.getType() == TokenType.NOT) {
            return !evaluateExpression((ExpressionNode) node.getLeft(), row);
        } else {
            Object val = evaluateValue(node, row);
            if (val instanceof Boolean) {
                return (Boolean) val;
            }
            return val != null;
        }
    }
    
    private Object evaluateValue(ExpressionNode node, Object[] row) {
        if (node == null) return null;
        
        if (node.isLiteral()) {
            switch (node.getType()) {
                case NUMBER:
                    String val = node.getValue();
                    if (val.contains(".")) return Double.parseDouble(val);
                    try { return Long.parseLong(val); }
                    catch (NumberFormatException e) { return Double.parseDouble(val); }
                case STRING: return node.getValue();
                case NULL: return null;
                default: return node.getValue();
            }
        }
        
        if (node.isColumn()) {
            String columnName = node.getColumnName() != null ? node.getColumnName() : node.getValue();
            int colIdx = findColumnIndex(columnName);
            if (colIdx >= 0 && colIdx < row.length) {
                return row[colIdx];
            }
            return null;
        }
        
        if (node.isBinaryOp()) {
            Object left = evaluateValue(node.getLeft(), row);
            Object right = evaluateValue(node.getRight(), row);
            return applyOperator(left, right, node.getType());
        }
        
        return null;
    }
    
    private int findColumnIndex(String columnName) {
        for (int i = 0; i < columnNames.size(); i++) {
            if (columnNames.get(i).equalsIgnoreCase(columnName)) {
                return i;
            }
        }
        return -1;
    }
    
    private Object applyOperator(Object left, Object right, TokenType op) {
        if (left == null || right == null) return null;
        
        try {
            double lnum = TypeConverter.toDouble(left);
            double rnum = TypeConverter.toDouble(right);
            
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
            double lnum = TypeConverter.toDouble(left);
            double rnum = TypeConverter.toDouble(right);
            
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
    
    @Override
    public String getOperatorName() {
        return "Filter";
    }
    
    @Override
    public String getOperatorDetails() {
        return "condition=" + (condition != null ? condition.toString() : "none");
    }
    
    @Override
    public long estimateCost() throws SQLException, IOException {
        if (children.isEmpty()) return 0;
        long childCost = children.get(0).estimateCost();
        // Assume filter eliminates ~50% of rows
        return childCost / 2;
    }
}