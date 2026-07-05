// GroupByNode.java
package com.dbf.jdbc.parser.ast;

import java.util.ArrayList;
import java.util.List;

public class GroupByNode extends ASTNode {
    private List<String> columnNames = new ArrayList<>();
    private List<Integer> columnPositions = new ArrayList<>();
    // One key expression per GROUP BY item, in order. A plain column is stored
    // as a column-reference expression; MID(CUST_DESC,31,10) etc. as that
    // expression. The aggregate engine groups by each key's evaluated value.
    private final List<ExpressionNode> keys = new ArrayList<>();

    public GroupByNode() { super(null); }

    public void addColumnName(String name) { columnNames.add(name); }
    public void addColumn(int position) { columnPositions.add(position); }
    public List<String> getColumnNames() { return columnNames; }
    public List<Integer> getColumnPositions() { return columnPositions; }

    public void addKey(ExpressionNode key) { keys.add(key); }
    public List<ExpressionNode> getKeys() { return keys; }
}
