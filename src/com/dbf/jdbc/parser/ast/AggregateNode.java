package com.dbf.jdbc.parser.ast;

public class AggregateNode extends ASTNode {
    private String function;
    private boolean distinct;
    private boolean star;
    private ColumnNode column;
    private ExpressionNode argument; // full expression argument, e.g. MAX(STR(YEAR(d)))
    private String alias;
    private Object computedValue;
    
    public AggregateNode(String function, boolean distinct) {
        super(null);
        this.function = function.toUpperCase();
        this.distinct = distinct;
    }
    
    public String getFunction() { 
        return function; 
    }
    
    public boolean isDistinct() { 
        return distinct; 
    }
    
    public boolean isStar() { 
        return star; 
    }
    
    public void setStar(boolean star) { 
        this.star = star; 
    }
    
    public ColumnNode getColumn() {
        return column;
    }

    public void setColumn(ColumnNode column) {
        this.column = column;
        if (column != null) addChild(column);
    }

    /** The aggregate's argument as a full expression (null for COUNT(*)). */
    public ExpressionNode getArgument() {
        return argument;
    }

    public void setArgument(ExpressionNode argument) {
        this.argument = argument;
        if (argument != null) addChild(argument);
    }
    
    public String getAlias() { 
        return alias; 
    }
    
    public void setAlias(String alias) { 
        this.alias = alias; 
    }
    
    public Object getComputedValue() { 
        return computedValue; 
    }
    
    public void setComputedValue(Object value) { 
        this.computedValue = value; 
    }
}