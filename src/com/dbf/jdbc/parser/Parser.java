package com.dbf.jdbc.parser;

import com.dbf.jdbc.parser.ast.*;
import java.io.IOException;
import java.io.Reader;
import java.util.*;

/**
 * Recursive-descent SQL parser for the DBF JDBC driver.
 *
 * Supported grammar:
 *   SELECT selectItem {, selectItem}
 *   FROM table [alias]
 *   [ [INNER|LEFT|RIGHT|FULL] [OUTER] JOIN table [alias] ON expr ]
 *   [ WHERE expr ]
 *   [ GROUP BY column {, column} ]
 *   [ HAVING expr ]
 *   [ ORDER BY column [ASC|DESC] {, column [ASC|DESC]} ]
 *
 * selectItem: * | RECNO() | COUNT/SUM/AVG/MIN/MAX([DISTINCT] col|*) | [tbl.]col  — each with optional [AS] alias
 *
 * WHERE expressions support: AND, OR, NOT, =, <>, !=, <, >, <=, >=,
 * BETWEEN, LIKE, IN (...), IS [NOT] NULL, RECNO(), parentheses and
 * +, -, *, / arithmetic.
 */
public class Parser {
    private Token currentToken;
    private int tokenIndex = 0;
    private final List<Token> tokens;

    public Parser(Reader reader) throws IOException {
        Lexer lexer = new Lexer(reader);
        this.tokens = lexer.getAllTokens();
        this.currentToken = tokens.isEmpty()
            ? new Token(TokenType.EOF, null, 1, 1)
            : tokens.get(0);
    }

    // ==================== Token helpers ====================

    private void advance() {
        tokenIndex++;
        if (tokenIndex < tokens.size()) {
            currentToken = tokens.get(tokenIndex);
        } else {
            currentToken = new Token(TokenType.EOF, null,
                currentToken.getLine(), currentToken.getColumn());
        }
    }

    private boolean is(TokenType type) {
        return currentToken.getType() == type;
    }

    private boolean match(TokenType type) {
        if (is(type)) {
            advance();
            return true;
        }
        return false;
    }

    private Token expect(TokenType type) throws ParseException {
        if (!is(type)) {
            throw new ParseException("Expected " + type + " but found " + currentToken.getType()
                + (currentToken.getValue() != null ? " ('" + currentToken.getValue() + "')" : "")
                + " at line " + currentToken.getLine() + ", column " + currentToken.getColumn());
        }
        Token t = currentToken;
        advance();
        return t;
    }

    /** True when the current token is an IDENTIFIER with the given (case-insensitive) value. */
    private boolean isIdent(String word) {
        return is(TokenType.IDENTIFIER) && word.equalsIgnoreCase(currentToken.getValue());
    }

    private Token syntheticToken(TokenType type, String value) {
        return new Token(type, value, currentToken.getLine(), currentToken.getColumn());
    }

    // ==================== SELECT ====================

