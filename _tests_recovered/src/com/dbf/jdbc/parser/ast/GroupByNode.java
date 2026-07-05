// GroupByNode.java
package com.dbf.jdbc.parser.ast;

import java.util.ArrayList;
import java.util.List;

public class GroupByNode extends ASTNode {
    private List<String> columnNames = new ArrayList<>();
    private List<Integer> columnPositions = new ArrayList<>();
    
    public GroupByNode() { super(null); }
    
    public void addColumnName(String name) { columnNames.add(name); }
    public void addColumn(int position) { columnPositions.add(position); }
    public List<String> getColumnNames() { return columnNames; }
    public List<Integer> getColumnPositions() { return columnPositions; }
}
