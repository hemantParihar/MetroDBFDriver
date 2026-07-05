package com.dbf.jdbc.execution.streaming;

import com.dbf.jdbc.parser.ast.OrderByNode;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * External merge sort with bounded memory.
 *
 * Phase 1: input rows are buffered; when the buffer reaches its row or
 * byte limit it is sorted and spilled to a temporary run file.
 * Phase 2: the sorted runs are k-way merged with a priority queue,
 * streaming one row at a time.
 *
 * If the whole input fits in a single buffer, no disk I/O happens at all.
 * Memory is O(buffer) + O(number of runs), never O(total rows).
 */
public class ExternalMergeSortOperator implements RowStream, FieldAwareStream {
    // Overridable for testing the spill path (-Ddbf.sortMemoryLimit / -Ddbf.sortBufferRows).
    private static final long DEFAULT_MEMORY_LIMIT =
        Long.getLong("dbf.sortMemoryLimit", 16L * 1024 * 1024); // 16MB buffer
    private static final int DEFAULT_BUFFER_ROWS =
        Integer.getInteger("dbf.sortBufferRows", 100_000);

    private final RowStream input;
    private final int[] sortColumnIndexes;
    private final boolean[] sortAscending;
    private final String[] columnNames;
    private final int[] columnTypes;
    private final long memoryLimit;
    private final int bufferRows;
    private final int topN; // > 0: keep only the N best rows (Top-N sort); <= 0: full sort
    private final Comparator<Object[]> rowComparator;

    // Disk-merge state
    private final List<Path> runFiles = new ArrayList<>();
    private DataInputStream[] runStreams;
    private PriorityQueue<RunEntry> mergeQueue;
    private Path tempDir;

    // In-memory fast path (input fit in one buffer)
    private List<Object[]> memoryRows;
    private int memoryIndex;

    private boolean sorted = false;
    private boolean closed = false;

    private static final class RunEntry {
        final Object[] row;
        final int runIndex;

        RunEntry(Object[] row, int runIndex) {
            this.row = row;
            this.runIndex = runIndex;
        }
    }

    public ExternalMergeSortOperator(RowStream input, OrderByNode orderBy, String[] columnNames) {
        this(input, orderBy, columnNames, DEFAULT_MEMORY_LIMIT, DEFAULT_BUFFER_ROWS, 0);
    }

    /** Top-N variant: when {@code topN > 0}, only the N best rows are kept. */
    public ExternalMergeSortOperator(RowStream input, OrderByNode orderBy, String[] columnNames,
                                     int topN) {
        this(input, orderBy, columnNames, DEFAULT_MEMORY_LIMIT, DEFAULT_BUFFER_ROWS, topN);
    }

    public ExternalMergeSortOperator(RowStream input, OrderByNode orderBy, String[] columnNames,
                                     long memoryLimit, int bufferRows) {
        this(input, orderBy, columnNames, memoryLimit, bufferRows, 0);
    }

    public ExternalMergeSortOperator(RowStream input, OrderByNode orderBy, String[] columnNames,
                                     long memoryLimit, int bufferRows, int topN) {
        this.input = input;
        this.columnNames = columnNames;
        this.columnTypes = input.getColumnTypes();
        this.memoryLimit = memoryLimit;
        this.bufferRows = bufferRows;
        this.topN = topN;

        List<Integer> sortCols = new ArrayList<>();
        List<Boolean> sortAsc = new ArrayList<>();
        if (orderBy != null) {
            for (OrderByNode.OrderItem item : orderBy.getItems()) {
                int idx = findColumnIndex(item.getColumnName());
                if (idx >= 0) {
                    sortCols.add(idx);
                    sortAsc.add(item.isAscending());
                }
            }
        }
        this.sortColumnIndexes = new int[sortCols.size()];
        this.sortAscending = new boolean[sortAsc.size()];
        for (int i = 0; i < sortCols.size(); i++) {
            this.sortColumnIndexes[i] = sortCols.get(i);
            this.sortAscending[i] = sortAsc.get(i);
        }

        this.rowComparator = (a, b) -> {
            for (int i = 0; i < sortColumnIndexes.length; i++) {
                int idx = sortColumnIndexes[i];
                Object valA = (idx < a.length) ? a[idx] : null;
                Object valB = (idx < b.length) ? b[idx] : null;
                int cmp = compareValues(valA, valB);
                if (cmp != 0) {
                    return sortAscending[i] ? cmp : -cmp;
                }
            }
            return 0;
        };
    }

