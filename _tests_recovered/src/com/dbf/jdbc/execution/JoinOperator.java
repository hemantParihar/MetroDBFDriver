package com.dbf.jdbc.execution;

import com.dbf.jdbc.dbf.DBFField;
import com.dbf.jdbc.parser.ast.JoinNode;
import com.dbf.jdbc.parser.TokenType;
import com.dbf.jdbc.resultset.TypeConverter;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;

/**
 * Performs JOIN between two child operators
 */
public class JoinOperator extends Operator {
    private final JoinNode joinNode;
    private final List<DBFField> leftFields;
    private final List<DBFField> rightFields;
    private final String leftJoinColumn;
    private final String rightJoinColumn;
    private final JoinType joinType;
    
    private List<Object[]> rightRows;
    private Iterator<Object[]> leftIterator;
    private Object[] currentLeftRow;
    private Iterator<Object[]> rightIterator;
    private boolean leftExhausted = false;
    private boolean rightLoaded = false;
    
    public enum JoinType {
        INNER, LEFT, RIGHT, FULL
    }
    
    public JoinOperator(JoinNode joinNode, List<DBFField> leftFields, List<DBFField> rightFields) {
        this.joinNode = joinNode;
        this.leftFields = leftFields;
        this.rightFields = rightFields;
        this.joinType = convertJoinType(joinNode.getJoinType());
        
        // Extract join columns from ON condition
        if (joinNode.getCondition() != null && 
            joinNode.getCondition().isBinaryOp() && 
            joinNode.getCondition().getType() == TokenType.EQ) {
            this.leftJoinColumn = joinNode.getCondition().getLeft().getValue();
            this.rightJoinColumn = joinNode.getCondition().getRight().getValue();
        } else {
            this.leftJoinColumn = null;
            this.rightJoinColumn = null;
        }
    }
    
    private JoinType convertJoinType(com.dbf.jdbc.parser.ast.JoinType type) {
        if (type == null) return JoinType.INNER;
        switch (type) {
            case INNER: return JoinType.INNER;
            case LEFT: return JoinType.LEFT;
            case RIGHT: return JoinType.RIGHT;
            case FULL: return JoinType.FULL;
            default: return JoinType.INNER;
        }
    }
    
    @Override
    public void open() throws SQLException, IOException {
        super.open();
        if (children.size() != 2) {
            throw new SQLException("JoinOperator requires exactly 2 children");
        }
        
        // Load right side rows
        rightRows = new ArrayList<>();
        Operator rightChild = children.get(1);
        Object[] row;
        while ((row = rightChild.next()) != null) {
            rightRows.add(row);
        }
        rightLoaded = true;
        
        // Reset and prepare left side
        children.get(0).reset();
        leftIterator = null;
        currentLeftRow = null;
        rightIterator = null;
        leftExhausted = false;
    }
    
    @Override
    public Object[] next() throws SQLException, IOException {
        switch (joinType) {
            case INNER:
                return nextInnerJoin();
            case LEFT:
                return nextLeftJoin();
            case RIGHT:
                return nextRightJoin();
            case FULL:
                return nextFullJoin();
            default:
                return nextInnerJoin();
        }
    }
    
    private Object[] nextInnerJoin() throws SQLException, IOException {
        while (true) {
            // Get next left row if needed
            if (currentLeftRow == null) {
                if (leftIterator == null) {
                    leftIterator = getLeftRows();
                }
                if (leftIterator.hasNext()) {
                    currentLeftRow = leftIterator.next();
                    rightIterator = rightRows.iterator();
                } else {
                    return null;
                }
            }
            
            // Find matching right row
            while (rightIterator.hasNext()) {
                Object[] rightRow = rightIterator.next();
                if (evaluateJoinCondition(currentLeftRow, rightRow)) {
                    return combineRows(currentLeftRow, rightRow);
                }
            }
            
            // No match found for current left row, move to next
            currentLeftRow = null;
        }
    }
    
    private Object[] nextLeftJoin() throws SQLException, IOException {
        while (true) {
            // Get next left row if needed
            if (currentLeftRow == null) {
                if (leftIterator == null) {
                    leftIterator = getLeftRows();
                }
                if (leftIterator.hasNext()) {
                    currentLeftRow = leftIterator.next();
                    rightIterator = rightRows.iterator();
                    leftExhausted = false;
                } else {
                    return null;
                }
            }
            
            // Find matching right row
            boolean foundMatch = false;
            while (rightIterator.hasNext()) {
                Object[] rightRow = rightIterator.next();
                if (evaluateJoinCondition(currentLeftRow, rightRow)) {
                    foundMatch = true;
                    incrementRowsProcessed();
                    return combineRows(currentLeftRow, rightRow);
                }
            }
            
            // No match found, return left row with nulls
            if (!foundMatch && !leftExhausted) {
                leftExhausted = true;
                incrementRowsProcessed();
                return combineRows(currentLeftRow, createNullRow(rightFields.size()));
            }
            
            currentLeftRow = null;
        }
    }
    
    private Object[] nextRightJoin() throws SQLException, IOException {
        // For RIGHT JOIN, we can swap the operators
        return nextLeftJoin();
    }
    
    private Object[] nextFullJoin() throws SQLException, IOException {
        // Simplified - in production, implement properly
        return nextLeftJoin();
    }
    
    private Iterator<Object[]> getLeftRows() throws SQLException, IOException {
        List<Object[]> leftRows = new ArrayList<>();
        Operator leftChild = children.get(0);
        leftChild.reset();
        Object[] row;
        while ((row = leftChild.next()) != null) {
            leftRows.add(row);
        }
        return leftRows.iterator();
    }
    
    private boolean evaluateJoinCondition(Object[] leftRow, Object[] rightRow) {
        if (leftJoinColumn == null || rightJoinColumn == null) return true;
        
        Object leftVal = getColumnValue(leftRow, leftFields, leftJoinColumn);
        Object rightVal = getColumnValue(rightRow, rightFields, rightJoinColumn);
        
        if (leftVal == null && rightVal == null) return true;
        if (leftVal == null || rightVal == null) return false;
        
        return leftVal.equals(rightVal);
    }
    
    private Object getColumnValue(Object[] row, List<DBFField> fields, String columnName) {
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).getName().equalsIgnoreCase(columnName)) {
                return (i < row.length) ? row[i] : null;
            }
        }
        return null;
    }
    
    private Object[] combineRows(Object[] leftRow, Object[] rightRow) {
        int leftSize = leftFields.size();
        int rightSize = rightFields.size();
        Object[] combined = new Object[leftSize + rightSize];
        System.arraycopy(leftRow, 0, combined, 0, Math.min(leftSize, leftRow.length));
        System.arraycopy(rightRow, 0, combined, leftSize, Math.min(rightSize, rightRow.length));
        return combined;
    }
    
    private Object[] createNullRow(int size) {
        Object[] row = new Object[size];
        Arrays.fill(row, null);
        return row;
    }
    
    @Override
    public String getOperatorName() {
        return "Join";
    }
    
    @Override
    public String getOperatorDetails() {
        return "type=" + joinType + (leftJoinColumn != null ? 
               " on " + leftJoinColumn + "=" + rightJoinColumn : "");
    }
    
    @Override
    public long estimateCost() throws SQLException, IOException {
        if (children.size() != 2) return 0;
        long leftCost = children.get(0).estimateCost();
        long rightCost = children.get(1).estimateCost();
        // Join cost is roughly left * right
        return leftCost * rightCost / 1000;
    }
}