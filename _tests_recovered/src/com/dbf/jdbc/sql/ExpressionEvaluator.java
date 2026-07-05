package com.dbf.jdbc.sql;

import java.io.IOException;
import java.sql.SQLException;

import com.dbf.jdbc.dbf.DBFReader;
import com.dbf.jdbc.parser.TokenType;
import com.dbf.jdbc.parser.ast.ExpressionNode;

/**
 * Evaluates parsed expression against a DBF record
 */
public class ExpressionEvaluator {
    private final DBFReader reader;
    
    public ExpressionEvaluator(DBFReader reader) {
        this.reader = reader;
    }
    
    public boolean evaluate(ExpressionNode expr) throws IOException {
        return evaluateNode(expr);
    }
    
    private boolean evaluateNode(ExpressionNode node) throws IOException {
        if (node == null) return true;
        
        if (node.isBinaryOp()) {
            Object left = evaluateValue(node.getLeft());
            Object right = evaluateValue(node.getRight());
            return compareValues(left, right, node.getType());
        } else if (node.getType() == TokenType.NOT) {
            return !evaluateNode((ExpressionNode) node.getLeft());
        } else {
            Object val = evaluateValue(node);
            if (val instanceof Boolean) {
                return (Boolean) val;
            }
            return val != null;
        }
    }
    
    private Object evaluateValue(ExpressionNode node) throws IOException {
        if (node == null) return null;
        
        if (node.isLiteral()) {
            return parseLiteral(node);
        }
        
        if (node.isColumn()) {
            String columnName = node.getValue();
            if (node.getTableName() != null) {
                // Table qualified column - ignore table name for now
            }
            try {
				return reader.getValue(columnName);
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
        }
        
        if (node.isBinaryOp()) {
            Object left = evaluateValue(node.getLeft());
            Object right = evaluateValue(node.getRight());
            return applyOperator(left, right, node.getType());
        }
        
        return null;
    }
    
    private Object parseLiteral(ExpressionNode node) {
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
    
    private Object applyOperator(Object left, Object right, TokenType op) {
        if (left == null || right == null) return null;
        
        switch (op) {
            case EQ:
                return left.equals(right);
            case NE:
                return !left.equals(right);
            case LT:
                return compareNumeric(left, right) < 0;
            case GT:
                return compareNumeric(left, right) > 0;
            case LE:
                return compareNumeric(left, right) <= 0;
            case GE:
                return compareNumeric(left, right) >= 0;
            default:
                return null;
        }
    }
    
    private boolean compareValues(Object left, Object right, TokenType op) {
        if (left == null || right == null) {
            if (op == TokenType.EQ) return left == right;
            if (op == TokenType.NE) return left != right;
            return false;
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
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return Double.parseDouble(value.toString());
    }
    
    private int compareNumeric(Object left, Object right) {
        double lnum = toDouble(left);
        double rnum = toDouble(right);
        return Double.compare(lnum, rnum);
    }
}