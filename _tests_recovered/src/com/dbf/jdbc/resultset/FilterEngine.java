package com.dbf.jdbc.resultset;

import com.dbf.jdbc.parser.ast.ExpressionNode;
import com.dbf.jdbc.parser.TokenType;
import java.io.IOException;
import java.util.Map;

/**
 * Evaluates WHERE clause conditions against rows
 */
public class FilterEngine {
    private final ExpressionNode condition;
    private final RowProjector projector;
    
    public FilterEngine(ExpressionNode condition, RowProjector projector) {
        this.condition = condition;
        this.projector = projector;
    }
    
    public boolean matches(Object[] fullRow) throws IOException {
        if (condition == null) return true;
        return evaluateExpression(condition, fullRow);
    }
    
    private boolean evaluateExpression(ExpressionNode node, Object[] fullRow) {
        if (node == null) return true;
        
        if (node.isBinaryOp()) {
            Object left = evaluateValue(node.getLeft(), fullRow);
            Object right = evaluateValue(node.getRight(), fullRow);
            return compareValues(left, right, node.getType());
        } else if (node.getType() == TokenType.NOT) {
            return !evaluateExpression((ExpressionNode) node.getLeft(), fullRow);
        } else {
            Object val = evaluateValue(node, fullRow);
            if (val instanceof Boolean) {
                return (Boolean) val;
            }
            return val != null;
        }
    }
    
    private Object evaluateValue(ExpressionNode node, Object[] fullRow) {
        if (node == null) return null;
        
        if (node.isLiteral()) {
            switch (node.getType()) {
                case NUMBER:
                    String val = node.getValue();
                    if (val.contains(".")) {
                        return Double.parseDouble(val);
                    }
                    try {
                        return Long.parseLong(val);
                    } catch (NumberFormatException e) {
                        return Double.parseDouble(val);
                    }
                case STRING:
                    return node.getValue();
                case NULL:
                    return null;
                default:
                    return node.getValue();
            }
        }
        
        if (node.isColumn()) {
            String columnName = node.getColumnName() != null ? node.getColumnName() : node.getValue();
            int colIdx = projector.getColumnIndex(columnName);
            if (colIdx >= 0 && colIdx < fullRow.length) {
                return fullRow[colIdx];
            }
            return null;
        }
        
        if (node.isBinaryOp()) {
            Object left = evaluateValue(node.getLeft(), fullRow);
            Object right = evaluateValue(node.getRight(), fullRow);
            return applyOperator(left, right, node.getType());
        }
        
        return null;
    }
    
    private Object applyOperator(Object left, Object right, TokenType op) {
        if (left == null || right == null) return null;
        
        switch (op) {
            case EQ: return left.equals(right);
            case NE: return !left.equals(right);
            case LT: return compareNumeric(left, right) < 0;
            case GT: return compareNumeric(left, right) > 0;
            case LE: return compareNumeric(left, right) <= 0;
            case GE: return compareNumeric(left, right) >= 0;
            default: return null;
        }
    }
    
    private boolean compareValues(Object left, Object right, TokenType op) {
        if (left == null && right == null) {
            return op == TokenType.EQ;
        }
        if (left == null || right == null) {
            return op == TokenType.NE;
        }
        
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
    
    private int compareNumeric(Object left, Object right) {
        double lnum = toDouble(left);
        double rnum = toDouble(right);
        return Double.compare(lnum, rnum);
    }
}