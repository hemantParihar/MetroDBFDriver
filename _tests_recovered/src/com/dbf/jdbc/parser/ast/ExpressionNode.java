package com.dbf.jdbc.parser.ast;

import com.dbf.jdbc.parser.Token;
import com.dbf.jdbc.parser.TokenType;

public class ExpressionNode extends ASTNode {
    private ExpressionNode left;
    private ExpressionNode right;
    private String tableName;  // For qualified column references (table.column)
    private String columnName;  // For column references
    private Object literalValue; // For literal values
 // In ExpressionNode.java, add:

    private boolean isRecno = false;
    private int recnoValue = -1;

    public void setRecno(boolean isRecno) {
        this.isRecno = isRecno;
    }

    public boolean isRecno() {
        return isRecno;
    }

    public void setRecnoValue(int recnoValue) {
        this.recnoValue = recnoValue;
    }

    public int getRecnoValue() {
        return recnoValue;
    }
    
    public ExpressionNode(Token token) {
        super(token);
        if (token != null && token.getType() == TokenType.IDENTIFIER) {
            this.columnName = token.getValue();
        }
    }
    
    public ExpressionNode(Object literalValue) {
        super(null);
        this.literalValue = literalValue;
    }
    
    public void setLeft(ExpressionNode left) {
        this.left = left;
        if (left != null) addChild(left);
    }
    
    public ExpressionNode getLeft() {
        return left;
    }
    
    public void setRight(ExpressionNode right) {
        this.right = right;
        if (right != null) addChild(right);
    }
    
    public ExpressionNode getRight() {
        return right;
    }
    
    public void setTableName(String tableName) {
        this.tableName = tableName;
    }
    
    public String getTableName() {
        return tableName;
    }
    
    public void setColumnName(String columnName) {
        this.columnName = columnName;
        // Also update the token value for backward compatibility
        if (getToken() != null && getToken().getValue() == null) {
            // Can't modify token directly, but we store separately
        }
    }
    
    public String getColumnName() {
        return columnName;
    }
    
    public Object getLiteralValue() {
        return literalValue;
    }
    
    public void setLiteralValue(Object literalValue) {
        this.literalValue = literalValue;
    }
    
    @Override
    public String getValue() {
        if (columnName != null) return columnName;
        if (literalValue != null) return literalValue.toString();
        return super.getValue();
    }
    
    public boolean isBinaryOp() {
        TokenType type = getType();
        return type == TokenType.EQ || type == TokenType.LT || type == TokenType.GT ||
               type == TokenType.LE || type == TokenType.GE || type == TokenType.NE ||
               type == TokenType.AND || type == TokenType.OR || type == TokenType.LIKE ||
               type == TokenType.IN || type == TokenType.PLUS || type == TokenType.MINUS ||
               type == TokenType.MULTIPLY || type == TokenType.DIVIDE;
    }
    
    public boolean isLiteral() {
        TokenType type = getType();
        return type == TokenType.NUMBER || type == TokenType.STRING || type == TokenType.NULL ||
               literalValue != null;
    }
    
    public boolean isColumn() {
        TokenType type = getType();
        return (type == TokenType.IDENTIFIER || columnName != null) && !isLiteral();
    }
    
    public boolean isQualifiedColumn() {
        return tableName != null && columnName != null;
    }
    
    // Helper method to evaluate expression (will be used by evaluator)
    public Object evaluate(java.util.Map<String, Object> rowData) {
        if (isLiteral()) {
            if (literalValue != null) return literalValue;
            switch (getType()) {
                case NUMBER:
                    String val = getValue();
                    if (val.contains(".")) return Double.parseDouble(val);
                    try { return Long.parseLong(val); }
                    catch (NumberFormatException e) { return Double.parseDouble(val); }
                case STRING:
                    return getValue();
                case NULL:
                    return null;
                default:
                    return getValue();
            }
        }
        
        if (isColumn()) {
            String col = columnName != null ? columnName : getValue();
            if (rowData != null && rowData.containsKey(col)) {
                return rowData.get(col);
            }
            return null;
        }
        
        if (isBinaryOp() && left != null && right != null) {
            Object leftVal = left.evaluate(rowData);
            Object rightVal = right.evaluate(rowData);
            return applyOperator(leftVal, rightVal, getType());
        }
        
        return null;
    }
    
    private Object applyOperator(Object left, Object right, TokenType op) {
        if (left == null || right == null) {
            if (op == TokenType.EQ) return left == right;
            if (op == TokenType.NE) return left != right;
            return null;
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
                case PLUS: return lnum + rnum;
                case MINUS: return lnum - rnum;
                case MULTIPLY: return lnum * rnum;
                case DIVIDE: return rnum != 0 ? lnum / rnum : null;
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
                default: return null;
            }
        }
    }
    
    private double toDouble(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        return Double.parseDouble(value.toString());
    }
}