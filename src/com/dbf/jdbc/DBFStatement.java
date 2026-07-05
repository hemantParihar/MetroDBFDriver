package com.dbf.jdbc;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import com.dbf.jdbc.dbf.DBFReader;
import com.dbf.jdbc.dbf.DBFWriter;
import com.dbf.jdbc.error.SQLExceptionFactory;
import com.dbf.jdbc.execution.AggregateQueryExecutor;
import com.dbf.jdbc.execution.streaming.ExpressionFilterOperator;
import com.dbf.jdbc.execution.streaming.ExpressionProjectionOperator;
import com.dbf.jdbc.execution.streaming.ExternalMergeSortOperator;
import com.dbf.jdbc.execution.streaming.ProjectedReaderStream;
import com.dbf.jdbc.execution.streaming.RowStream;
import com.dbf.jdbc.execution.streaming.StreamingTableScanOperator;
import com.dbf.jdbc.join.GraceHashJoinOperator;
import com.dbf.jdbc.join.HashJoinOperator;
import com.dbf.jdbc.parser.Parser;
import com.dbf.jdbc.parser.ast.JoinNode;
import com.dbf.jdbc.parser.ast.SelectNode;
import com.dbf.jdbc.resultset.FilterEngine;

public class DBFStatement implements Statement {
    protected DBFConnection connection;
    protected DBFResultSet currentResultSet;
    protected List<String> batchCommands = new ArrayList<>();
    protected boolean closed = false;
    protected int fetchSize = 100;
    protected int maxRows = 0;
    protected int queryTimeout = 0;
    protected boolean escapeProcessing = true;
    protected SQLWarning warnings = null;
    protected List<Integer> batchResults = new ArrayList<>();
    /** RECNOs of rows inserted by the most recent INSERT (or batch). */
    protected final List<Integer> lastGeneratedKeys = new ArrayList<>();
    
    public DBFStatement(DBFConnection connection) {
        this.connection = connection;
    }
    
 // Update the executeQuery method in DBFStatement.java to use QueryExecutor

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        checkClosed();
        closeCurrentResultSet();