    public SelectNode parseSelect() throws ParseException {
        SelectNode select = new SelectNode();

        expect(TokenType.SELECT);

        // Optional row limit: SQL-Server / MS-Access style "TOP n" (with
        // optional PERCENT, which we treat as a plain row count).
        if (isIdent("top")) {
            advance();
            select.setLimit(parseLimitCount());
            if (isIdent("percent")) {
                advance(); // accepted but treated as an absolute row count
            }
        }
        // Tolerate (and ignore) DISTINCT/ALL right after SELECT/TOP
        if (isIdent("distinct") || isIdent("all")) {
            advance();
        }

        parseSelectList(select);

        expect(TokenType.FROM);
        parseFromClause(select);

        // Optional WHERE
        if (match(TokenType.WHERE)) {
            WhereNode where = new WhereNode();
            where.setCondition(parseExpression());
            select.setWhere(where);
        }

        // Optional GROUP BY
        if (is(TokenType.GROUP)) {
            advance();
            expect(TokenType.BY);
            GroupByNode groupBy = new GroupByNode();
            do {
                // GROUP BY accepts a column or an expression (e.g. MID(x,31,10)).
                ExpressionNode key = parseExpression();
                groupBy.addKey(key);
                if (key.isColumn() && !key.isRecno()) {
                    groupBy.addColumnName(key.getColumnName());
                }
            } while (match(TokenType.COMMA));
            select.setGroupBy(groupBy);
        }

        // Optional HAVING
        if (match(TokenType.HAVING)) {
            HavingNode having = new HavingNode();
            having.setCondition(parseExpression());
            select.setHaving(having);
        }

        // Optional ORDER BY
        if (is(TokenType.ORDER)) {
            advance();
            expect(TokenType.BY);
            OrderByNode orderBy = new OrderByNode();
            do {
                if (isAggregateFunction()) {
                    // ORDER BY MAX(col) / SUM(col) / ... (aggregate sort key)
                    AggregateNode agg = parseAggregateCall();
                    boolean ascending = !match(TokenType.DESC);
                    if (ascending) {
                        match(TokenType.ASC);
                    }
                    orderBy.addItem(new OrderByNode.OrderItem(agg, ascending));
                    continue;
                }
                ExpressionNode expr = parseExpression();
                boolean ascending = true;
                if (match(TokenType.DESC)) {
                    ascending = false;
                } else {
                    match(TokenType.ASC);
                }
                // ORDER BY ordinal: a bare positive integer references the Nth
                // select column (1-based), e.g. ORDER BY 5,6.
                Integer ordinal = asOrdinal(expr);
                if (ordinal != null && ordinal >= 1
                        && ordinal <= select.getSelectItems().size()) {
                    orderBy.addItem(orderItemForSelectItem(
                        select.getSelectItems().get(ordinal - 1), ascending));
                } else if (expr.isColumn()) {
                    // A bare column keeps the simple name-based ordering path;
                    // only real expressions/functions become expression keys.
                    orderBy.addItem(new OrderByNode.OrderItem(expr.getColumnName(), ascending));
                } else {
                    orderBy.addItem(new OrderByNode.OrderItem(expr, ascending));
                }
            } while (match(TokenType.COMMA));
            select.setOrderBy(orderBy);
        }

        // Optional trailing "LIMIT n" (MySQL/SQLite style)
        if (isIdent("limit")) {
            advance();
            select.setLimit(parseLimitCount());
        }

        // Allow a trailing semicolon (lexed as UNKNOWN), then require EOF
        while (is(TokenType.UNKNOWN) && ";".equals(currentToken.getValue())) {
            advance();
        }
        if (!is(TokenType.EOF)) {
            throw new ParseException("Unexpected token " + currentToken.getType()
                + (currentToken.getValue() != null ? " ('" + currentToken.getValue() + "')" : "")
                + " at line " + currentToken.getLine() + ", column " + currentToken.getColumn());
        }

        return select;
    }

