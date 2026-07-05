package com.dbf.jdbc.index.ntx;

import com.dbf.jdbc.dbf.DBFField;
import com.dbf.jdbc.parser.TokenType;
import com.dbf.jdbc.parser.ast.ExpressionNode;
import com.dbf.jdbc.parser.ast.OrderByNode;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides whether a single-table WHERE clause can be answered by seeking a
 * Clipper {@code .NTX} index instead of scanning, and if so returns the
 * matching record numbers (already in index order).
 *
 * <p>Strategy: parse the index key expression into ordered terms (e.g.
 * {@code L_FLAG}, {@code upper(CUST_DESC)}), then consume them left-to-right by
 * matching WHERE conjuncts: an equality ({@code col = 'lit'}) pins a whole term
 * and lets us continue; a prefix LIKE ({@code [UPPER(]col[)] LIKE 'p%'}) pins a
 * leading slice and ends the prefix. The built byte prefix is fed to
 * {@link NtxIndex#seekPrefix}. Anything not fully understood returns
 * {@code null}, so the caller safely falls back to a full scan -- the seek is
 * only ever an optimization.
 *
 * <p>The seek can yield false positives if the index disagrees with the table
 * (the caller re-applies the full WHERE, which removes them). It cannot protect
 * against a stale index that is missing rows; that is why index reads are
 * gated behind a connection flag.
 */
public final class NtxPlanner {

    private NtxPlanner() {
    }

    /** Outcome of a successful index match (for diagnostics + execution). */
    public static final class Match {
        public final String indexFile;
        public final String keyExpression;
        public final List<Long> recnos;
        /** True when {@code recnos} are already in the query's ORDER BY order
         *  (so the caller can skip sorting). */
        public final boolean ordered;

        Match(String indexFile, String keyExpression, List<Long> recnos, boolean ordered) {
            this.indexFile = indexFile;
            this.keyExpression = keyExpression;
            this.recnos = recnos;
            this.ordered = ordered;
        }
    }

    /**
     * Attempts an index seek for {@code tableName} in {@code folder}.
     *
     * <p>Two strategies:
     * <ul>
     *   <li><b>Ordered fast path</b> (best for type-ahead): when an index's order
     *       matches the query's ORDER BY, the WHERE is fully covered by the seek
     *       prefix, and a positive {@code limit} (TOP n) is given, we seek only
     *       the first {@code limit} record numbers in index order and stop. This
     *       is fast even for a broad prefix like {@code 'C%'} because we never
     *       read more than {@code limit} rows, and the caller can skip sorting.</li>
     *   <li><b>Selective path</b>: otherwise the prefix is sought capped at
     *       {@code maxHits}; an index matching more than that is rejected (random
     *       I/O would beat a scan), and the most selective candidate wins.</li>
     * </ul>
     *
     * @param orderBy the query ORDER BY (may be null); used to enable the fast path
     * @param limit   TOP/LIMIT n (<=0 if none); enables the fast path
     * @param maxHits selectivity cap for the non-ordered path ({@code <=0} = none)
     */
    public static Match plan(String folder, String tableName, List<DBFField> fields,
            ExpressionNode where, OrderByNode orderBy, int limit, int maxHits) {
        if (where == null) {
            return null;
        }
        List<ExpressionNode> conjuncts = new ArrayList<>();
        collectConjuncts(where, conjuncts);

        Match best = null;
        for (File ntx : ntxFilesFor(folder, tableName, fields)) {
            try (NtxIndex idx = NtxIndex.open(ntx.getPath())) {
                List<KeyTerm> terms = parseSeekTerms(idx.keyExpression(), fields);
                if (terms.isEmpty()) {
                    continue;
                }
                PrefixPlan pp = buildPrefix(terms, conjuncts);
                if (pp == null) {
                    continue;
                }

                // Fast path: index order satisfies ORDER BY, WHERE fully covered,
                // and a row cap exists -> read just the first `limit` in order.
                boolean fullyCovers =
                    pp.pinnedEqualityCount + (pp.likeUsed ? 1 : 0) == conjuncts.size();
                boolean ordered = orderSatisfiedBy(terms, pp.pinnedEqualityCount, orderBy);
                if (ordered && fullyCovers && limit > 0) {
                    List<Long> recnos = idx.seekPrefix(pp.prefix, limit);
                    return new Match(ntx.getName(), idx.keyExpression(), recnos, true);
                }

                // Selective path: cap the seek so a broad prefix is cheap to reject.
                int seekLimit = maxHits > 0 ? maxHits + 1 : 0;
                List<Long> recnos = idx.seekPrefix(pp.prefix, seekLimit);
                if (maxHits > 0 && recnos.size() > maxHits) {
                    continue;
                }
                if (best == null || recnos.size() < best.recnos.size()) {
                    best = new Match(ntx.getName(), idx.keyExpression(), recnos, false);
                }
            } catch (IOException | RuntimeException e) {
                // Unreadable/odd index: ignore and let the caller scan.
            }
        }
        return best;
    }

    /**
     * True if a seek on this index yields rows already ordered the way
     * {@code orderBy} requests. A null ORDER BY trivially matches (no ordering
     * required). Otherwise each ORDER BY item must line up, in sequence, with
     * the key terms starting at {@code fromIndex} (the first non-equality term),
     * same field and UPPER-ness, ascending.
     */
    private static boolean orderSatisfiedBy(List<KeyTerm> terms, int fromIndex,
            OrderByNode orderBy) {
        if (orderBy == null || orderBy.getItems().isEmpty()) {
            return true;
        }
        List<OrderByNode.OrderItem> items = orderBy.getItems();
        if (fromIndex + items.size() > terms.size()) {
            return false;
        }
        for (int i = 0; i < items.size(); i++) {
            OrderByNode.OrderItem item = items.get(i);
            if (item.isAggregate() || !item.isAscending()) {
                return false;
            }
            String[] fu = orderField(item); // {fieldName, "U"/"" upper-flag}
            if (fu == null) {
                return false;
            }
            KeyTerm term = terms.get(fromIndex + i);
            boolean wantUpper = "U".equals(fu[1]);
            if (!term.field.equalsIgnoreCase(fu[0]) || term.upper != wantUpper) {
                return false;
            }
        }
        return true;
    }

    /** Extracts {field, "U"|""} from an ORDER BY item, or null if not a plain/UPPER column. */
    private static String[] orderField(OrderByNode.OrderItem item) {
        if (item.isExpression()) {
            ExpressionNode e = item.getExpression();
            if (e == null) {
                return null;
            }
            if (e.isColumn()) {
                return new String[] { e.getColumnName(), "" };
            }
            if (e.isFunction() && e.getArguments() != null && e.getArguments().size() == 1) {
                String fn = e.getFunctionName();
                if (("UPPER".equalsIgnoreCase(fn) || "UCASE".equalsIgnoreCase(fn))
                        && e.getArguments().get(0).isColumn()) {
                    return new String[] { e.getArguments().get(0).getColumnName(), "U" };
                }
            }
            return null;
        }
        return item.getColumnName() != null ? new String[] { item.getColumnName(), "" } : null;
    }

    // ==================== introspection ====================

    /** Human-facing description of one .NTX index belonging to a table. */
    public static final class IndexInfo {
        public final String fileName;       // e.g. "MASTER1.NTX"
        public final String indexName;      // base name, e.g. "MASTER1"
        public final String keyExpression;  // raw Clipper key, e.g. "L_FLAG+upper(cust_desc)+..."
        public final List<String> columns;  // indexed fields, in key order
        public final List<Boolean> upper;   // parallel: was the field UPPER()-ed
        public final String createIndexSql;

        IndexInfo(String fileName, String keyExpression, List<String> columns,
                List<Boolean> upper, String createIndexSql) {
            this.fileName = fileName;
            this.indexName = fileName.replaceAll("(?i)\\.ntx$", "");
            this.keyExpression = keyExpression;
            this.columns = columns;
            this.upper = upper;
            this.createIndexSql = createIndexSql;
        }
    }

    /**
     * Lists the .NTX indexes in {@code folder} that belong to {@code tableName}
     * -- i.e. whose key expression's leading terms are fields of the table --
     * with the indexed columns (in key order) and a synthesized CREATE INDEX
     * statement. Indexes whose fields don't match the table are skipped.
     */
    public static List<IndexInfo> describeIndexes(String folder, String tableName,
            List<DBFField> fields) {
        List<IndexInfo> out = new ArrayList<>();
        File dir = new File(folder);
        File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".ntx"));
        if (files == null) {
            return out;
        }
        for (File f : files) {
            try (NtxIndex idx = NtxIndex.open(f.getPath())) {
                List<KeyTerm> terms = parseKey(idx.keyExpression(), fields);
                if (terms.isEmpty()) {
                    continue; // not this table's index
                }
                List<String> cols = new ArrayList<>();
                List<Boolean> ups = new ArrayList<>();
                StringBuilder colSql = new StringBuilder();
                Set<String> seen = new LinkedHashSet<>();
                for (KeyTerm t : terms) {
                    if (!seen.add(t.field.toUpperCase())) {
                        continue; // same field referenced again (e.g. date parts)
                    }
                    cols.add(t.field);
                    ups.add(t.upper);
                    if (colSql.length() > 0) {
                        colSql.append(", ");
                    }
                    colSql.append(t.upper ? "UPPER(" + t.field + ")" : t.field);
                }
                String name = f.getName().replaceAll("(?i)\\.ntx$", "");
                String sql = "CREATE INDEX \"" + name + "\" ON " + tableName
                    + " (" + colSql + ")";
                out.add(new IndexInfo(f.getName(), idx.keyExpression(), cols, ups, sql));
            } catch (IOException | RuntimeException e) {
                // Unreadable index: skip.
            }
        }
        return out;
    }

    // ==================== key expression parsing ====================

    /** One leading term of the key: a field, optionally upper-cased. */
    private static final class KeyTerm {
        final String field;
        final boolean upper;
        final int length; // DBF field width (for fixed-width padding)

        KeyTerm(String field, boolean upper, int length) {
            this.field = field;
            this.upper = upper;
            this.length = length;
        }
    }

    // A bare field, optionally wrapped in a single UPPER()/UCASE().
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern UPPER_WRAP = Pattern.compile(
        "(?i)^(?:upper|ucase)\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)$");

    /**
     * Strict parse for the SEEK path: only leading terms whose byte encoding we
     * can reproduce exactly -- a bare character field or {@code UPPER(charField)}.
     * Stops at the uniqueness suffix or the first term that is anything else
     * (e.g. {@code STR(debit,5)}, {@code STR(YEAR(d_date),4)}), because guessing
     * a wrong prefix would prune the B-tree incorrectly and drop valid rows.
     * Only character fields qualify: their key bytes are the value padded with
     * spaces to the field width, which is what we build in {@link #buildPrefix}.
     */
    private static List<KeyTerm> parseSeekTerms(String keyExpr, List<DBFField> fields) {
        List<KeyTerm> terms = new ArrayList<>();
        for (String raw : splitTopLevel(keyExpr, '+')) {
            String term = raw.trim();
            if (term.isEmpty()) {
                continue;
            }
            if (term.toLowerCase().contains("recn(")) {
                break; // uniqueness suffix -> end of usable prefix
            }
            boolean upper = false;
            String inner = term;
            java.util.regex.Matcher m = UPPER_WRAP.matcher(term);
            if (m.matches()) {
                upper = true;
                inner = m.group(1);
            }
            if (!IDENT.matcher(inner).matches()) {
                break; // not a bare field -> cannot encode safely
            }
            DBFField f = findField(fields, inner);
            if (f == null || Character.toUpperCase(f.getType()) != 'C') {
                break; // unknown, or non-char (numeric/date keys are STR-encoded)
            }
            terms.add(new KeyTerm(f.getName(), upper, f.getLength()));
        }
        return terms;
    }

    /**
     * Lenient parse for INTROSPECTION: lists every table field the key
     * references, peeling off function wrappers (STR/UPPER/SUBS/...). Stops at
     * the uniqueness suffix or the first term that is not a field of this table.
     */
    private static List<KeyTerm> parseKey(String keyExpr, List<DBFField> fields) {
        List<KeyTerm> terms = new ArrayList<>();
        for (String raw : splitTopLevel(keyExpr, '+')) {
            String term = raw.trim();
            if (term.isEmpty()) {
                continue;
            }
            if (term.toLowerCase().contains("recn(")) {
                break; // uniqueness suffix -> end of useful prefix
            }
            boolean upper = term.toLowerCase().contains("upper(")
                || term.toLowerCase().contains("ucase(");
            String fieldName = innerIdentifier(term);
            if (fieldName == null) {
                break;
            }
            DBFField f = findField(fields, fieldName);
            if (f == null) {
                break;
            }
            terms.add(new KeyTerm(f.getName(), upper, f.getLength()));
        }
        return terms;
    }

    /** A built seek prefix plus how it was built (for order/coverage analysis). */
    private static final class PrefixPlan {
        final byte[] prefix;
        final int pinnedEqualityCount; // leading terms pinned by equality
        final boolean likeUsed;        // a prefix-LIKE pinned the next term

        PrefixPlan(byte[] prefix, int pinnedEqualityCount, boolean likeUsed) {
            this.prefix = prefix;
            this.pinnedEqualityCount = pinnedEqualityCount;
            this.likeUsed = likeUsed;
        }
    }

    /** Builds the seek prefix by pinning leading key terms from the WHERE. */
    private static PrefixPlan buildPrefix(List<KeyTerm> terms, List<ExpressionNode> conjuncts) {
        StringBuilder prefix = new StringBuilder();
        int pinnedEqualityCount = 0;
        boolean likeUsed = false;
        for (KeyTerm term : terms) {
            String eq = equalityValue(conjuncts, term.field);
            if (eq != null) {
                String v = term.upper ? eq.toUpperCase() : eq;
                prefix.append(pad(v, term.length));
                pinnedEqualityCount++;
                continue; // whole field pinned; carry on to the next term
            }
            String like = likePrefix(conjuncts, term.field);
            if (like != null) {
                prefix.append(term.upper ? like.toUpperCase() : like);
                likeUsed = true;
                break; // partial pin -> cannot pin any later term
            }
            break; // this term is unconstrained -> stop here
        }
        if (prefix.length() == 0) {
            return null;
        }
        return new PrefixPlan(prefix.toString().getBytes(StandardCharsets.ISO_8859_1),
            pinnedEqualityCount, likeUsed);
    }

    // ==================== WHERE matching ====================

    private static void collectConjuncts(ExpressionNode node, List<ExpressionNode> out) {
        if (node == null) {
            return;
        }
        if (node.getType() == TokenType.AND) {
            collectConjuncts(node.getLeft(), out);
            collectConjuncts(node.getRight(), out);
        } else {
            out.add(node);
        }
    }

    /** Literal value of a {@code field = 'literal'} (or {@code UPPER(field)=...}) conjunct. */
    private static String equalityValue(List<ExpressionNode> conjuncts, String field) {
        for (ExpressionNode c : conjuncts) {
            if (c.getType() != TokenType.EQ) {
                continue;
            }
            if (refersToField(c.getLeft(), field) && isLiteral(c.getRight())) {
                return literalString(c.getRight());
            }
            if (refersToField(c.getRight(), field) && isLiteral(c.getLeft())) {
                return literalString(c.getLeft());
            }
        }
        return null;
    }

    /**
     * Prefix of a {@code field LIKE 'p%'} conjunct (or wrapped in UPPER/UCASE),
     * i.e. a pattern with a single trailing {@code %} and no other wildcards.
     */
    private static String likePrefix(List<ExpressionNode> conjuncts, String field) {
        for (ExpressionNode c : conjuncts) {
            if (c.getType() != TokenType.LIKE) {
                continue;
            }
            if (!refersToField(c.getLeft(), field) || !isLiteral(c.getRight())) {
                continue;
            }
            String pat = literalString(c.getRight());
            if (pat == null || !pat.endsWith("%")) {
                continue;
            }
            String body = pat.substring(0, pat.length() - 1);
            if (body.indexOf('%') >= 0 || body.indexOf('_') >= 0) {
                continue; // wildcard inside -> not a pure prefix
            }
            return body;
        }
        return null;
    }

    /** True if the node references {@code field}, possibly via UPPER()/UCASE()/TRIM(). */
    private static boolean refersToField(ExpressionNode node, String field) {
        if (node == null) {
            return false;
        }
        if (node.isColumn()) {
            return field.equalsIgnoreCase(node.getColumnName());
        }
        if (node.isFunction() && node.getArguments() != null
                && node.getArguments().size() == 1) {
            return refersToField(node.getArguments().get(0), field);
        }
        return false;
    }

    private static boolean isLiteral(ExpressionNode node) {
        return node != null && node.isLiteral();
    }

    private static String literalString(ExpressionNode node) {
        Object v = node.getLiteralValue();
        if (v != null) {
            return v.toString();
        }
        return node.getValue();
    }

    // ==================== helpers ====================

    private static List<File> ntxFilesFor(String folder, String tableName,
            List<DBFField> fields) {
        List<File> result = new ArrayList<>();
        File dir = new File(folder);
        File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".ntx"));
        if (files == null) {
            return result;
        }
        // Prefer indexes named after the table (MASTER*.NTX for MASTER), but
        // accept any whose first key field belongs to this table.
        for (File f : files) {
            String base = f.getName().toLowerCase();
            boolean nameMatch = base.startsWith(tableName.toLowerCase());
            if (nameMatch) {
                result.add(0, f);
            } else {
                result.add(f);
            }
        }
        return result;
    }

    private static DBFField findField(List<DBFField> fields, String name) {
        for (DBFField f : fields) {
            if (f.getName().equalsIgnoreCase(name)) {
                return f;
            }
        }
        return null;
    }

    /** Strips nested single-arg function wrappers and returns the inner identifier. */
    private static String innerIdentifier(String term) {
        String t = term.trim();
        while (t.indexOf('(') >= 0 && t.lastIndexOf(')') > t.indexOf('(')) {
            t = t.substring(t.indexOf('(') + 1, t.lastIndexOf(')')).trim();
        }
        int i = 0;
        while (i < t.length() && (Character.isLetterOrDigit(t.charAt(i)) || t.charAt(i) == '_')) {
            i++;
        }
        String id = t.substring(0, i).trim();
        return id.isEmpty() ? null : id;
    }

    private static String pad(String value, int width) {
        if (value.length() >= width) {
            return value.substring(0, width);
        }
        StringBuilder sb = new StringBuilder(value);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static List<String> splitTopLevel(String expr, char sep) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth > 0) depth--;
            } else if (c == sep && depth == 0) {
                parts.add(expr.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(expr.substring(start));
        return parts;
    }
}