        try {
            // Parse SQL
            Parser parser = new Parser(new StringReader(sql));
            SelectNode selectNode = parser.parseSelect();

            // Open DBF file
            String tableName = selectNode.getFrom().getTableName();
            String filePath = connection.getPath() + "/" + tableName + ".dbf";

            DBFReader reader = new DBFReader(filePath, getCharset());
            try {
                currentResultSet = buildResultSet(reader, selectNode);
            } catch (IOException | SQLException | RuntimeException e) {
                try {
                    reader.close();
                } catch (IOException suppressed) {
                    // The original failure is more useful than the close error
                }
                throw e;
            }

            currentResultSet.setFetchSize(fetchSize);
            // Apply SQL row limit (TOP n / LIMIT n), honouring any tighter
            // Statement.setMaxRows() cap as well.
            int effectiveLimit = selectNode.getLimit();
            if (maxRows > 0) {
                effectiveLimit = effectiveLimit < 0
                    ? maxRows : Math.min(effectiveLimit, maxRows);
            }
            if (effectiveLimit >= 0) {
                currentResultSet.setLimit(effectiveLimit);
            }
            return currentResultSet;

        } catch (IOException e) {
            throw new SQLException("Error executing query: " + e.getMessage(), e);
        } catch (Parser.ParseException e) {
            throw new SQLException("SQL parse error: " + e.getMessage(), e);
        }
    }

    private DBFResultSet buildResultSet(DBFReader reader, SelectNode selectNode)
            throws IOException, SQLException {
        boolean hasJoin = !selectNode.getJoins().isEmpty();
        boolean hasAggregation = selectNode.hasAggregates() || selectNode.getGroupBy() != null;
        boolean hasComputed = hasComputedSelectItems(selectNode);
        boolean hasExprOrderBy = hasExpressionOrderBy(selectNode);

        if (hasAggregation) {
            if (hasJoin) {
                // Aggregate over the joined row stream: scans -> joins feed the
                // aggregator, which applies WHERE/GROUP BY/HAVING/ORDER BY.
                return buildJoinedAggregate(reader, selectNode);
            }
            // Aggregates are computed in one pass; the result is tiny
            // (one row per group), so the reader can be closed right away.
            RowStream aggregateStream = AggregateQueryExecutor.execute(reader, selectNode);
            reader.close();
            return new DBFResultSet(aggregateStream, null);
        }

        // Fast path: a single-table WHERE that a Clipper .NTX index can seek.
        // Falls through to a full scan when no usable index/predicate exists.
        if (!hasJoin && connection.isIndexReadEnabled() && selectNode.getWhere() != null) {
            DBFResultSet indexed = tryIndexSeek(reader, selectNode);
            if (indexed != null) {
                return indexed;
            }
        }

        if (hasJoin || hasComputed || hasExprOrderBy) {
            // General pipeline: scan(s) -> joins -> WHERE -> ORDER BY -> project
            return buildExpressionPipeline(reader, selectNode);
        }

        if (selectNode.getOrderBy() != null) {
            // ORDER BY uses an external merge sort: bounded memory,
            // spills sorted runs to disk for large inputs
            ProjectedReaderStream scan = new ProjectedReaderStream(reader, selectNode);
            RowStream sortedStream = new ExternalMergeSortOperator(
                scan, selectNode.getOrderBy(), scan.getColumnNames(), effectiveLimit(selectNode));
            return new DBFResultSet(sortedStream, null);
        }

        // Regular streaming query with possible WHERE
        return new DBFResultSet(reader, selectNode);
    }

    private static boolean hasComputedSelectItems(SelectNode selectNode) {
        for (com.dbf.jdbc.parser.ast.ColumnNode col : selectNode.getColumns()) {
            if (col.isExpression()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasExpressionOrderBy(SelectNode selectNode) {
        if (selectNode.getOrderBy() == null) {
            return false;
        }
        for (com.dbf.jdbc.parser.ast.OrderByNode.OrderItem item
                : selectNode.getOrderBy().getItems()) {
            if (item.isExpression()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the general query pipeline for queries with joins and/or
     * computed columns: qualified table scans, a left-deep chain of Grace
     * hash joins, a WHERE filter, an optional ORDER BY (external sort over
     * computed keys), and a final expression projection for the SELECT list.
     */
    private DBFResultSet buildExpressionPipeline(DBFReader reader, SelectNode selectNode)
            throws IOException, SQLException {
        List<DBFReader> openedReaders = new ArrayList<>();
        openedReaders.add(reader);
        List<com.dbf.jdbc.dbf.DBFField> combinedFields = new ArrayList<>();
        try {
            RowStream stream =
                buildScanJoinStream(reader, selectNode, openedReaders, combinedFields);

            if (selectNode.getWhere() != null) {
                stream = new ExpressionFilterOperator(stream,
                    selectNode.getWhere().getCondition());
            }

            if (selectNode.getOrderBy() != null) {
                stream = applyOrderBy(stream, selectNode.getOrderBy(), combinedFields,
                    effectiveLimit(selectNode));
            }

            List<com.dbf.jdbc.parser.ast.ColumnNode> items = selectNode.getColumns();
            if (!items.isEmpty()) {
                stream = new ExpressionProjectionOperator(stream, items, combinedFields);
            }

            return new DBFResultSet(stream, null);
        } catch (SQLException | RuntimeException e) {
            closeQuietly(openedReaders);
            throw e;
        }
    }

    /**
     * Tries to answer a single-table query by seeking a Clipper .NTX index
     * instead of scanning. Returns null (and leaves {@code reader} untouched)
     * whenever no usable index/predicate is found, or the seek is not selective
     * enough to be worth the random I/O -- the caller then scans as usual.
     *
     * <p>On the index path the seek hits feed the same WHERE filter / ORDER BY /
     * projection pipeline as a scan, so results are identical. Re-applying the
     * full WHERE also discards any false positives from an out-of-date index.
     */
    private DBFResultSet tryIndexSeek(DBFReader reader, SelectNode selectNode) {
        com.dbf.jdbc.index.ntx.NtxPlanner.Match match;
        try {
            java.util.List<com.dbf.jdbc.dbf.DBFField> fields = reader.getHeader().getFields();
            String table = selectNode.getFrom().getTableName();
            // Random-access of a large fraction of the file is slower than a
            // sequential scan, so only seek when the index is selective.
            long recordCount = reader.getHeader().getRecordCount();
            int maxHits = (int) Math.max(1, recordCount / 4);
            // TOP/LIMIT, honouring any tighter Statement.setMaxRows() cap, lets the
            // planner stop after N rows when the index order matches ORDER BY.
            int limit = selectNode.getLimit();
            if (maxRows > 0) {
                limit = limit < 0 ? maxRows : Math.min(limit, maxRows);
            }
            match = com.dbf.jdbc.index.ntx.NtxPlanner.plan(connection.getPath(), table,
                fields, selectNode.getWhere().getCondition(),
                selectNode.getOrderBy(), limit, maxHits);
            if (System.getProperty("dbf.indexDebug") != null) {
                System.err.println("[indexDebug] table=" + table + " match="
                    + (match == null ? "none"
                        : match.indexFile + " recnos=" + match.recnos.size()
                            + " ordered=" + match.ordered));
            }
            if (match == null || match.recnos.isEmpty()) {
                return null;
            }
        } catch (RuntimeException e) {
            return null;
        }

        // Committed to the index path: the reader is now owned by the stream.
        try {
            String table = selectNode.getFrom().getTableName();
            String alias = selectNode.getFrom().getAlias();
            List<com.dbf.jdbc.dbf.DBFField> combinedFields =
                new ArrayList<>(reader.getHeader().getFields());

            RowStream stream = new com.dbf.jdbc.execution.streaming.RecnoListScanOperator(
                reader, table, alias, match.recnos);

            if (selectNode.getWhere() != null) {
                stream = new ExpressionFilterOperator(stream,
                    selectNode.getWhere().getCondition());
            }
            // When the index already returns rows in the requested order, skip
            // the sort entirely (the seek even stopped after TOP n rows).
            if (selectNode.getOrderBy() != null && !match.ordered) {
                stream = applyOrderBy(stream, selectNode.getOrderBy(), combinedFields,
                    effectiveLimit(selectNode));
            }
            List<com.dbf.jdbc.parser.ast.ColumnNode> items = selectNode.getColumns();
            if (!items.isEmpty()) {
                stream = new ExpressionProjectionOperator(stream, items, combinedFields);
            }
            return new DBFResultSet(stream, null);
        } catch (SQLException | RuntimeException e) {
            try {
                reader.close();
            } catch (IOException suppressed) {
                // original failure is more informative
            }
            throw new RuntimeException(e);
        }
    }

    /**
     * Aggregate query whose FROM has one or more joins. Builds the scan/join
     * stream and hands it to {@link AggregateQueryExecutor}, which applies the
     * WHERE, GROUP BY, HAVING and ORDER BY over the joined (alias-qualified)
     * rows. The executor consumes and closes the stream (and thus every reader
     * in the chain); we only close manually if building the stream fails first.
     */
    private DBFResultSet buildJoinedAggregate(DBFReader reader, SelectNode selectNode)
            throws IOException, SQLException {
        List<DBFReader> openedReaders = new ArrayList<>();
        openedReaders.add(reader);
        List<com.dbf.jdbc.dbf.DBFField> combinedFields = new ArrayList<>();
        RowStream stream;
        try {
            stream = buildScanJoinStream(reader, selectNode, openedReaders, combinedFields);
        } catch (SQLException | RuntimeException e) {
            closeQuietly(openedReaders);
            throw e;
        }
        RowStream aggregated =
            AggregateQueryExecutor.execute(stream, combinedFields, selectNode);
        return new DBFResultSet(aggregated, null);
    }

    /**
     * Builds the left-deep scan/join stream for a multi-table FROM: a qualified
     * base scan followed by a chain of Grace hash joins. Appends each opened
     * reader to {@code openedReaders} and each source's fields to
     * {@code combinedFields} (aligned to the joined column order).
     */
    private RowStream buildScanJoinStream(DBFReader reader, SelectNode selectNode,
            List<DBFReader> openedReaders,
            List<com.dbf.jdbc.dbf.DBFField> combinedFields)
            throws IOException, SQLException {
        String baseTable = selectNode.getFrom().getTableName();
        String baseAlias = selectNode.getFrom().getAlias();

        // Column pruning: decode only the fields the query references. null means
        // a "*" select item is present -> decode every column (no pruning).
        java.util.Set<String> neededColumns = collectNeededColumnNames(selectNode);

        StreamingTableScanOperator baseScan =
            new StreamingTableScanOperator(reader, baseTable, baseAlias, neededColumns);
        RowStream stream = baseScan;
        combinedFields.addAll(baseScan.getSourceFields());

        // Predicate pushdown: apply WHERE conjuncts that reference ONLY the base
        // (driving) table at the scan, before any join. The base table is the
        // preserved side of the left-deep join tree, so filtering it early never
        // changes the result -- but it can collapse a multi-million-row join down
        // to the handful of rows that match (e.g. SALES.V_NO=1129 AND
        // SALES.TYPE='A'). The full WHERE is still applied above the join, so
        // pushed conjuncts are merely a (large) speed-up, not a semantic change.
        if (!selectNode.getJoins().isEmpty() && selectNode.getWhere() != null) {
            List<com.dbf.jdbc.parser.ast.ExpressionNode> conjuncts = new ArrayList<>();
            collectAndConjuncts(selectNode.getWhere().getCondition(), conjuncts);
            List<com.dbf.jdbc.parser.ast.ExpressionNode> pushable = new ArrayList<>();
            for (com.dbf.jdbc.parser.ast.ExpressionNode c : conjuncts) {
                if (referencesOnlyTable(c, baseTable, baseAlias)) {
                    pushable.add(c);
                }
            }
            if (!pushable.isEmpty()) {
                stream = new ExpressionFilterOperator(stream, andAll(pushable));
            }
        }

        for (JoinNode join : selectNode.getJoins()) {
            String rightTable = join.getRightTable();
            String rightAlias = join.getRightTableAlias();
            String rightPath = connection.getPath() + "/" + rightTable + ".dbf";
            DBFReader rightReader = new DBFReader(rightPath, getCharset());
            openedReaders.add(rightReader);

            StreamingTableScanOperator rightScan =
                new StreamingTableScanOperator(rightReader, rightTable, rightAlias, neededColumns);

            String[][] keys = resolveJoinKeys(join.getCondition(),
                stream.getColumnNames(), rightScan.getColumnNames());

            stream = new GraceHashJoinOperator(stream, rightScan, keys[0], keys[1],
                HashJoinOperator.JoinType.valueOf(join.getJoinType().name()));
            combinedFields.addAll(rightScan.getSourceFields());
        }
        return stream;
    }

    private static void closeQuietly(List<DBFReader> readers) {
        for (DBFReader r : readers) {
            try {
                r.close();
            } catch (IOException suppressed) {
                // The original failure is more informative
            }
        }
    }

    /**
     * Resolves an equi-join ON condition to the (qualified) key column names on
     * each side. Supports a single equality or several equalities combined with
     * AND ({@code a.x=b.x AND a.y=b.y}); either operand of each equality may
     * name either side. Returns {@code {leftKeys[], rightKeys[]}}, aligned.
     */
    private String[][] resolveJoinKeys(com.dbf.jdbc.parser.ast.ExpressionNode condition,
            String[] leftColumns, String[] rightColumns) throws SQLException {
        List<com.dbf.jdbc.parser.ast.ExpressionNode> equalities = new ArrayList<>();
        collectAndConjuncts(condition, equalities);
        if (equalities.isEmpty()) {
            throw new SQLException("Missing join condition in ON");
        }

        List<String> lefts = new ArrayList<>();
        List<String> rights = new ArrayList<>();
        for (com.dbf.jdbc.parser.ast.ExpressionNode eq : equalities) {
            if (eq.getType() != com.dbf.jdbc.parser.TokenType.EQ) {
                throw new SQLException("Only equi-join conditions (col = col, "
                    + "optionally AND-combined) are supported in ON");
            }
            com.dbf.jdbc.parser.ast.ExpressionNode a = eq.getLeft();
            com.dbf.jdbc.parser.ast.ExpressionNode b = eq.getRight();

            String aLeft = matchColumn(a, leftColumns);
            String bRight = matchColumn(b, rightColumns);
            if (aLeft != null && bRight != null) {
                lefts.add(aLeft);
                rights.add(bRight);
                continue;
            }
            String bLeft = matchColumn(b, leftColumns);
            String aRight = matchColumn(a, rightColumns);
            if (bLeft != null && aRight != null) {
                lefts.add(bLeft);
                rights.add(aRight);
                continue;
            }
            throw new SQLException("Join condition does not reference both tables: "
                + describeRef(a) + " = " + describeRef(b));
        }
        return new String[][] {
            lefts.toArray(new String[0]), rights.toArray(new String[0])
        };
    }

    /** Flattens an AND-tree of join equalities into a list of equality nodes. */
    private static void collectAndConjuncts(com.dbf.jdbc.parser.ast.ExpressionNode n,
            List<com.dbf.jdbc.parser.ast.ExpressionNode> out) {
        if (n == null) {
            return;
        }
        if (n.getType() == com.dbf.jdbc.parser.TokenType.AND) {
            collectAndConjuncts(n.getLeft(), out);
            collectAndConjuncts(n.getRight(), out);
        } else {
            out.add(n);
        }
    }

    /**
     * True if {@code n} references at least one column and every column it
     * references is explicitly qualified to {@code table} (or {@code alias}).
     * Used to decide a WHERE conjunct can be safely pushed onto the base scan.
     * Unqualified references return false (conservative: they might belong to a
     * joined table), so only fully base-qualified predicates are pushed.
     */
    private static boolean referencesOnlyTable(com.dbf.jdbc.parser.ast.ExpressionNode n,
            String table, String alias) {
        List<com.dbf.jdbc.parser.ast.ExpressionNode> cols = new ArrayList<>();
        collectColumns(n, cols);
        if (cols.isEmpty()) {
            return false;
        }
        for (com.dbf.jdbc.parser.ast.ExpressionNode c : cols) {
            String t = c.getTableName();
            if (t == null) {
                return false;
            }
            if (!t.equalsIgnoreCase(table) && (alias == null || !t.equalsIgnoreCase(alias))) {
                return false;
            }
        }
        return true;
    }

    /** Collects every column reference (excluding RECNO) in an expression tree. */
    private static void collectColumns(com.dbf.jdbc.parser.ast.ExpressionNode n,
            List<com.dbf.jdbc.parser.ast.ExpressionNode> out) {
        if (n == null) {
            return;
        }
        if (n.isColumn()) {
            if (!n.isRecno()) {
                out.add(n);
            }
            return;
        }
        if (n.isAggregate()) {
            collectColumns(n.getAggregateArg(), out);
        }
        collectColumns(n.getLeft(), out);
        collectColumns(n.getRight(), out);
        if (n.getArguments() != null) {
            for (com.dbf.jdbc.parser.ast.ExpressionNode a : n.getArguments()) {
                collectColumns(a, out);
            }
        }
    }

    /**
     * The set of column NAMES (upper-cased, unqualified) referenced anywhere in
     * the query -- select list, WHERE, every JOIN ON, GROUP BY, HAVING and ORDER
     * BY -- so a scan can decode only those fields. Returns {@code null} when a
     * {@code *} select item is present (then every column must be decoded).
     *
     * <p>Intentionally ignores table qualifiers: a name needed for one table is
     * also decoded on any other table that happens to share it. That over-decodes
     * a little but is always correct, and avoids fragile per-table attribution.
     */
    private static java.util.Set<String> collectNeededColumnNames(SelectNode sel) {
        java.util.Set<String> names = new java.util.HashSet<>();
        List<com.dbf.jdbc.parser.ast.ExpressionNode> refs = new ArrayList<>();

        if (sel.getSelectItems() != null) {
            for (com.dbf.jdbc.parser.ast.ASTNode item : sel.getSelectItems()) {
                if (item instanceof com.dbf.jdbc.parser.ast.ColumnNode) {
                    com.dbf.jdbc.parser.ast.ColumnNode col = (com.dbf.jdbc.parser.ast.ColumnNode) item;
                    if (col.isStar()) return null;
                    if (col.isExpression()) collectColumns(col.getExpression(), refs);
                    else if (col.getColumnName() != null) names.add(col.getColumnName().toUpperCase());
                } else if (item instanceof com.dbf.jdbc.parser.ast.AggregateNode) {
                    collectColumns(((com.dbf.jdbc.parser.ast.AggregateNode) item).getArgument(), refs);
                }
            }
        }
        if (sel.getColumns() != null) {
            for (com.dbf.jdbc.parser.ast.ColumnNode col : sel.getColumns()) {
                if (col.isStar()) return null;
                if (col.isExpression()) collectColumns(col.getExpression(), refs);
                else if (col.getColumnName() != null) names.add(col.getColumnName().toUpperCase());
            }
        }
        if (sel.getWhere() != null) collectColumns(sel.getWhere().getCondition(), refs);
        if (sel.getJoins() != null) {
            for (JoinNode j : sel.getJoins()) collectColumns(j.getCondition(), refs);
        }
        if (sel.getGroupBy() != null) {
            for (com.dbf.jdbc.parser.ast.ExpressionNode k : sel.getGroupBy().getKeys()) {
                collectColumns(k, refs);
            }
        }
        if (sel.getHaving() != null) collectColumns(sel.getHaving().getCondition(), refs);
        if (sel.getOrderBy() != null) {
            for (com.dbf.jdbc.parser.ast.OrderByNode.OrderItem oi : sel.getOrderBy().getItems()) {
                if (oi.isAggregate()) collectColumns(oi.getAggregate().getArgument(), refs);
                else if (oi.isExpression()) collectColumns(oi.getExpression(), refs);
                else if (oi.getColumnName() != null) names.add(oi.getColumnName().toUpperCase());
            }
        }

        for (com.dbf.jdbc.parser.ast.ExpressionNode c : refs) {
            if (c.getColumnName() != null) names.add(c.getColumnName().toUpperCase());
        }
        return names;
    }

    /** Combines conjuncts with AND into a single expression (left-associative). */
    private static com.dbf.jdbc.parser.ast.ExpressionNode andAll(
            List<com.dbf.jdbc.parser.ast.ExpressionNode> list) {
        com.dbf.jdbc.parser.ast.ExpressionNode acc = list.get(0);
        for (int i = 1; i < list.size(); i++) {
            com.dbf.jdbc.parser.ast.ExpressionNode and =
                new com.dbf.jdbc.parser.ast.ExpressionNode(
                    new com.dbf.jdbc.parser.Token(com.dbf.jdbc.parser.TokenType.AND, "AND", 0, 0));
            and.setLeft(acc);
            and.setRight(list.get(i));
            acc = and;
        }
        return acc;
    }

    private static String matchColumn(com.dbf.jdbc.parser.ast.ExpressionNode ref,
            String[] columns) {
        if (ref == null || !ref.isColumn()) return null;
        String table = ref.getTableName();
        String col = ref.getColumnName();
        // Exact qualified match first
        if (table != null) {
            String want = (table + "." + col).toUpperCase();
            for (String c : columns) {
                if (c.equalsIgnoreCase(want)) return c;
            }
            return null;
        }
        // Unqualified: match by column-name suffix
        for (String c : columns) {
            int dot = c.lastIndexOf('.');
            String suffix = dot >= 0 ? c.substring(dot + 1) : c;
            if (suffix.equalsIgnoreCase(col)) return c;
        }
        return null;
    }

    private static String describeRef(com.dbf.jdbc.parser.ast.ExpressionNode ref) {
        if (ref == null) return "?";
        return (ref.getTableName() != null ? ref.getTableName() + "." : "")
            + (ref.getColumnName() != null ? ref.getColumnName() : ref.getValue());
    }

    /**
     * Attaches ORDER BY sort keys as extra columns, sorts with the external
     * merge sort, and leaves the original columns in place for the final
     * projection.
     */
    private RowStream applyOrderBy(RowStream stream,
            com.dbf.jdbc.parser.ast.OrderByNode orderBy,
            List<com.dbf.jdbc.dbf.DBFField> combinedFields, int limit) throws SQLException {
        List<com.dbf.jdbc.parser.ast.ExpressionNode> keyExprs = new ArrayList<>();
        List<String> keyNames = new ArrayList<>();
        com.dbf.jdbc.parser.ast.OrderByNode keyOrder =
            new com.dbf.jdbc.parser.ast.OrderByNode();

        int i = 0;
        for (com.dbf.jdbc.parser.ast.OrderByNode.OrderItem item : orderBy.getItems()) {
            String keyName = "__ORDERKEY" + i++;
            com.dbf.jdbc.parser.ast.ExpressionNode expr;
            if (item.isExpression()) {
                expr = item.getExpression();
            } else {
                // Plain column: build a column reference expression
                expr = new com.dbf.jdbc.parser.ast.ExpressionNode(
                    new com.dbf.jdbc.parser.Token(com.dbf.jdbc.parser.TokenType.IDENTIFIER,
                        item.getColumnName(), 0, 0));
                expr.setColumnName(item.getColumnName());
            }
            keyExprs.add(expr);
            keyNames.add(keyName);
            keyOrder.addItem(new com.dbf.jdbc.parser.ast.OrderByNode.OrderItem(
                keyName, item.isAscending()));
        }

        ExpressionProjectionOperator augmented =
            new ExpressionProjectionOperator(stream, combinedFields, keyExprs, keyNames);
        return new ExternalMergeSortOperator(augmented, keyOrder, augmented.getColumnNames(), limit);
    }

    /**
     * Effective row cap for a query: the SQL TOP n / LIMIT n, tightened by any
     * {@link #setMaxRows(int)}. Returns &lt;= 0 when there is no cap. Lets the
     * sort use a bounded Top-N heap instead of a full sort.
     */
    private int effectiveLimit(SelectNode selectNode) {
        int lim = selectNode.getLimit();
        if (maxRows > 0) {
            lim = lim < 0 ? maxRows : Math.min(lim, maxRows);
        }
        return lim;
    }
    
    private Charset getCharset() {
        String charsetName = connection.getCharset();
        if (charsetName == null || charsetName.isEmpty()) {
            return Charset.forName("UTF-8");
        }
        try {
            return Charset.forName(charsetName);
        } catch (Exception e) {
            return Charset.forName("UTF-8");
        }
    }
    
    @Override
    public int executeUpdate(String sql) throws SQLException {
        checkClosed();
        lastGeneratedKeys.clear();

        String upperSql = sql.trim().toUpperCase();

        if (upperSql.startsWith("INSERT")) {
            return executeInsert(sql);
        } else if (upperSql.startsWith("UPDATE")) {
            return executeUpdateStatement(sql);
        } else if (upperSql.startsWith("DELETE")) {
            return executeDelete(sql);
        } else if (upperSql.startsWith("CREATE")) {
            return executeCreateTable(sql);
        } else if (upperSql.startsWith("DROP")) {
            return executeDropTable(sql);
        } else if (upperSql.startsWith("ALTER")) {
            return executeAlterTable(sql);
        }

        throw new SQLException("Unsupported SQL statement: " + sql);
    }

    // ==================== CREATE / DROP TABLE ====================

    /**
     * CREATE TABLE name (col TYPE[(p[,s])], ...). The file is created in
     * dBASE III PLUS format (0x03), or 0x83 with a .DBT memo file when a
     * MEMO column is declared.
     */
    private int executeCreateTable(String sql) throws SQLException {
        String upper = sql.toUpperCase();
        int tablePos = upper.indexOf("TABLE");
        if (tablePos < 0) {
            throw new SQLException("Only CREATE TABLE is supported: " + sql);
        }
        int parenPos = sql.indexOf('(', tablePos);
        if (parenPos < 0 || !sql.trim().endsWith(")")) {
            throw new SQLException("CREATE TABLE requires a column list: " + sql);
        }

        String tableName = sql.substring(tablePos + 5, parenPos).trim();
        boolean ifNotExists = false;
        if (tableName.toUpperCase().startsWith("IF NOT EXISTS")) {
            ifNotExists = true;
            tableName = tableName.substring("IF NOT EXISTS".length()).trim();
        }
        if (tableName.isEmpty()) {
            throw new SQLException("Missing table name in CREATE TABLE");
        }

        String filePath = connection.getPath() + "/" + tableName + ".dbf";
        if (new java.io.File(filePath).exists()) {
            if (ifNotExists) return 0;
            throw new SQLException("Table already exists: " + tableName);
        }

        String columnsPart = sql.substring(parenPos + 1, sql.lastIndexOf(')'));
        List<com.dbf.jdbc.dbf.DBFField> fields = new ArrayList<>();
        for (String def : splitTopLevel(columnsPart)) {
            fields.add(parseColumnDefinition(def.trim()));
        }
        if (fields.isEmpty()) {
            throw new SQLException("CREATE TABLE requires at least one column");
        }

        try {
            DBFWriter.createTable(filePath, fields, getCharset());
        } catch (IOException e) {
            throw writeError("Create table", e);
        }
        return 0;
    }

    /** Maps a SQL column definition to a dBASE III field. */
    private com.dbf.jdbc.dbf.DBFField parseColumnDefinition(String def) throws SQLException {
        // name TYPE[(p[,s])]
        int space = def.indexOf(' ');
        if (space < 0) {
            throw new SQLException("Malformed column definition: " + def);
        }
        String name = def.substring(0, space).trim();
        String typePart = def.substring(space + 1).trim();

        validateColumnName(name);

        String typeName = typePart;
        int precision = -1;
        int scale = 0;
        int p = typePart.indexOf('(');
        if (p >= 0) {
            typeName = typePart.substring(0, p).trim();
            int close = typePart.indexOf(')', p);
            if (close < 0) {
                throw new SQLException("Malformed type in column definition: " + def);
            }
            String[] args = typePart.substring(p + 1, close).split(",");
            try {
                precision = Integer.parseInt(args[0].trim());
                if (args.length > 1) {
                    scale = Integer.parseInt(args[1].trim());
                }
            } catch (NumberFormatException e) {
                throw new SQLException("Invalid precision/scale in: " + def);
            }
        }

        // The driver writes dBASE III files; validate against its real
        // format limits, sourced from the central version table.
        final com.dbf.jdbc.dbf.DbfVersion version = com.dbf.jdbc.dbf.DbfVersion.DBASE_3;

        char dbfType;
        int length;
        int decimals = 0;
        switch (typeName.toUpperCase()) {
            case "CHAR":
            case "CHARACTER":
            case "VARCHAR":
                int maxChar = version.maxCharLength();
                if (precision == 0 || precision > maxChar) {
                    throw new SQLException("Invalid length for " + name + " " + typeName
                        + "(" + precision + "): must be 1.." + maxChar + " in "
                        + version.displayName(), "42000");
                }
                dbfType = 'C';
                length = precision > 0 ? precision : 30;
                break;
            case "NUMERIC":
            case "DECIMAL":
            case "NUMBER":
                int maxNum = version.maxNumberLength();
                if (precision == 0 || precision > maxNum) {
                    throw new SQLException("Invalid precision for " + name + " " + typeName
                        + "(" + precision + "): must be 1.." + maxNum + " in "
                        + version.displayName(), "42000");
                }
                if (scale < 0 || (scale > 0 && precision > 0 && scale > precision - 2)) {
                    throw new SQLException("Invalid scale for " + name + " " + typeName
                        + "(" + precision + "," + scale + "): scale must leave room "
                        + "for the integer part and decimal point", "42000");
                }
                dbfType = 'N';
                length = precision > 0 ? precision : 10;
                decimals = scale;
                break;
            case "INT":
            case "INTEGER":
                dbfType = 'N';
                length = 10;
                break;
            case "SMALLINT":
                dbfType = 'N';
                length = 6;
                break;
            case "BIGINT":
                dbfType = 'N';
                length = 18;
                break;
            case "DOUBLE":
            case "FLOAT":
            case "REAL":
                dbfType = 'N';
                length = 18;
                decimals = 5;
                break;
            case "DATE":
                dbfType = 'D';
                length = 8;
                break;
            case "BOOLEAN":
            case "LOGICAL":
            case "BIT":
                dbfType = 'L';
                length = 1;
                break;
            case "MEMO":
            case "TEXT":
            case "CLOB":
            case "LONGVARCHAR":
                dbfType = 'M';
                length = 10;
                break;
            default:
                throw new SQLException("Unsupported column type: " + typeName);
        }

        if (!version.supportsType(dbfType)) {
            throw new SQLException("Field type '" + dbfType + "' is not supported by "
                + version.displayName(), "42000");
        }

        com.dbf.jdbc.dbf.DBFField field = new com.dbf.jdbc.dbf.DBFField();
        field.setName(name.toUpperCase());
        field.setType(dbfType);
        field.setLength(length);
        field.setDecimalCount(decimals);
        return field;
    }

    /** dBASE column name rules: max 10 chars, letter first, then letters/digits/underscore. */
    private static void validateColumnName(String name) throws SQLException {
        if (name.length() > 10) {
            throw new SQLException("Column name '" + name
                + "' exceeds the dBASE III limit of 10 characters");
        }
        if (!name.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new SQLException("Invalid column name '" + name
                + "': must start with a letter and contain only letters, digits and underscore");
        }
    }

    // ==================== ALTER TABLE ====================

    /**
     * Supported forms:
     *   ALTER TABLE t ADD [COLUMN] col TYPE[(p[,s])]
     *   ALTER TABLE t DROP [COLUMN] col
     *   ALTER TABLE t RENAME COLUMN old TO new
     *   ALTER TABLE t RENAME TO newname
     *
     * ADD and DROP rebuild the file (DBF records are fixed-width), keeping
     * record numbers - deleted rows are copied through still flagged deleted.
     * Renames touch only the header / file names.
     */
    private int executeAlterTable(String sql) throws SQLException {
        String trimmed = sql.trim();
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        String upper = trimmed.toUpperCase();
        int tablePos = upper.indexOf("TABLE");
        if (tablePos < 0) {
            throw new SQLException("Only ALTER TABLE is supported: " + sql);
        }

        String rest = trimmed.substring(tablePos + 5).trim();
        int space = indexOfWhitespace(rest);
        if (space < 0) {
            throw new SQLException("Missing action in ALTER TABLE: " + sql);
        }
        String tableName = rest.substring(0, space);
        String action = rest.substring(space).trim();
        String actionUpper = action.toUpperCase();

        if (actionUpper.startsWith("ADD")) {
            String def = action.substring(3).trim();
            if (def.toUpperCase().startsWith("COLUMN")) {
                def = def.substring(6).trim();
            }
            return alterAddColumn(tableName, parseColumnDefinition(def));
        }
        if (actionUpper.startsWith("RENAME COLUMN")) {
            String[] parts = action.substring("RENAME COLUMN".length()).trim().split("\\s+");
            if (parts.length != 3 || !"TO".equalsIgnoreCase(parts[1])) {
                throw new SQLException("Expected: ALTER TABLE t RENAME COLUMN old TO new");
            }
            return alterRenameColumn(tableName, parts[0], parts[2]);
        }
        if (actionUpper.startsWith("RENAME TO")) {
            String newName = action.substring("RENAME TO".length()).trim();
            if (newName.isEmpty()) {
                throw new SQLException("Missing new table name in ALTER TABLE RENAME TO");
            }
            return alterRenameTable(tableName, newName);
        }
        if (actionUpper.startsWith("DROP")) {
            String col = action.substring(4).trim();
            if (col.toUpperCase().startsWith("COLUMN")) {
                col = col.substring(6).trim();
            }
            if (col.isEmpty()) {
                throw new SQLException("Missing column name in ALTER TABLE DROP");
            }
            return alterDropColumn(tableName, col);
        }

        throw new SQLException("Unsupported ALTER TABLE action: " + action);
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    private int alterAddColumn(String tableName, com.dbf.jdbc.dbf.DBFField newField)
            throws SQLException {
        List<com.dbf.jdbc.dbf.DBFField> oldFields = readTableFields(tableName);
        for (com.dbf.jdbc.dbf.DBFField f : oldFields) {
            if (f.getName().equalsIgnoreCase(newField.getName())) {
                throw new SQLException("Column already exists: " + newField.getName());
            }
        }

        List<com.dbf.jdbc.dbf.DBFField> newFields = new ArrayList<>();
        int[] sourceIdx = new int[oldFields.size() + 1];
        for (int i = 0; i < oldFields.size(); i++) {
            newFields.add(copyField(oldFields.get(i)));
            sourceIdx[i] = i;
        }
        newFields.add(newField);
        sourceIdx[oldFields.size()] = -1; // new column starts out null

        rebuildTable(tableName, newFields, sourceIdx);
        return 0;
    }

    private int alterDropColumn(String tableName, String columnName) throws SQLException {
        List<com.dbf.jdbc.dbf.DBFField> oldFields = readTableFields(tableName);

        List<com.dbf.jdbc.dbf.DBFField> newFields = new ArrayList<>();
        List<Integer> sources = new ArrayList<>();
        boolean found = false;
        for (int i = 0; i < oldFields.size(); i++) {
            if (oldFields.get(i).getName().equalsIgnoreCase(columnName)) {
                found = true;
                continue;
            }
            newFields.add(copyField(oldFields.get(i)));
            sources.add(i);
        }
        if (!found) {
            throw new SQLException("Column not found: " + columnName);
        }
        if (newFields.isEmpty()) {
            throw new SQLException("Cannot drop the last remaining column of " + tableName);
        }

        int[] sourceIdx = new int[sources.size()];
        for (int i = 0; i < sources.size(); i++) {
            sourceIdx[i] = sources.get(i);
        }
        rebuildTable(tableName, newFields, sourceIdx);
        return 0;
    }

    /** Renaming a column only changes the field descriptor - done in place. */
    private int alterRenameColumn(String tableName, String oldName, String newName)
            throws SQLException {
        validateColumnName(newName);
        String filePath = connection.getPath() + "/" + tableName + ".dbf";
        try (DBFWriter writer = new DBFWriter(filePath, getCharset())) {
            List<com.dbf.jdbc.dbf.DBFField> fields = writer.getHeader().getFields();
            com.dbf.jdbc.dbf.DBFField target = null;
            for (com.dbf.jdbc.dbf.DBFField f : fields) {
                if (f.getName().equalsIgnoreCase(newName)) {
                    throw new SQLException("Column already exists: " + newName);
                }
                if (f.getName().equalsIgnoreCase(oldName)) {
                    target = f;
                }
            }
            if (target == null) {
                throw new SQLException("Column not found: " + oldName);
            }
            target.setName(newName.toUpperCase());
            writer.flushHeader();
        } catch (IOException e) {
            throw new SQLException("Rename column failed: " + e.getMessage(), e);
        }
        return 0;
    }

    private int alterRenameTable(String tableName, String newName) throws SQLException {
        String basePath = connection.getPath() + "/";
        java.io.File source = new java.io.File(basePath + tableName + ".dbf");
        java.io.File target = new java.io.File(basePath + newName + ".dbf");
        if (!source.exists()) {
            throw new SQLException("Table not found: " + tableName);
        }
        if (target.exists()) {
            throw new SQLException("Table already exists: " + newName);
        }
        if (!source.renameTo(target)) {
            throw new SQLException("Could not rename table file: " + source);
        }
        for (String ext : new String[] { ".dbt", ".fpt", ".ndx", ".mdx" }) {
            java.io.File companion = new java.io.File(basePath + tableName + ext);
            if (companion.exists()) {
                companion.renameTo(new java.io.File(basePath + newName + ext));
            }
        }
        return 0;
    }

    private List<com.dbf.jdbc.dbf.DBFField> readTableFields(String tableName) throws SQLException {
        String filePath = connection.getPath() + "/" + tableName + ".dbf";
        try (DBFReader reader = new DBFReader(filePath, getCharset())) {
            return new ArrayList<>(reader.getHeader().getFields());
        } catch (IOException e) {
            throw new SQLException("Cannot open table " + tableName + ": " + e.getMessage(), e);
        }
    }

    private static com.dbf.jdbc.dbf.DBFField copyField(com.dbf.jdbc.dbf.DBFField source) {
        com.dbf.jdbc.dbf.DBFField copy = new com.dbf.jdbc.dbf.DBFField();
        copy.setName(source.getName());
        copy.setType(source.getType());
        copy.setLength(source.getLength());
        copy.setDecimalCount(source.getDecimalCount());
        return copy;
    }

    /**
     * Rewrites the table with a new structure. Every new column takes its
     * value from {@code sourceIdx[i]} in the old row (-1 = null). Rows are
     * copied in chunks, deleted rows stay deleted, so RECNOs are unchanged.
     */
    private void rebuildTable(String tableName, List<com.dbf.jdbc.dbf.DBFField> newFields,
            int[] sourceIdx) throws SQLException {
        String basePath = connection.getPath() + "/";
        String filePath = basePath + tableName + ".dbf";
        String tempName = tableName + "_alt";
        String tempPath = basePath + tempName + ".dbf";

        java.io.File tempDbf = new java.io.File(tempPath);
        java.io.File tempDbt = new java.io.File(basePath + tempName + ".dbt");
        if (tempDbf.exists() || tempDbt.exists()) {
            throw new SQLException("Temporary file already exists: " + tempPath);
        }

        try {
            DBFWriter.createTable(tempPath, newFields, getCharset());

            try (DBFReader reader = new DBFReader(filePath, getCharset());
                 DBFWriter writer = new DBFWriter(tempPath, getCharset())) {

                int oldFieldCount = reader.getHeader().getFieldCount();
                final int chunkSize = 4096;
                List<Object[]> chunk = new ArrayList<>(chunkSize);
                List<Boolean> chunkDeleted = new ArrayList<>(chunkSize);

                reader.beforeFirst();
                while (reader.next()) {
                    Object[] oldRow = readFullRow(reader, oldFieldCount);
                    Object[] newRow = new Object[newFields.size()];
                    for (int i = 0; i < sourceIdx.length; i++) {
                        newRow[i] = sourceIdx[i] >= 0 ? oldRow[sourceIdx[i]] : null;
                    }
                    chunk.add(newRow);
                    chunkDeleted.add(reader.isDeleted());

                    if (chunk.size() >= chunkSize) {
                        copyChunk(writer, chunk, chunkDeleted);
                    }
                }
                if (!chunk.isEmpty()) {
                    copyChunk(writer, chunk, chunkDeleted);
                }
            }

            // Swap with a backup so the original survives a failed rename.
            // Memo text was re-resolved into the new file, so old memo
            // files are replaced wholesale.
            java.io.File original = new java.io.File(filePath);
            java.io.File backupDbf = new java.io.File(basePath + tempName + "_bak.dbf");
            if (!original.renameTo(backupDbf)) {
                throw new SQLException("Could not replace table file: " + filePath);
            }
            java.io.File backupDbt = null;
            for (String ext : new String[] { ".dbt", ".fpt" }) {
                java.io.File memo = new java.io.File(basePath + tableName + ext);
                if (memo.exists()) {
                    backupDbt = new java.io.File(basePath + tempName + "_bak" + ext);
                    memo.renameTo(backupDbt);
                }
            }

            if (!tempDbf.renameTo(original)) {
                // Restore the original before failing
                backupDbf.renameTo(original);
                if (backupDbt != null) {
                    String ext = backupDbt.getName().endsWith(".fpt") ? ".fpt" : ".dbt";
                    backupDbt.renameTo(new java.io.File(basePath + tableName + ext));
                }
                throw new SQLException("Could not move rebuilt table into place: " + tempPath);
            }
            if (tempDbt.exists()) {
                tempDbt.renameTo(new java.io.File(basePath + tableName + ".dbt"));
            }

            backupDbf.delete();
            if (backupDbt != null) {
                backupDbt.delete();
            }
        } catch (IOException e) {
            throw writeError("ALTER TABLE", e);
        } finally {
            // Clean up leftovers from a failed rebuild
            if (tempDbf.exists()) tempDbf.delete();
            if (tempDbt.exists()) tempDbt.delete();
        }
    }

    private void copyChunk(DBFWriter writer, List<Object[]> chunk, List<Boolean> deleted)
            throws IOException {
        int[] recordNumbers = writer.insertRecords(chunk);
        for (int i = 0; i < recordNumbers.length; i++) {
            if (deleted.get(i)) {
                writer.deleteRecord(recordNumbers[i]);
            }
        }
        chunk.clear();
        deleted.clear();
    }

    /** DROP TABLE [IF EXISTS] name - removes the .dbf and companion files. */
    private int executeDropTable(String sql) throws SQLException {
        String upper = sql.toUpperCase();
        int tablePos = upper.indexOf("TABLE");
        if (tablePos < 0) {
            throw new SQLException("Only DROP TABLE is supported: " + sql);
        }
        String tableName = sql.substring(tablePos + 5).trim();
        boolean ifExists = false;
        if (tableName.toUpperCase().startsWith("IF EXISTS")) {
            ifExists = true;
            tableName = tableName.substring("IF EXISTS".length()).trim();
        }
        if (tableName.endsWith(";")) {
            tableName = tableName.substring(0, tableName.length() - 1).trim();
        }

        String basePath = connection.getPath() + "/" + tableName;
        java.io.File dbf = new java.io.File(basePath + ".dbf");
        if (!dbf.exists()) {
            if (ifExists) return 0;
            throw new SQLException("Table not found: " + tableName);
        }
        if (!dbf.delete()) {
            throw new SQLException("Could not delete table file: " + dbf);
        }
        // Companion files are best-effort
        for (String ext : new String[] { ".dbt", ".fpt", ".ndx", ".mdx" }) {
            java.io.File companion = new java.io.File(basePath + ext);
            if (companion.exists()) {
                companion.delete();
            }
        }
        return 0;
    }

    /** Splits on commas that are outside quotes and parentheses. */
    private static List<String> splitTopLevel(String s) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inQuote = false;
        for (char c : s.toCharArray()) {
            if (c == '\'') {
                inQuote = !inQuote;
                current.append(c);
            } else if (!inQuote && c == '(') {
                depth++;
                current.append(c);
            } else if (!inQuote && c == ')') {
                depth--;
                current.append(c);
            } else if (!inQuote && depth == 0 && c == ',') {
                parts.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }
    
    private int executeInsert(String sql) throws SQLException {
        try {
            // Parse INSERT INTO table [(col1, col2)] VALUES (val1, val2)
            String upper = sql.toUpperCase();
            int intoPos = upper.indexOf("INTO") + 4;
            int valuesPos = upper.indexOf("VALUES");
            if (valuesPos < 0) {
                throw new SQLException("INSERT requires a VALUES clause: " + sql);
            }

            String beforeValues = sql.substring(intoPos, valuesPos).trim();
            String tableName;
            String[] columns = null;
            int colParen = beforeValues.indexOf('(');
            if (colParen >= 0) {
                tableName = DBFConnection.normalizeTableName(beforeValues.substring(0, colParen).trim());
                int colClose = beforeValues.lastIndexOf(')');
                String columnsStr = beforeValues.substring(colParen + 1, colClose);
                columns = columnsStr.split(",");
                for (int i = 0; i < columns.length; i++) {
                    columns[i] = unquoteIdentifier(columns[i].trim());
                }
            } else {
                tableName = DBFConnection.normalizeTableName(beforeValues);
            }

            int valuesStart = sql.indexOf("(", valuesPos);
            int valuesEnd = sql.lastIndexOf(")");
            if (valuesStart < 0 || valuesEnd <= valuesStart) {
                throw new SQLException("Malformed VALUES clause: " + sql);
            }
            List<String> valueList = splitCsvRespectingQuotes(
                sql.substring(valuesStart + 1, valuesEnd));

            Object[] values = new Object[valueList.size()];
            for (int i = 0; i < valueList.size(); i++) {
                values[i] = parseValue(valueList.get(i));
            }

            List<Object[]> rows = new ArrayList<>();
            rows.add(values);
            int[] results = executeMappedInsert(tableName, columns, rows);
            maintainIndexesAfterInsert(tableName);
            return results.length;
        } catch (IOException e) {
            throw writeError("Insert", e);
        }
    }

    /**
     * Maps low-level write failures to SQL exceptions; field validation
     * failures become SQLDataExceptions with their proper SQLState.
     */
    protected static SQLException writeError(String operation, IOException e) {
        if (e instanceof com.dbf.jdbc.dbf.DbfValidationException) {
            String state = ((com.dbf.jdbc.dbf.DbfValidationException) e).getSqlState();
            // Class 22 is a data exception; other classes (42xxx syntax,
            // 54xxx program limit) are not, so don't force SQLDataException
            if (state != null && state.startsWith("22")) {
                return new java.sql.SQLDataException(e.getMessage(), state,
                    SQLExceptionFactory.ERROR_DATA_CONVERSION, e);
            }
            return new SQLException(e.getMessage(), state,
                SQLExceptionFactory.ERROR_GENERAL, e);
        }
        return new SQLException(operation + " failed: " + e.getMessage(), e);
    }

    /**
     * Inserts rows whose values are positionally aligned with {@code columns}
     * (or with the table's fields when columns is null), through a single
     * writer. Captures each new row's RECNO for getGeneratedKeys().
     */
    protected int[] executeMappedInsert(String tableName, String[] columns,
            List<Object[]> rows) throws SQLException, IOException {
        String filePath = connection.getPath() + "/" + tableName + ".dbf";
        com.dbf.jdbc.tx.WriteLock lock = connection.beginWrite(tableName);
        try (DBFWriter writer = new DBFWriter(filePath, getCharset())) {
            List<com.dbf.jdbc.dbf.DBFField> fields = writer.getHeader().getFields();

            int[] columnIdx = null;
            if (columns != null) {
                columnIdx = new int[columns.length];
                for (int i = 0; i < columns.length; i++) {
                    columnIdx[i] = findFieldIndex(fields, columns[i]);
                    if (columnIdx[i] < 0) {
                        throw new SQLException("Unknown column in INSERT: " + columns[i]);
                    }
                }
            }

            List<Object[]> mapped = new ArrayList<>(rows.size());
            for (Object[] row : rows) {
                int expected = columnIdx != null ? columnIdx.length : fields.size();
                if (row.length > expected) {
                    throw new SQLException("Too many values: expected " + expected
                        + ", got " + row.length);
                }
                Object[] values = new Object[fields.size()];
                for (int i = 0; i < row.length; i++) {
                    values[columnIdx != null ? columnIdx[i] : i] = row[i];
                }
                mapped.add(values);
            }

            int[] recordNumbers = writer.insertRecords(mapped);
            int[] results = new int[recordNumbers.length];
            for (int i = 0; i < recordNumbers.length; i++) {
                lastGeneratedKeys.add(recordNumbers[i]);
                results[i] = 1;
            }
            return results;
        } finally {
            connection.endWrite(lock);
        }
    }

    protected static List<String> splitCsvRespectingQuotes(String valuesStr) {
        List<String> valueList = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        for (char c : valuesStr.toCharArray()) {
            if (c == '\'') {
                inQuote = !inQuote;
                current.append(c);
            } else if (c == ',' && !inQuote) {
                valueList.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            valueList.add(current.toString().trim());
        }
        return valueList;
    }

    /**
     * Strips identifier quotes from a table/column name in hand-parsed DML:
     * backtick ({@code `name`}), bracket ({@code [name]}) or double-quote
     * ({@code "name"}). Leaves unquoted names unchanged.
     */
    protected static String unquoteIdentifier(String name) {
        if (name == null) {
            return null;
        }
        String n = name.trim();
        if (n.length() >= 2) {
            char a = n.charAt(0);
            char b = n.charAt(n.length() - 1);
            if ((a == '`' && b == '`') || (a == '"' && b == '"') || (a == '[' && b == ']')) {
                return n.substring(1, n.length() - 1).trim();
            }
        }
        return n;
    }

    private static int findFieldIndex(List<com.dbf.jdbc.dbf.DBFField> fields, String name) {
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).getName().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }
    
    /** Result of splitting an UPDATE statement into its top-level clauses. */
    private static final class UpdateClauses {
        final String table;
        final String setClause;
        final String whereClause; // null when no WHERE is present

        UpdateClauses(String table, String setClause, String whereClause) {
            this.table = table;
            this.setClause = setClause;
            this.whereClause = whereClause;
        }
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * Splits {@code UPDATE <table> SET <assignments> [WHERE <condition>]} into
     * its three top-level parts. This is a small hand-written scanner rather
     * than a substring/regex hack: it skips leading whitespace, reads the
     * UPDATE and SET keywords as whole words, and locates the WHERE keyword
     * only when it occurs at the top level (outside string literals and
     * parentheses) and as a complete word -- so a column or value that merely
     * contains the letters "where"/"set" is never mistaken for a keyword. The
     * SET and WHERE clauses are returned as the original substrings so the
     * downstream value parser and {@link Parser} see the text verbatim.
     */
    private static UpdateClauses parseUpdateClauses(String sql) throws SQLException {
        int n = sql.length();
        int i = skipWhitespace(sql, 0);

        i = expectKeyword(sql, i, "UPDATE");
        i = skipWhitespace(sql, i);

        // Table name: a single whitespace-delimited token.
        int tableStart = i;
        while (i < n && !Character.isWhitespace(sql.charAt(i))) {
            i++;
        }
        String table = sql.substring(tableStart, i).trim();
        if (table.isEmpty()) {
            throw new SQLException("Missing table name in UPDATE statement: " + sql);
        }

        i = skipWhitespace(sql, i);
        i = expectKeyword(sql, i, "SET");

        int setStart = i;
        int whereStart = findTopLevelWhere(sql, i);

        String setClause;
        String whereClause;
        if (whereStart >= 0) {
            setClause = sql.substring(setStart, whereStart).trim();
            whereClause = sql.substring(whereStart + "WHERE".length()).trim();
        } else {
            setClause = sql.substring(setStart).trim();
            whereClause = null;
        }
        if (setClause.isEmpty()) {
            throw new SQLException("Missing SET assignments in UPDATE statement: " + sql);
        }
        return new UpdateClauses(table, setClause, whereClause);
    }

    private static int skipWhitespace(String sql, int i) {
        while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
            i++;
        }
        return i;
    }

    /** Consumes the given keyword (case-insensitive) at {@code i}, returning the index after it. */
    private static int expectKeyword(String sql, int i, String keyword) throws SQLException {
        int end = i;
        while (end < sql.length() && isWordChar(sql.charAt(end))) {
            end++;
        }
        if (!sql.substring(i, end).equalsIgnoreCase(keyword)) {
            throw new SQLException("Expected '" + keyword + "' in UPDATE statement: " + sql);
        }
        return end;
    }

    /**
     * Returns the index of the top-level WHERE keyword at or after {@code from},
     * or -1 if there is none. Skips over single/double quoted string literals
     * and parenthesised groups, and only matches WHERE as a whole word.
     */
    private static int findTopLevelWhere(String sql, int from) {
        return findTopLevelKeyword(sql, from, "WHERE");
    }

    /**
     * Returns the index of the given keyword (whole word, case-insensitive) at
     * or after {@code from} that appears at the top level -- outside string
     * literals and parenthesised groups -- or -1 if none.
     */
    private static int findTopLevelKeyword(String sql, int from, String keyword) {
        int n = sql.length();
        int klen = keyword.length();
        int depth = 0;
        int i = from;
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                i = skipQuoted(sql, i);
                continue;
            }
            if (c == '(') {
                depth++;
                i++;
                continue;
            }
            if (c == ')') {
                if (depth > 0) depth--;
                i++;
                continue;
            }
            if (depth == 0 && isWordChar(c)) {
                int wordStart = i;
                while (i < n && isWordChar(sql.charAt(i))) {
                    i++;
                }
                if ((i - wordStart) == klen
                        && sql.regionMatches(true, wordStart, keyword, 0, klen)) {
                    return wordStart;
                }
                continue;
            }
            i++;
        }
        return -1;
    }

    /** Skips a quoted literal starting at the opening quote; returns index past the close. */
    private static int skipQuoted(String sql, int i) {
        char quote = sql.charAt(i);
        int n = sql.length();
        i++; // opening quote
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\\' && i + 1 < n) {
                i += 2; // escaped char
                continue;
            }
            i++;
            if (c == quote) {
                break;
            }
        }
        return i;
    }

    /**
     * Parses an UPDATE SET right-hand side into an expression. It may be a
     * literal ({@code 0}, {@code 'x'}, {@code null}), a column ({@code O_BAL}),
     * or arithmetic over columns ({@code O_BAL + DD_DEBIT - DD_CREDIT}). Falls
     * back to a plain literal value if it can't be parsed as an expression.
     */
    private com.dbf.jdbc.parser.ast.ExpressionNode parseSetExpression(String rhs)
            throws SQLException {
        try {
            return new Parser(new StringReader(rhs)).parseExpression();
        } catch (IOException | Parser.ParseException | RuntimeException e) {
            Object v = parseValue(rhs);
            return v == null
                ? new com.dbf.jdbc.parser.ast.ExpressionNode(
                    new com.dbf.jdbc.parser.Token(com.dbf.jdbc.parser.TokenType.NULL, null, 0, 0))
                : new com.dbf.jdbc.parser.ast.ExpressionNode(v);
        }
    }

    private int executeUpdateStatement(String sql) throws SQLException {
        if (isJoinUpdate(sql)) {
            return executeUpdateJoin(sql);
        }
        try {
            UpdateClauses parsed = parseUpdateClauses(sql);
            String tableName = DBFConnection.normalizeTableName(parsed.table);
            String setClause = parsed.setClause;
            String whereClause = parsed.whereClause;

            // Parse SET assignments. Each right-hand side is a full expression
            // so a column can be set from other columns, e.g.
            // SET P_OB = O_BAL, P_CL = O_BAL + DD_DEBIT - DD_CREDIT.
            List<String> assignments = splitCsvRespectingQuotes(setClause);
            String[] setColumns = new String[assignments.size()];
            com.dbf.jdbc.parser.ast.ExpressionNode[] setExprs =
                new com.dbf.jdbc.parser.ast.ExpressionNode[assignments.size()];

            for (int i = 0; i < assignments.size(); i++) {
                int eq = assignments.get(i).indexOf('=');
                if (eq < 0) {
                    throw new SQLException("Malformed SET assignment: " + assignments.get(i));
                }
                setColumns[i] = unquoteIdentifier(assignments.get(i).substring(0, eq).trim());
                setExprs[i] = parseSetExpression(assignments.get(i).substring(eq + 1).trim());
            }

            String filePath = connection.getPath() + "/" + tableName + ".dbf";
            int affected = 0;
            List<Long> changedRecnos = new ArrayList<>();
            com.dbf.jdbc.tx.WriteLock lock = connection.beginWrite(tableName);
            try {
                try (DBFReader reader = new DBFReader(filePath, getCharset());
                     DBFWriter writer = new DBFWriter(filePath, getCharset())) {

                    List<com.dbf.jdbc.dbf.DBFField> fields = reader.getHeader().getFields();
                    int[] setIdx = new int[setColumns.length];
                    for (int i = 0; i < setColumns.length; i++) {
                        setIdx[i] = findFieldIndex(fields, setColumns[i]);
                        if (setIdx[i] < 0) {
                            throw new SQLException("Unknown column in SET: " + setColumns[i]);
                        }
                    }

                    String[] fieldNames = new String[fields.size()];
                    for (int i = 0; i < fields.size(); i++) {
                        fieldNames[i] = fields.get(i).getName();
                    }
                    com.dbf.jdbc.execution.eval.RowExpressionEvaluator setEval =
                        new com.dbf.jdbc.execution.eval.RowExpressionEvaluator(fieldNames);

                    FilterEngine filter = buildDmlFilter(whereClause, fields);

                    int updated = 0;
                    reader.beforeFirst();
                    while (reader.next()) {
                        if (reader.isDeleted()) continue;

                        Object[] row = readFullRow(reader, fields.size());
                        int recno = reader.getCurrentRecord() + 1;
                        if (filter != null && !filter.matches(row, recno)) continue;

                        // Capture the original bytes for rollback before overwriting.
                        byte[] original = reader.getCurrentRecordRaw();

                        // Evaluate every SET expression against the ORIGINAL row
                        // (so assignments don't see each other's new values),
                        // then overlay the results.
                        Object[] newVals = new Object[setIdx.length];
                        for (int i = 0; i < setIdx.length; i++) {
                            newVals[i] = setEval.evaluate(setExprs[i], row);
                        }
                        for (int i = 0; i < setIdx.length; i++) {
                            row[setIdx[i]] = newVals[i];
                        }
                        if (writer.updateRecord(recno, row)) {
                            updated++;
                            changedRecnos.add((long) recno);
                            connection.captureOriginal(tableName, recno, original);
                        }
                    }
                    affected = updated;
                }
                maintainIndexesAfterUpdate(tableName, changedRecnos);
                return affected;
            } finally {
                connection.endWrite(lock);
            }
        } catch (IOException e) {
            throw writeError("Update", e);
        }
    }

    /** True for an Access/Jet UPDATE with a JOIN: {@code UPDATE t1 [JOIN t2 ON ..] SET ..}. */
    private static boolean isJoinUpdate(String sql) {
        int i = skipWhitespace(sql, 0);
        int end = i;
        while (end < sql.length() && isWordChar(sql.charAt(end))) end++;
        if (!sql.substring(i, end).equalsIgnoreCase("UPDATE")) return false;
        int setPos = findTopLevelKeyword(sql, end, "SET");
        if (setPos < 0) return false;
        int joinPos = findTopLevelKeyword(sql, end, "JOIN");
        return joinPos >= 0 && joinPos < setPos;
    }

    /**
     * Executes a correlated multi-table UPDATE (MS Access / Jet syntax):
     * {@code UPDATE target [AS a] [INNER] JOIN other [AS b] ON a.k=b.k [AND ..]
     * SET a.col = <expr over a and b> [, ..] [WHERE <expr over a and b>]}.
     *
     * <p>The join table is read into memory and indexed by the composite equi-join
     * key; each target row that has a match is updated by evaluating the SET (and
     * optional WHERE) expressions against the alias-qualified combined row. Only
     * the target table is written, locked and re-indexed.
     */
    private int executeUpdateJoin(String sql) throws SQLException {
        try {
            int i = skipWhitespace(sql, 0);
            i = expectKeyword(sql, i, "UPDATE");
            int setPos = findTopLevelKeyword(sql, i, "SET");
            if (setPos < 0) {
                throw new SQLException("Missing SET in UPDATE statement: " + sql);
            }
            int wherePos = findTopLevelKeyword(sql, setPos + 3, "WHERE");

            String header = sql.substring(i, setPos).trim();
            String setClause = (wherePos >= 0 ? sql.substring(setPos + 3, wherePos)
                                              : sql.substring(setPos + 3)).trim();
            String whereClause = wherePos >= 0 ? sql.substring(wherePos + 5).trim() : null;

            int joinPos = findTopLevelKeyword(header, 0, "JOIN");
            int onPos = findTopLevelKeyword(header, joinPos + 4, "ON");
            if (onPos < 0) {
                throw new SQLException("UPDATE ... JOIN requires an ON clause: " + sql);
            }
            String[] tgt = parseTableAndAlias(header.substring(0, joinPos).trim());
            String[] oth = parseTableAndAlias(header.substring(joinPos + 4, onPos).trim());
            String onCond = header.substring(onPos + 2).trim();

            String targetTable = DBFConnection.normalizeTableName(tgt[0]);
            String targetAlias = tgt[1] != null ? tgt[1] : targetTable;
            String joinTable = DBFConnection.normalizeTableName(oth[0]);
            String joinAlias = oth[1] != null ? oth[1] : joinTable;

            com.dbf.jdbc.parser.ast.ExpressionNode onExpr =
                new Parser(new StringReader(onCond)).parseExpression();
            List<com.dbf.jdbc.parser.ast.ExpressionNode> eqs = new ArrayList<>();
            collectAndConjuncts(onExpr, eqs);
            if (eqs.isEmpty()) {
                throw new SQLException("UPDATE ... JOIN ON must be an equi-join: " + onCond);
            }

            String targetPath = connection.getPath() + "/" + targetTable + ".dbf";
            String joinPath = connection.getPath() + "/" + joinTable + ".dbf";

            // 1. Read the join table fully into memory.
            List<com.dbf.jdbc.dbf.DBFField> joinFields;
            List<Object[]> joinRows = new ArrayList<>();
            try (DBFReader jr = new DBFReader(joinPath, getCharset())) {
                joinFields = jr.getHeader().getFields();
                jr.beforeFirst();
                while (jr.next()) {
                    if (jr.isDeleted()) continue;
                    joinRows.add(readFullRow(jr, joinFields.size()));
                }
            }

            int affected = 0;
            List<Long> changedRecnos = new ArrayList<>();
            com.dbf.jdbc.tx.WriteLock lock = connection.beginWrite(targetTable);
            try {
                try (DBFReader reader = new DBFReader(targetPath, getCharset());
                     DBFWriter writer = new DBFWriter(targetPath, getCharset())) {

                    List<com.dbf.jdbc.dbf.DBFField> targetFields = reader.getHeader().getFields();

                    // Resolve the equi-join key columns to indexes on each side.
                    int[] tgtKeyIdx = new int[eqs.size()];
                    int[] joinKeyIdx = new int[eqs.size()];
                    for (int k = 0; k < eqs.size(); k++) {
                        com.dbf.jdbc.parser.ast.ExpressionNode eq = eqs.get(k);
                        if (eq.getType() != com.dbf.jdbc.parser.TokenType.EQ) {
                            throw new SQLException("UPDATE ... JOIN ON supports only "
                                + "equi-join conditions (col = col, AND-combined): " + onCond);
                        }
                        String[] a = classifyRef(eq.getLeft(), targetAlias, targetTable, joinAlias, joinTable);
                        String[] b = classifyRef(eq.getRight(), targetAlias, targetTable, joinAlias, joinTable);
                        String tgtCol, joinCol;
                        if ("T".equals(side(a)) && "J".equals(side(b))) {
                            tgtCol = a[1]; joinCol = b[1];
                        } else if ("J".equals(side(a)) && "T".equals(side(b))) {
                            tgtCol = b[1]; joinCol = a[1];
                        } else {
                            throw new SQLException("ON condition must reference both the target "
                                + "and the joined table: " + onCond);
                        }
                        tgtKeyIdx[k] = findFieldIndex(targetFields, tgtCol);
                        joinKeyIdx[k] = findFieldIndex(joinFields, joinCol);
                        if (tgtKeyIdx[k] < 0) throw new SQLException("Unknown join column: " + tgtCol);
                        if (joinKeyIdx[k] < 0) throw new SQLException("Unknown join column: " + joinCol);
                    }

                    // Index the join rows by composite key (first match wins).
                    java.util.Map<List<Object>, Object[]> joinByKey = new java.util.HashMap<>();
                    for (Object[] jrow : joinRows) {
                        List<Object> key = new ArrayList<>(joinKeyIdx.length);
                        for (int idx : joinKeyIdx) key.add(normalizeJoinKey(jrow[idx]));
                        joinByKey.putIfAbsent(key, jrow);
                    }

                    // Combined, alias-qualified column names: target fields then join fields.
                    String[] combinedNames = new String[targetFields.size() + joinFields.size()];
                    for (int c = 0; c < targetFields.size(); c++) {
                        combinedNames[c] = targetAlias + "." + targetFields.get(c).getName();
                    }
                    for (int c = 0; c < joinFields.size(); c++) {
                        combinedNames[targetFields.size() + c] =
                            joinAlias + "." + joinFields.get(c).getName();
                    }
                    com.dbf.jdbc.execution.eval.RowExpressionEvaluator eval =
                        new com.dbf.jdbc.execution.eval.RowExpressionEvaluator(combinedNames);

                    // Parse SET: LHS -> target column (alias stripped), RHS -> expression.
                    List<String> assignments = splitCsvRespectingQuotes(setClause);
                    int[] setIdx = new int[assignments.size()];
                    com.dbf.jdbc.parser.ast.ExpressionNode[] setExprs =
                        new com.dbf.jdbc.parser.ast.ExpressionNode[assignments.size()];
                    for (int a = 0; a < assignments.size(); a++) {
                        int eq = assignments.get(a).indexOf('=');
                        if (eq < 0) throw new SQLException("Malformed SET assignment: " + assignments.get(a));
                        String col = stripAliasPrefix(assignments.get(a).substring(0, eq), targetAlias, targetTable);
                        setIdx[a] = findFieldIndex(targetFields, col);
                        if (setIdx[a] < 0) throw new SQLException("Unknown column in SET: " + col);
                        setExprs[a] = parseSetExpression(assignments.get(a).substring(eq + 1).trim());
                    }

                    com.dbf.jdbc.parser.ast.ExpressionNode whereExpr = whereClause == null ? null
                        : new Parser(new StringReader(whereClause)).parseExpression();

                    int tcount = targetFields.size();
                    reader.beforeFirst();
                    while (reader.next()) {
                        if (reader.isDeleted()) continue;
                        Object[] row = readFullRow(reader, tcount);
                        int recno = reader.getCurrentRecord() + 1;

                        List<Object> key = new ArrayList<>(tgtKeyIdx.length);
                        for (int idx : tgtKeyIdx) key.add(normalizeJoinKey(row[idx]));
                        Object[] jrow = joinByKey.get(key);
                        if (jrow == null) continue; // INNER JOIN: no match -> not updated

                        Object[] combined = new Object[tcount + joinFields.size()];
                        System.arraycopy(row, 0, combined, 0, tcount);
                        System.arraycopy(jrow, 0, combined, tcount, joinFields.size());

                        if (whereExpr != null && !eval.matches(whereExpr, combined, recno)) continue;

                        byte[] original = reader.getCurrentRecordRaw();
                        Object[] newVals = new Object[setIdx.length];
                        for (int s = 0; s < setIdx.length; s++) {
                            newVals[s] = eval.evaluate(setExprs[s], combined);
                        }
                        for (int s = 0; s < setIdx.length; s++) {
                            row[setIdx[s]] = newVals[s];
                        }
                        if (writer.updateRecord(recno, row)) {
                            affected++;
                            changedRecnos.add((long) recno);
                            connection.captureOriginal(targetTable, recno, original);
                        }
                    }
                }
                maintainIndexesAfterUpdate(targetTable, changedRecnos);
                return affected;
            } finally {
                connection.endWrite(lock);
            }
        } catch (IOException e) {
            throw writeError("Update", e);
        } catch (Parser.ParseException e) {
            throw new SQLException("Could not parse UPDATE ... JOIN: " + e.getMessage(), e);
        }
    }

    /** Splits a "table [AS] alias" spec, ignoring join-type keywords; returns {table, alias|null}. */
    private static String[] parseTableAndAlias(String spec) {
        String table = null;
        String alias = null;
        for (String t : spec.trim().split("\\s+")) {
            if (t.isEmpty()) continue;
            String u = t.toUpperCase();
            if (u.equals("AS") || u.equals("INNER") || u.equals("LEFT") || u.equals("RIGHT")
                || u.equals("OUTER") || u.equals("FULL") || u.equals("CROSS") || u.equals("JOIN")) {
                continue;
            }
            if (table == null) table = t;
            else if (alias == null) alias = t;
        }
        return new String[] { unquoteIdentifier(table),
            alias == null ? null : unquoteIdentifier(alias) };
    }

    /** Classifies a column ref as target ("T") or join ("J") side; {side, column} or null. */
    private static String[] classifyRef(com.dbf.jdbc.parser.ast.ExpressionNode ref,
            String tAlias, String tTable, String jAlias, String jTable) {
        if (ref == null || !ref.isColumn()) return null;
        String tbl = ref.getTableName();
        String col = ref.getColumnName();
        if (tbl == null) return new String[] { "?", col };
        if (tbl.equalsIgnoreCase(tAlias) || tbl.equalsIgnoreCase(tTable)) return new String[] { "T", col };
        if (tbl.equalsIgnoreCase(jAlias) || tbl.equalsIgnoreCase(jTable)) return new String[] { "J", col };
        return new String[] { "?", col };
    }

    private static String side(String[] classified) {
        return classified == null ? null : classified[0];
    }

    /** Strips a leading {@code alias.}/{@code table.} qualifier from a SET left-hand side. */
    private static String stripAliasPrefix(String lhs, String alias, String table) {
        String s = lhs.trim();
        int dot = s.indexOf('.');
        if (dot > 0) {
            String pre = s.substring(0, dot).trim();
            if (pre.equalsIgnoreCase(alias) || pre.equalsIgnoreCase(table)) {
                return unquoteIdentifier(s.substring(dot + 1).trim());
            }
        }
        return unquoteIdentifier(s);
    }

    /** Join keys compare case/space like char values; trim strings, leave others. */
    private static Object normalizeJoinKey(Object v) {
        return v instanceof String ? ((String) v).trim() : v;
    }

    private int executeDelete(String sql) throws SQLException {
        try {
            // Parse DELETE FROM table WHERE condition
            String upper = sql.toUpperCase();
            int fromPos = upper.indexOf("FROM") + 4;
            int wherePos = upper.indexOf("WHERE");
            String tableName;
            String whereClause = null;

            if (wherePos > 0) {
                tableName = DBFConnection.normalizeTableName(sql.substring(fromPos, wherePos).trim());
                whereClause = sql.substring(wherePos + 5).trim();
            } else {
                tableName = DBFConnection.normalizeTableName(sql.substring(fromPos).trim());
            }

            String filePath = connection.getPath() + "/" + tableName + ".dbf";
            int affected = 0;
            com.dbf.jdbc.tx.WriteLock lock = connection.beginWrite(tableName);
            try {
                try (DBFReader reader = new DBFReader(filePath, getCharset());
                     DBFWriter writer = new DBFWriter(filePath, getCharset())) {

                    List<com.dbf.jdbc.dbf.DBFField> fields = reader.getHeader().getFields();
                    FilterEngine filter = buildDmlFilter(whereClause, fields);

                    int deleted = 0;
                    reader.beforeFirst();
                    while (reader.next()) {
                        if (reader.isDeleted()) continue;

                        Object[] row = readFullRow(reader, fields.size());
                        int recno = reader.getCurrentRecord() + 1;
                        if (filter != null && !filter.matches(row, recno)) continue;

                        // Capture original bytes (incl. the deleted flag) for rollback.
                        byte[] original = reader.getCurrentRecordRaw();
                        if (writer.deleteRecord(recno)) {
                            deleted++;
                            connection.captureOriginal(tableName, recno, original);
                        }
                    }
                    affected = deleted;
                }
                maintainIndexes(tableName);
                return affected;
            } finally {
                connection.endWrite(lock);
            }
        } catch (IOException e) {
            throw new SQLException("Delete failed: " + e.getMessage(), e);
        }
    }

    /**
     * Rebuilds the table's maintainable .NTX indexes after a write. Gated by
     * the {@code indexWrite} connection flag (default off). Never throws: index
     * upkeep must not fail the user's DML, and {@link com.dbf.jdbc.index.ntx.NtxMaintainer}
     * refuses any index it cannot reproduce/verify, so it cannot corrupt one.
     */
    private void maintainIndexes(String tableName) {
        // Inside a transaction, indexes are rebuilt at commit, not per statement.
        if (!connection.isIndexWriteEnabled() || connection.inTransaction()) {
            return;
        }
        try {
            com.dbf.jdbc.index.ntx.NtxMaintainer.rebuildAll(
                connection.getPath(), tableName, getCharset());
        } catch (RuntimeException e) {
            // Index maintenance is best-effort; the DML already succeeded.
        }
    }

    /**
     * Fast-path index upkeep after INSERT: incrementally adds just the new
     * records' keys (the new RECNOs are in {@code lastGeneratedKeys}), falling
     * back to a full rebuild per index only if the incremental result fails its
     * self-check.
     */
    protected void maintainIndexesAfterInsert(String tableName) {
        if (!connection.isIndexWriteEnabled() || connection.inTransaction()
                || lastGeneratedKeys.isEmpty()) {
            return;
        }
        try {
            long[] recnos = new long[lastGeneratedKeys.size()];
            for (int i = 0; i < recnos.length; i++) {
                recnos[i] = lastGeneratedKeys.get(i);
            }
            com.dbf.jdbc.index.ntx.NtxMaintainer.insertKeys(
                connection.getPath(), tableName, getCharset(), recnos);
        } catch (RuntimeException e) {
            // Best-effort; the INSERT itself already succeeded.
        }
    }

    /**
     * Fast-path index upkeep after UPDATE: incrementally re-keys just the
     * changed records (remove old key, add new), falling back to a full rebuild
     * per index if the incremental result fails its self-check.
     */
    private void maintainIndexesAfterUpdate(String tableName, List<Long> changedRecnos) {
        if (!connection.isIndexWriteEnabled() || connection.inTransaction()
                || changedRecnos.isEmpty()) {
            return;
        }
        try {
            long[] recnos = new long[changedRecnos.size()];
            for (int i = 0; i < recnos.length; i++) {
                recnos[i] = changedRecnos.get(i);
            }
            com.dbf.jdbc.index.ntx.NtxMaintainer.updateKeys(
                connection.getPath(), tableName, getCharset(), recnos);
        } catch (RuntimeException e) {
            // Best-effort; the UPDATE itself already succeeded.
        }
    }

    /** Parses a WHERE clause with the full SQL expression grammar. */
    private FilterEngine buildDmlFilter(String whereClause,
            List<com.dbf.jdbc.dbf.DBFField> fields) throws SQLException {
        if (whereClause == null || whereClause.isEmpty()) {
            return null;
        }
        try {
            Parser parser = new Parser(new StringReader(whereClause));
            return new FilterEngine(parser.parseExpression(), fields);
        } catch (IOException | Parser.ParseException e) {
            throw new SQLException("Invalid WHERE clause: " + e.getMessage(), e);
        }
    }

    private Object[] readFullRow(DBFReader reader, int fieldCount)
            throws IOException, SQLException {
        Object[] row = new Object[fieldCount];
        for (int i = 0; i < fieldCount; i++) {
            row[i] = reader.getValue(i);
        }
        return row;
    }

    protected Object parseValue(String value) {
        value = value.trim();
        if (value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1);
        }
        // MS Access / xBase date literal: #2025-10-10#, #10/10/2025#, etc. The
        // SELECT path handles these in the lexer; INSERT/UPDATE values go through
        // here, so strip the # delimiters and normalize to yyyy-MM-dd (the form
        // the DBF date-field encoder accepts).
        if (value.length() >= 2 && value.charAt(0) == '#'
                && value.charAt(value.length() - 1) == '#') {
            return parseDateLiteral(value.substring(1, value.length() - 1).trim());
        }
        if (value.equalsIgnoreCase("NULL")) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e1) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException e2) {
                return value;
            }
        }
    }

    /**
     * Normalizes the inside of a {@code #...#} date literal to {@code yyyy-MM-dd}.
     * Accepts ISO ({@code yyyy-MM-dd}), compact ({@code yyyyMMdd}) and the common
     * Access slash forms; an optional trailing time is dropped. Anything else is
     * passed through unchanged so the field validator reports it.
     */
    private static String parseDateLiteral(String s) {
        if (s.isEmpty()) {
            return null;
        }
        int sp = s.indexOf(' ');
        if (sp > 0) {
            s = s.substring(0, sp); // drop any time component
        }
        if (s.matches("\\d{4}-\\d{2}-\\d{2}") || s.matches("\\d{8}")) {
            return s;
        }
        String[] patterns = { "yyyy/MM/dd", "M/d/yyyy", "MM/dd/yyyy", "M-d-yyyy", "dd-MM-yyyy" };
        for (String p : patterns) {
            try {
                return java.time.LocalDate.parse(s,
                    java.time.format.DateTimeFormatter.ofPattern(p)).toString();
            } catch (RuntimeException ignore) {
                // try the next pattern
            }
        }
        return s;
    }
    
    @Override
    public void addBatch(String sql) throws SQLException {
        checkClosed();
        batchCommands.add(sql);
    }
    
    @Override
    public void clearBatch() throws SQLException {
        checkClosed();
        batchCommands.clear();
        batchResults.clear();
    }
    
    @Override
    public int[] executeBatch() throws SQLException {
        checkClosed();
        
        if (batchCommands.isEmpty()) {
            return new int[0];
        }
        
        int[] results = new int[batchCommands.size()];
        
        for (int i = 0; i < batchCommands.size(); i++) {
            try {
                results[i] = executeUpdate(batchCommands.get(i));
            } catch (SQLException e) {
                results[i] = -3; // Batch update failed
            }
        }
        
        batchCommands.clear();
        return results;
    }
    
    @Override
    public boolean execute(String sql) throws SQLException {
        String upperSql = sql.trim().toUpperCase();
        if (upperSql.startsWith("SELECT")) {
            executeQuery(sql);
            return true;
        } else {
            executeUpdate(sql);
            return false;
        }
    }
    
    @Override
    public ResultSet getResultSet() throws SQLException {
        return currentResultSet;
    }
    
    @Override
    public int getUpdateCount() throws SQLException {
        return -1;
    }
    
    @Override
    public boolean getMoreResults() throws SQLException {
        closeCurrentResultSet();
        return false;
    }
    
    @Override
    public void setFetchSize(int rows) throws SQLException {
        if (rows < 0) throw new SQLException("Fetch size cannot be negative");
        this.fetchSize = rows;
        if (currentResultSet != null) {
            currentResultSet.setFetchSize(rows);
        }
    }
    
    @Override
    public int getFetchSize() throws SQLException {
        return fetchSize;
    }
    
    @Override
    public int getResultSetConcurrency() throws SQLException {
        return ResultSet.CONCUR_READ_ONLY;
    }
    
    @Override
    public int getResultSetType() throws SQLException {
        return ResultSet.TYPE_FORWARD_ONLY;
    }
    
    @Override
    public Connection getConnection() throws SQLException {
        return connection;
    }
    
    @Override
    public void close() throws SQLException {
        if (!closed) {
            closeCurrentResultSet();
            closed = true;
        }
    }
    
    @Override
    public boolean isClosed() throws SQLException {
        return closed;
    }
    
    @Override
    public void setMaxRows(int max) throws SQLException {
        if (max < 0) throw new SQLException("Max rows cannot be negative");
        this.maxRows = max;
    }
    
    @Override
    public int getMaxRows() throws SQLException {
        return maxRows;
    }
    
    @Override
    public void setQueryTimeout(int seconds) throws SQLException {
        if (seconds < 0) throw new SQLException("Query timeout cannot be negative");
        this.queryTimeout = seconds;
    }
    
    @Override
    public int getQueryTimeout() throws SQLException {
        return queryTimeout;
    }
    
    @Override
    public SQLWarning getWarnings() throws SQLException {
        return warnings;
    }
    
    @Override
    public void clearWarnings() throws SQLException {
        warnings = null;
    }
    
    @Override
    public void setCursorName(String name) throws SQLException {
        // Not supported
    }
    
    @Override
    public void setEscapeProcessing(boolean enable) throws SQLException {
        this.escapeProcessing = enable;
    }
    
    @Override
    public int getMaxFieldSize() throws SQLException {
        return 0;
    }
    
    @Override
    public void setMaxFieldSize(int max) throws SQLException {
        // Ignore
    }
    
    @Override
    public void setFetchDirection(int direction) throws SQLException {
        // Not implemented
    }
    
    @Override
    public int getFetchDirection() throws SQLException {
        return ResultSet.FETCH_FORWARD;
    }
    
    @Override
    public boolean getMoreResults(int current) throws SQLException {
        return getMoreResults();
    }
    
    /**
     * dBASE has no declared primary keys; the physical record number
     * (RECNO) is the row identity, so inserted rows report their RECNO
     * as the generated key.
     */
    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        checkClosed();
        List<Object[]> rows = new ArrayList<>(lastGeneratedKeys.size());
        for (Integer key : lastGeneratedKeys) {
            rows.add(new Object[] { key });
        }
        return new DBFResultSet(
            new com.dbf.jdbc.execution.streaming.MaterializedRowStream(
                rows, new String[] { "RECNO" }), null);
    }
    
    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        return executeUpdate(sql);
    }
    
    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        return executeUpdate(sql);
    }
    
    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        return executeUpdate(sql);
    }
    
    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        return execute(sql);
    }
    
    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        return execute(sql);
    }
    
    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        return execute(sql);
    }
    
    @Override
    public int getResultSetHoldability() throws SQLException {
        return ResultSet.HOLD_CURSORS_OVER_COMMIT;
    }
    
    @Override
    public void setPoolable(boolean poolable) throws SQLException {
        // Not implemented
    }
    
    @Override
    public boolean isPoolable() throws SQLException {
        return false;
    }
    
    @Override
    public void closeOnCompletion() throws SQLException {
        // Not implemented
    }
    
    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        return false;
    }
    
    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("Cannot unwrap to " + iface);
    }
    
    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this);
    }
    
    protected void checkClosed() throws SQLException {
        if (closed) {
            throw new SQLException("Statement is closed");
        }
    }
    
    protected void closeCurrentResultSet() throws SQLException {
        if (currentResultSet != null) {
            currentResultSet.close();
            currentResultSet = null;
        }
    }
    
    @Override
    public void cancel() throws SQLException {
        // Queries run synchronously on the calling thread, so there is
        // nothing to cancel from another thread.
        checkClosed();
    }
}