    private int findColumnIndex(String columnName) {
        for (int i = 0; i < columnNames.length; i++) {
            if (columnNames[i].equalsIgnoreCase(columnName)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public Object[] next() throws IOException, SQLException {
        if (!sorted) {
            performExternalSort();
            sorted = true;
        }

        if (memoryRows != null) {
            if (memoryIndex >= memoryRows.size()) return null;
            return memoryRows.get(memoryIndex++);
        }

        if (mergeQueue == null || mergeQueue.isEmpty()) {
            return null;
        }

        RunEntry entry = mergeQueue.poll();
        Object[] nextFromRun = readRowFromRun(entry.runIndex);
        if (nextFromRun != null) {
            mergeQueue.offer(new RunEntry(nextFromRun, entry.runIndex));
        }
        return entry.row;
    }

    private void performExternalSort() throws IOException, SQLException {
        if (topN > 0) {
            performTopN();
            return;
        }
        List<Object[]> buffer = new ArrayList<>();
        long estimatedBytes = 0;

        Object[] row;
        while ((row = input.next()) != null) {
            buffer.add(row);
            estimatedBytes += estimateRowSize(row);

            if (buffer.size() >= bufferRows || estimatedBytes >= memoryLimit) {
                spillRun(buffer);
                buffer = new ArrayList<>();
                estimatedBytes = 0;
            }
        }

        if (runFiles.isEmpty()) {
            // Everything fit in one buffer: sort in memory, no disk I/O
            buffer.sort(rowComparator);
            memoryRows = buffer;
            memoryIndex = 0;
            return;
        }

        if (!buffer.isEmpty()) {
            spillRun(buffer);
        }

        // Open every run and seed the merge queue with its first row
        runStreams = new DataInputStream[runFiles.size()];
        mergeQueue = new PriorityQueue<>(runFiles.size(),
            (a, b) -> rowComparator.compare(a.row, b.row));
        for (int i = 0; i < runFiles.size(); i++) {
            runStreams[i] = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(runFiles.get(i)), 1 << 16));
            Object[] first = readRowFromRun(i);
            if (first != null) {
                mergeQueue.offer(new RunEntry(first, i));
            }
        }
    }

    /** Holds a row plus its input position, for stable Top-N tie-breaking. */
    private static final class TopEntry {
        final Object[] row;
        final long idx;
        TopEntry(Object[] row, long idx) { this.row = row; this.idx = idx; }
    }

    /**
     * Top-N sort (Calcite's LimitSort idea): keep only the {@code topN} best rows
     * in a size-N max-heap during a single pass -- O(rows * log N) time, O(N)
     * memory, no spill, no full sort. Ties are broken by input order so the
     * output is identical to a stable full sort followed by LIMIT N.
     */
    private void performTopN() throws IOException, SQLException {
        Comparator<TopEntry> total = (a, b) -> {
            int c = rowComparator.compare(a.row, b.row);
            return c != 0 ? c : Long.compare(a.idx, b.idx);
        };
        // Max-heap: the current worst (largest) sits at the head so it can be evicted.
        PriorityQueue<TopEntry> heap = new PriorityQueue<>(
            Math.min(Math.max(topN, 1), 1024), total.reversed());

        long idx = 0;
        Object[] row;
        while ((row = input.next()) != null) {
            TopEntry e = new TopEntry(row, idx++);
            if (heap.size() < topN) {
                heap.offer(e);
            } else if (total.compare(e, heap.peek()) < 0) {
                heap.poll();
                heap.offer(e);
            }
        }
        TopEntry[] arr = heap.toArray(new TopEntry[0]);
        java.util.Arrays.sort(arr, total);
        memoryRows = new ArrayList<>(arr.length);
        for (TopEntry e : arr) {
            memoryRows.add(e.row);
        }
        memoryIndex = 0;
    }

    private void spillRun(List<Object[]> buffer) throws IOException {
        buffer.sort(rowComparator);

        if (tempDir == null) {
            tempDir = Files.createTempDirectory("dbf_sort_");
        }
        Path runFile = Files.createTempFile(tempDir, "run_", ".tmp");
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(runFile), 1 << 16))) {
            for (Object[] row : buffer) {
                RowSerializer.writeRow(out, row);
            }
        }
        runFiles.add(runFile);
    }

    private Object[] readRowFromRun(int runIndex) throws IOException {
        DataInputStream in = runStreams[runIndex];
        if (in == null) return null;
        try {
            return RowSerializer.readRow(in);
        } catch (EOFException e) {
            in.close();
            runStreams[runIndex] = null;
            return null;
        }
    }

    private long estimateRowSize(Object[] row) {
        long size = 48; // array + object headers
        for (Object value : row) {
            if (value == null) {
                size += 8;
            } else if (value instanceof String) {
                size += 48 + ((String) value).length();
            } else {
                size += 24;
            }
        }
        return size;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareValues(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        }
        if (a instanceof Comparable && a.getClass() == b.getClass()) {
            return ((Comparable) a).compareTo(b);
        }
        return a.toString().trim().compareToIgnoreCase(b.toString().trim());
    }

    @Override
    public String[] getColumnNames() {
        return columnNames;
    }

    @Override
    public int[] getColumnTypes() {
        return columnTypes;
    }

    @Override
    public java.util.List<com.dbf.jdbc.dbf.DBFField> getFields() {
        return input instanceof FieldAwareStream
            ? ((FieldAwareStream) input).getFields()
            : null;
    }

    @Override
    public void reset() throws IOException, SQLException {
        cleanupRuns();
        memoryRows = null;
        memoryIndex = 0;
        mergeQueue = null;
        sorted = false;
        input.reset();
    }

    @Override
    public boolean supportsReset() {
        return input.supportsReset();
    }

    @Override
    public long estimateRowCount() {
        return input.estimateRowCount();
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            cleanupRuns();
            input.close();
        }
    }

    private void cleanupRuns() throws IOException {
        if (runStreams != null) {
            for (DataInputStream in : runStreams) {
                if (in != null) {
                    try { in.close(); } catch (IOException ignored) { }
                }
            }
            runStreams = null;
        }
        for (Path file : runFiles) {
            try { Files.deleteIfExists(file); } catch (IOException ignored) { }
        }
        runFiles.clear();
        if (tempDir != null) {
            try { Files.deleteIfExists(tempDir); } catch (IOException ignored) { }
            tempDir = null;
        }
    }
}