    /** Returns the value of a bare positive-integer literal (ORDER BY ordinal), else null. */
    private Integer asOrdinal(ExpressionNode expr) {
        if (expr == null || expr.isColumn() || expr.isFunction() || expr.isAggregate()) {
            return null;
        }
        if (expr.getType() != TokenType.NUMBER) {
            return null;
        }
        try {
            String v = expr.getValue();
            if (v == null || v.indexOf('.') >= 0) {
                return null;
            }
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Builds an ORDER BY item that references a SELECT item (for ordinal ORDER BY). */
    private OrderByNode.OrderItem orderItemForSelectItem(
            com.dbf.jdbc.parser.ast.ASTNode item, boolean ascending) {
        if (item instanceof AggregateNode) {
            return new OrderByNode.OrderItem((AggregateNode) item, ascending);
        }
        ColumnNode col = (ColumnNode) item;
        if (col.isExpression()) {
            return new OrderByNode.OrderItem(col.getExpression(), ascending);
        }
        // Build a (possibly qualified) column-reference so the right table's
        // column is used when names collide across joined tables.
        ExpressionNode ref = new ExpressionNode(
            syntheticToken(TokenType.IDENTIFIER, col.getColumnName()));
        ref.setColumnName(col.getColumnName());
        ref.setTableName(col.getTableName());
        return new OrderByNode.OrderItem(ref, ascending);
    }

    /** Parses an integer row-count for TOP/LIMIT. */
    private int parseLimitCount() throws ParseException {
        Token n = expect(TokenType.NUMBER);
        try {
            int value = Integer.parseInt(n.getValue());
            if (value < 0) {
                throw new ParseException("Row limit cannot be negative: " + value);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new ParseException("Invalid row limit: " + n.getValue());
        }
    }

    /** Parses [table.]column (or RECNO[()]) and returns just the column name. */
    private String parseColumnRef() throws ParseException {
        if (is(TokenType.RECNO)) {
            advance();
            consumeEmptyParens();
            return "RECNO";
        }
        String name = expect(TokenType.IDENTIFIER).getValue();
        if (match(TokenType.DOT)) {
            name = expect(TokenType.IDENTIFIER).getValue();
        }
        return name;
    }

    private void parseSelectList(SelectNode select) throws ParseException {
        do {
            if (is(TokenType.STAR)) {
                select.addColumn(new ColumnNode(currentToken));
                advance();
            } else if (is(TokenType.RECNO)) {
                advance();
                consumeEmptyParens();
                ColumnNode recnoCol = new ColumnNode(syntheticToken(TokenType.IDENTIFIER, "RECNO"));
                recnoCol.setColumnName("RECNO");
                recnoCol.setAlias(parseOptionalAlias());
                select.addColumn(recnoCol);
            } else {
                // General select item: any expression with optional [AS] alias.
                // A bare aggregate becomes an AggregateNode (the established
                // aggregate path); a bare [table.]column stays a plain ColumnNode
                // (single-table fast path); anything else (literal, function,
                // arithmetic, or an aggregate combined with arithmetic such as
                // MAX(x)+1) becomes a computed expression.
                ExpressionNode expr = parseExpression();
                String alias = parseOptionalAlias();

                if (expr.isAggregate()) {
                    AggregateNode agg = new AggregateNode(
                        expr.getAggregateFunction(), expr.isDistinct());
                    if (expr.isStar()) {
                        agg.setStar(true);
                    } else {
                        agg.setArgument(expr.getAggregateArg());
                    }
                    agg.setAlias(alias);
                    select.addAggregate(agg);
                } else if (expr.isColumn() && !expr.isRecno()) {
                    ColumnNode column = new ColumnNode(syntheticToken(TokenType.IDENTIFIER,
                        expr.getColumnName()));
                    column.setColumnName(expr.getColumnName());
                    column.setTableName(expr.getTableName());
                    column.setAlias(alias);
                    select.addColumn(column);
                } else {
                    ColumnNode column = new ColumnNode(syntheticToken(TokenType.IDENTIFIER,
                        alias != null ? alias : "EXPR"));
                    column.setExpression(expr);
                    column.setAlias(alias);
                    select.addColumn(column);
                }
            }
        } while (match(TokenType.COMMA));
    }

    // ==================== FROM clause (multi-table) ====================

    /**
     * Parses the FROM clause, which may be a parenthesized left-deep join
     * tree such as {@code ((A x JOIN B y ON ..) JOIN C z ON ..)}. The tree
     * is flattened into a base table plus an ordered list of joins.
     */
    private void parseFromClause(SelectNode select) throws ParseException {
        TableRef ref = parseTableExpression();
        select.setFrom(ref.base);
        for (JoinNode join : ref.joins) {
            select.addJoin(join);
        }
    }

    private static final class TableRef {
        final FromNode base;
        final java.util.List<JoinNode> joins = new java.util.ArrayList<>();
        TableRef(FromNode base) {
            this.base = base;
        }
    }

    private TableRef parseTableExpression() throws ParseException {
        TableRef ref;
        if (match(TokenType.LPAREN)) {
            ref = parseTableExpression();
            expect(TokenType.RPAREN);
        } else {
            ref = new TableRef(parseTablePrimary());
        }
        // Trailing joins at this nesting level (left-deep accumulation)
        while (isJoinStart()) {
            ref.joins.add(parseJoin());
        }
        return ref;
    }

    private FromNode parseTablePrimary() throws ParseException {
        FromNode from = new FromNode();
        from.setTableName(expect(TokenType.IDENTIFIER).getValue());
        from.setAlias(parseOptionalTableAlias());
        return from;
    }

    private boolean isJoinStart() {
        return is(TokenType.JOIN) || is(TokenType.INNER) || is(TokenType.LEFT)
            || is(TokenType.RIGHT) || is(TokenType.FULL);
    }

    /** Table alias: an identifier that is not a soft keyword starting the next clause. */
    private String parseOptionalTableAlias() throws ParseException {
        if (isIdent("as")) {
            advance();
            return expect(TokenType.IDENTIFIER).getValue();
        }
        if (is(TokenType.IDENTIFIER) && !isSoftKeyword()) {
            String alias = currentToken.getValue();
            advance();
            return alias;
        }
        return null;
    }

    /**
     * Identifiers that introduce a clause and so must never be consumed as an
     * alias (they aren't lexer keywords, so the parser has to guard them).
     */
    private boolean isSoftKeyword() {
        return isIdent("limit") || isIdent("offset");
    }

    private boolean isAggregateFunction() {
        TokenType t = currentToken.getType();
        return t == TokenType.COUNT || t == TokenType.SUM || t == TokenType.AVG
            || t == TokenType.MAX || t == TokenType.MIN;
    }

    private AggregateNode parseAggregate() throws ParseException {
        AggregateNode aggregate = parseAggregateCall();
        aggregate.setAlias(parseOptionalAlias());
        return aggregate;
    }

    /** Parses an aggregate call as an ExpressionNode so it can sit inside an expression. */
    private ExpressionNode parseAggregateExpr() throws ParseException {
        String function = currentToken.getType().name();
        advance();
        expect(TokenType.LPAREN);
        boolean distinct = false;
        if (isIdent("distinct")) {
            distinct = true;
            advance();
        }
        ExpressionNode node = new ExpressionNode(syntheticToken(TokenType.IDENTIFIER, function));
        if (is(TokenType.STAR)) {
            advance();
            node.setAggregate(function, null, true, distinct);
        } else {
            ExpressionNode arg = parseExpression();
            node.setAggregate(function, arg, false, distinct);
        }
        expect(TokenType.RPAREN);
        return node;
    }

    /** Parses FUNC( [DISTINCT] expr | * ) without a trailing alias. */
    private AggregateNode parseAggregateCall() throws ParseException {
        String function = currentToken.getType().name();
        advance();
        expect(TokenType.LPAREN);

        boolean distinct = false;
        if (isIdent("distinct")) {
            distinct = true;
            advance();
        }

        AggregateNode aggregate = new AggregateNode(function, distinct);
        if (is(TokenType.STAR)) {
            aggregate.setStar(true);
            advance();
        } else {
            // The argument is any scalar expression, e.g. MAX(col),
            // MAX(STR(YEAR(d))+'-'+STR(MONTH(d))), SUM(IIF(...)).
            ExpressionNode arg = parseExpression();
            aggregate.setArgument(arg);
            if (arg.isColumn()) {
                ColumnNode column = new ColumnNode(syntheticToken(
                    TokenType.IDENTIFIER, arg.getColumnName()));
                column.setColumnName(arg.getColumnName());
                column.setTableName(arg.getTableName());
                aggregate.setColumn(column);
            }
        }
        expect(TokenType.RPAREN);
        return aggregate;
    }

    /** Parses [AS] alias if present. "AS" is lexed as a plain identifier. */
    private String parseOptionalAlias() throws ParseException {
        if (isIdent("as")) {
            advance();
            // A quoted alias (AS 'fun_Tax%') lexes as a STRING; backtick/bracket
            // aliases already lex as IDENTIFIER. Both are accepted.
            if (is(TokenType.STRING)) {
                String alias = currentToken.getValue();
                advance();
                return alias;
            }
            return expect(TokenType.IDENTIFIER).getValue();
        }
        if (is(TokenType.IDENTIFIER) && !isSoftKeyword()) {
            String alias = currentToken.getValue();
            advance();
            return alias;
        }
        return null;
    }

    private void consumeEmptyParens() {
        if (is(TokenType.LPAREN)) {
            advance();
            match(TokenType.RPAREN);
        }
    }

    // ==================== JOIN ====================

    private JoinNode parseJoin() throws ParseException {
        JoinNode join = new JoinNode();

        JoinType type = JoinType.INNER;
        if (match(TokenType.INNER)) {
            type = JoinType.INNER;
        } else if (match(TokenType.LEFT)) {
            type = JoinType.LEFT;
        } else if (match(TokenType.RIGHT)) {
            type = JoinType.RIGHT;
        } else if (match(TokenType.FULL)) {
            type = JoinType.FULL;
        }
        if (isIdent("outer")) {
            advance();
        }
        join.setJoinType(type);

        expect(TokenType.JOIN);
        join.setRightTable(expect(TokenType.IDENTIFIER).getValue());
        if (is(TokenType.IDENTIFIER) && !isIdent("as")) {
            join.setRightTableAlias(currentToken.getValue());
            advance();
        } else if (isIdent("as")) {
            advance();
            join.setRightTableAlias(expect(TokenType.IDENTIFIER).getValue());
        }

        expect(TokenType.ON);
        join.setCondition(parseExpression());
        return join;
    }

    // ==================== Expressions ====================
    //
    // expression := andExpr (OR andExpr)*
    // andExpr    := notExpr (AND notExpr)*
    // notExpr    := NOT notExpr | predicate
    // predicate  := additive [comparison | BETWEEN | LIKE | IN | IS NULL]
    // additive   := term ((+|-) term)*
    // term       := factor ((*|/) factor)*
    // factor     := literal | RECNO() | column | (expression) | -factor

    public ExpressionNode parseExpression() throws ParseException {
        ExpressionNode left = parseAndExpression();
        while (is(TokenType.OR)) {
            Token op = currentToken;
            advance();
            ExpressionNode node = new ExpressionNode(op);
            node.setLeft(left);
            node.setRight(parseAndExpression());
            left = node;
        }
        return left;
    }

    private ExpressionNode parseAndExpression() throws ParseException {
        ExpressionNode left = parseNotExpression();
        while (is(TokenType.AND)) {
            Token op = currentToken;
            advance();
            ExpressionNode node = new ExpressionNode(op);
            node.setLeft(left);
            node.setRight(parseNotExpression());
            left = node;
        }
        return left;
    }

    private ExpressionNode parseNotExpression() throws ParseException {
        if (is(TokenType.NOT)) {
            Token op = currentToken;
            advance();
            ExpressionNode node = new ExpressionNode(op);
            node.setLeft(parseNotExpression());
            return node;
        }
        return parsePredicate();
    }

    private ExpressionNode parsePredicate() throws ParseException {
        ExpressionNode left = parseAdditive();

        TokenType t = currentToken.getType();
        if (t == TokenType.EQ || t == TokenType.NE || t == TokenType.LT
            || t == TokenType.GT || t == TokenType.LE || t == TokenType.GE) {
            Token op = currentToken;
            advance();
            ExpressionNode node = new ExpressionNode(op);
            node.setLeft(left);
            node.setRight(parseAdditive());
            return node;
        }

        boolean negated = false;
        if (is(TokenType.NOT)) {
            // NOT BETWEEN / NOT LIKE / NOT IN
            advance();
            negated = true;
        }

        if (is(TokenType.BETWEEN)) {
            advance();
            ExpressionNode low = parseAdditive();
            expect(TokenType.AND);
            ExpressionNode high = parseAdditive();

            ExpressionNode ge = new ExpressionNode(syntheticToken(TokenType.GE, ">="));
            ge.setLeft(left);
            ge.setRight(low);
            ExpressionNode le = new ExpressionNode(syntheticToken(TokenType.LE, "<="));
            le.setLeft(left);
            le.setRight(high);
            ExpressionNode and = new ExpressionNode(syntheticToken(TokenType.AND, "AND"));
            and.setLeft(ge);
            and.setRight(le);
            return negate(and, negated);
        }

        if (is(TokenType.LIKE)) {
            Token op = currentToken;
            advance();
            ExpressionNode node = new ExpressionNode(op);
            node.setLeft(left);
            node.setRight(parseAdditive());
            return negate(node, negated);
        }

        if (is(TokenType.IN)) {
            advance();
            expect(TokenType.LPAREN);
            ExpressionNode orChain = null;
            do {
                ExpressionNode eq = new ExpressionNode(syntheticToken(TokenType.EQ, "="));
                eq.setLeft(left);
                eq.setRight(parseAdditive());
                if (orChain == null) {
                    orChain = eq;
                } else {
                    ExpressionNode or = new ExpressionNode(syntheticToken(TokenType.OR, "OR"));
                    or.setLeft(orChain);
                    or.setRight(eq);
                    orChain = or;
                }
            } while (match(TokenType.COMMA));
            expect(TokenType.RPAREN);
            return negate(orChain, negated);
        }

        if (is(TokenType.IS)) {
            advance();
            boolean isNot = match(TokenType.NOT);
            expect(TokenType.NULL);
            ExpressionNode nullLiteral = new ExpressionNode(syntheticToken(TokenType.NULL, null));
            ExpressionNode node = new ExpressionNode(
                syntheticToken(isNot ? TokenType.NE : TokenType.EQ, isNot ? "<>" : "="));
            node.setLeft(left);
            node.setRight(nullLiteral);
            return negate(node, negated);
        }

        if (negated) {
            throw new ParseException("Expected BETWEEN, LIKE or IN after NOT at line "
                + currentToken.getLine() + ", column " + currentToken.getColumn());
        }
        return left;
    }

    private ExpressionNode negate(ExpressionNode node, boolean negated) {
        if (!negated) return node;
        ExpressionNode not = new ExpressionNode(syntheticToken(TokenType.NOT, "NOT"));
        not.setLeft(node);
        return not;
    }

    private ExpressionNode parseAdditive() throws ParseException {
        ExpressionNode left = parseTerm();
        while (is(TokenType.PLUS) || is(TokenType.MINUS)) {
            Token op = currentToken;
            advance();
            ExpressionNode node = new ExpressionNode(op);
            node.setLeft(left);
            node.setRight(parseTerm());
            left = node;
        }
        return left;
    }

    private ExpressionNode parseTerm() throws ParseException {
        ExpressionNode left = parseFactor();
        while (is(TokenType.STAR) || is(TokenType.MULTIPLY) || is(TokenType.DIVIDE)) {
            // '*' is lexed as STAR; in expression context it means multiply
            Token op = is(TokenType.DIVIDE)
                ? currentToken
                : syntheticToken(TokenType.MULTIPLY, "*");
            advance();
            ExpressionNode node = new ExpressionNode(op);
            node.setLeft(left);
            node.setRight(parseFactor());
            left = node;
        }
        return left;
    }

    private ExpressionNode parseFactor() throws ParseException {
        if (is(TokenType.NUMBER) || is(TokenType.STRING) || is(TokenType.NULL)) {
            ExpressionNode node = new ExpressionNode(currentToken);
            advance();
            return node;
        }

        if (is(TokenType.RECNO)) {
            advance();
            consumeEmptyParens();
            ExpressionNode node = new ExpressionNode(syntheticToken(TokenType.IDENTIFIER, "RECNO"));
            node.setColumnName("RECNO");
            node.setRecno(true);
            return node;
        }

        // Aggregate call inside an expression, e.g. MAX(lote)+1.
        if (isAggregateFunction()) {
            return parseAggregateExpr();
        }

        // SQL:2003 special-grammar expressions (soft keywords).
        if (isIdent("CASE")) {
            return parseCase();
        }
        if (isIdent("CAST")) {
            return parseCast();
        }
        if (isIdent("EXTRACT")) {
            return parseExtract();
        }

        if (is(TokenType.IDENTIFIER)) {
            Token first = currentToken;
            advance();
            // Scalar function call: name( arg {, arg} ) or name()
            if (is(TokenType.LPAREN)) {
                advance();
                java.util.List<ExpressionNode> args = new java.util.ArrayList<>();
                if (!is(TokenType.RPAREN)) {
                    do {
                        args.add(parseExpression());
                    } while (match(TokenType.COMMA));
                }
                expect(TokenType.RPAREN);
                ExpressionNode fn = new ExpressionNode(
                    syntheticToken(TokenType.IDENTIFIER, first.getValue()));
                fn.setFunction(first.getValue().toUpperCase(), args);
                return fn;
            }
            if (match(TokenType.DOT)) {
                Token columnToken = expect(TokenType.IDENTIFIER);
                ExpressionNode node = new ExpressionNode(columnToken);
                node.setTableName(first.getValue());
                node.setColumnName(columnToken.getValue());
                return node;
            }
            // Niladic SQL datetime functions are written without parentheses.
            String upper = first.getValue().toUpperCase();
            if (upper.equals("CURRENT_DATE") || upper.equals("CURRENT_TIME")
                    || upper.equals("CURRENT_TIMESTAMP")) {
                ExpressionNode fn = new ExpressionNode(
                    syntheticToken(TokenType.IDENTIFIER, first.getValue()));
                fn.setFunction(upper, new java.util.ArrayList<>());
                return fn;
            }
            return new ExpressionNode(first);
        }

        if (match(TokenType.LPAREN)) {
            ExpressionNode node = parseExpression();
            expect(TokenType.RPAREN);
            return node;
        }

        if (is(TokenType.MINUS)) {
            advance();
            // Negate by multiplying with -1 so it works with the evaluator
            ExpressionNode minusOne = new ExpressionNode(syntheticToken(TokenType.NUMBER, "-1"));
            ExpressionNode node = new ExpressionNode(syntheticToken(TokenType.MULTIPLY, "*"));
            node.setLeft(minusOne);
            node.setRight(parseFactor());
            return node;
        }

        throw new ParseException("Unexpected token " + currentToken.getType()
            + (currentToken.getValue() != null ? " ('" + currentToken.getValue() + "')" : "")
            + " in expression at line " + currentToken.getLine()
            + ", column " + currentToken.getColumn());
    }

    // ==================== SQL:2003 special-grammar expressions ====================

    /**
     * {@code CAST(expr AS type[(p[,s])])}. Desugared to a CAST(expr, 'TYPE')
     * function call, evaluated by FunctionLibrary.
     */
    private ExpressionNode parseCast() throws ParseException {
        expect(TokenType.IDENTIFIER); // CAST
        expect(TokenType.LPAREN);
        ExpressionNode value = parseExpression();
        if (!isIdent("AS")) {
            throw new ParseException("Expected AS in CAST at line " + currentToken.getLine());
        }
        advance();
        StringBuilder type = new StringBuilder(expect(TokenType.IDENTIFIER).getValue());
        // Allow multi-word types like "DOUBLE PRECISION".
        while (is(TokenType.IDENTIFIER)) {
            type.append(' ').append(currentToken.getValue());
            advance();
        }
        // Skip an optional length/scale, e.g. VARCHAR(20) or NUMERIC(10,2).
        if (match(TokenType.LPAREN)) {
            while (!is(TokenType.RPAREN) && !is(TokenType.EOF)) {
                advance();
            }
            expect(TokenType.RPAREN);
        }
        expect(TokenType.RPAREN);

        java.util.List<ExpressionNode> args = new java.util.ArrayList<>();
        args.add(value);
        args.add(new ExpressionNode((Object) type.toString().toUpperCase()));
        ExpressionNode fn = new ExpressionNode(syntheticToken(TokenType.IDENTIFIER, "CAST"));
        fn.setFunction("CAST", args);
        return fn;
    }

    /**
     * {@code EXTRACT(field FROM expr)}. Desugared to the matching scalar
     * function (YEAR/MONTH/DAY/HOUR/MINUTE/SECOND/QUARTER/WEEK/DOW/DOY).
     */
    private ExpressionNode parseExtract() throws ParseException {
        expect(TokenType.IDENTIFIER); // EXTRACT
        expect(TokenType.LPAREN);
        String field = expect(TokenType.IDENTIFIER).getValue().toUpperCase();
        expect(TokenType.FROM);
        ExpressionNode src = parseExpression();
        expect(TokenType.RPAREN);

        String fn;
        switch (field) {
            case "YEAR": fn = "YEAR"; break;
            case "MONTH": fn = "MONTH"; break;
            case "DAY": fn = "DAY"; break;
            case "HOUR": fn = "HOUR"; break;
            case "MINUTE": fn = "MINUTE"; break;
            case "SECOND": fn = "SECOND"; break;
            case "QUARTER": fn = "QUARTER"; break;
            case "WEEK": fn = "WEEK"; break;
            case "DOW": case "DAYOFWEEK": fn = "DAYOFWEEK"; break;
            case "DOY": case "DAYOFYEAR": fn = "DAYOFYEAR"; break;
            default:
                throw new ParseException("Unsupported EXTRACT field: " + field);
        }
        java.util.List<ExpressionNode> args = new java.util.ArrayList<>();
        args.add(src);
        ExpressionNode node = new ExpressionNode(syntheticToken(TokenType.IDENTIFIER, fn));
        node.setFunction(fn, args);
        return node;
    }

    /**
     * Searched {@code CASE WHEN c THEN r ... [ELSE e] END} and simple
     * {@code CASE x WHEN v THEN r ... [ELSE e] END}. Desugared to nested
     * IIF(cond, then, rest) calls so no new evaluator support is needed.
     */
    private ExpressionNode parseCase() throws ParseException {
        expect(TokenType.IDENTIFIER); // CASE
        ExpressionNode operand = null;
        if (!isIdent("WHEN")) {
            operand = parseExpression(); // simple CASE: operand compared to each WHEN value
        }

        java.util.List<ExpressionNode> conds = new java.util.ArrayList<>();
        java.util.List<ExpressionNode> results = new java.util.ArrayList<>();
        while (isIdent("WHEN")) {
            advance();
            ExpressionNode when = parseExpression();
            if (!isIdent("THEN")) {
                throw new ParseException("Expected THEN in CASE at line " + currentToken.getLine());
            }
            advance();
            results.add(parseExpression());
            conds.add(operand == null ? when : equals(operand, when));
        }
        if (conds.isEmpty()) {
            throw new ParseException("CASE requires at least one WHEN");
        }

        ExpressionNode elseExpr;
        if (isIdent("ELSE")) {
            advance();
            elseExpr = parseExpression();
        } else {
            elseExpr = new ExpressionNode(syntheticToken(TokenType.NULL, null));
        }
        if (!isIdent("END")) {
            throw new ParseException("Expected END in CASE at line " + currentToken.getLine());
        }
        advance();

        // Fold right-to-left into nested IIFs.
        ExpressionNode result = elseExpr;
        for (int i = conds.size() - 1; i >= 0; i--) {
            java.util.List<ExpressionNode> args = new java.util.ArrayList<>();
            args.add(conds.get(i));
            args.add(results.get(i));
            args.add(result);
            ExpressionNode iif = new ExpressionNode(syntheticToken(TokenType.IDENTIFIER, "IIF"));
            iif.setFunction("IIF", args);
            result = iif;
        }
        return result;
    }

    /** Builds an {@code a = b} comparison node (for simple CASE). */
    private ExpressionNode equals(ExpressionNode a, ExpressionNode b) {
        ExpressionNode eq = new ExpressionNode(syntheticToken(TokenType.EQ, "="));
        eq.setLeft(a);
        eq.setRight(b);
        return eq;
    }

    public static class ParseException extends Exception {
        public ParseException(String message) {
            super(message);
        }
    }
}
