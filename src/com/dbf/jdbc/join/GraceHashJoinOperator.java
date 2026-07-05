package com.dbf.jdbc.join;

import com.dbf.jdbc.execution.streaming.RowSerializer;
import com.dbf.jdbc.execution.streaming.RowStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Grace (partitioned) hash join with bounded memory.
 *
 * Phase 1: both inputs are hash-partitioned on the join key into temp
 * files, so matching keys always land in the same partition pair.
 * Phase 2: for each partition, the left side is loaded into a hash table
 * and the right side streams against it.
 *
 * Memory is O(largest left partition), never O(table size). Supports
 * INNER, LEFT, RIGHT and FULL joins with standard NULL-key semantics
 * (NULL never matches anything).
 */
public class GraceHashJoinOperator implements RowStream {
    private static final int DEFAULT_PARTITIONS = 32;
    /**
     * When the right (inner) side has no more than this many rows, an INNER/LEFT
     * join builds it in memory and streams the left side against it -- no temp
     * files, no serialization. Dimension/lookup tables (the usual right side of
     * an ERP join) fit easily, so this is the common, fast path. Larger inner
     * sides fall back to the disk-partitioned Grace join (bounded memory).
     */
    private static final int MAX_IN_MEMORY_BUILD_ROWS = 100_000;

    private final RowStream left;
    private final RowStream right;
    private final HashJoinOperator.JoinType type;
    private final int partitionCount;

    private final String[] outputColumns;
    private final int[] outputTypes;
    private final int leftWidth;
    private final int rightWidth;
    private int[] leftKeyIndexes;
    private int[] rightKeyIndexes;

    // Phase 1 output
    private Path tempDir;
    private Path[] leftPartitions;
    private Path[] rightPartitions;

    // Phase 2 state
    private int currentPartition = -1;
    private Map<Object, List<Slot>> buildTable;
    private List<Slot> buildSlots;
    private DataInputStream probeIn;
    private final ArrayDeque<Object[]> pending = new ArrayDeque<>();

    private boolean partitioned = false;
    private boolean closed = false;

    // In-memory fast path (small inner side, INNER/LEFT): right rows hashed by
    // key, left streamed against it.
    private boolean inMemoryMode = false;
    private Map<Object, List<Object[]>> memRight;

    /** A build-side row plus its matched flag (for LEFT/FULL joins). */
    private static final class Slot {
        final Object[] row;
        boolean matched;

        Slot(Object[] row) {
            this.row = row;
        }
    }

    public GraceHashJoinOperator(RowStream left, RowStream right,
                                 String leftKey, String rightKey,
                                 HashJoinOperator.JoinType type) {
        this(left, right, new String[] { leftKey }, new String[] { rightKey },
            type, DEFAULT_PARTITIONS);
    }

    /** Composite equi-join: leftKeys[i] (a left column) matches rightKeys[i] (a right column). */
    public GraceHashJoinOperator(RowStream left, RowStream right,
                                 String[] leftKeys, String[] rightKeys,
                                 HashJoinOperator.JoinType type) {
        this(left, right, leftKeys, rightKeys, type, DEFAULT_PARTITIONS);
    }

    public GraceHashJoinOperator(RowStream left, RowStream right,
                                 String[] leftKeys, String[] rightKeys,
                                 HashJoinOperator.JoinType type, int partitionCount) {
        this.left = left;
        this.right = right;
        this.type = type;
        this.partitionCount = partitionCount;

        String[] leftColumns = left.getColumnNames();
        String[] rightColumns = right.getColumnNames();
        this.leftWidth = leftColumns.length;
        this.rightWidth = rightColumns.length;

        // Resolve each key pair against the side that actually has the column,
        // tolerating the ON clause naming them in either order.
        int k = leftKeys.length;
        this.leftKeyIndexes = new int[k];
        this.rightKeyIndexes = new int[k];
        for (int i = 0; i < k; i++) {
            int li = indexOf(leftColumns, leftKeys[i]);
            int ri = indexOf(rightColumns, rightKeys[i]);
            if (li < 0 && ri < 0) {
                li = indexOf(leftColumns, rightKeys[i]);
                ri = indexOf(rightColumns, leftKeys[i]);
            } else if (li < 0) {
                li = indexOf(leftColumns, rightKeys[i]);
            } else if (ri < 0) {
                ri = indexOf(rightColumns, leftKeys[i]);
            }
            leftKeyIndexes[i] = li;
            rightKeyIndexes[i] = ri;
        }

        List<String> cols = new ArrayList<>();
        cols.addAll(Arrays.asList(leftColumns));
        cols.addAll(Arrays.asList(rightColumns));
        this.outputColumns = cols.toArray(new String[0]);

        this.outputTypes = new int[outputColumns.length];
        int[] lt = left.getColumnTypes();
        int[] rt = right.getColumnTypes();
        System.arraycopy(lt, 0, outputTypes, 0, lt.length);
        System.arraycopy(rt, 0, outputTypes, lt.length, rt.length);
    }

    private static int indexOf(String[] columns, String name) {
        if (name == null) return -1;
        for (int i = 0; i < columns.length; i++) {
            if (columns[i].equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public Object[] next() throws IOException, SQLException {
        if (!partitioned && !inMemoryMode) {
            for (int i = 0; i < leftKeyIndexes.length; i++) {
                if (leftKeyIndexes[i] < 0 || rightKeyIndexes[i] < 0) {
                    throw new SQLException("Join key column not found in either table");
                }
            }
            if (canBuildInMemory()) {
                buildRightInMemory();
                inMemoryMode = true;
            } else {
                partitionInputs();
                partitioned = true;
            }
        }

        if (inMemoryMode) {
            return nextInMemory();
        }

        while (true) {
            if (!pending.isEmpty()) {
                return pending.poll();
            }

            if (probeIn != null) {
                Object[] rightRow = readOrNull(probeIn);
                if (rightRow == null) {
                    probeIn.close();
                    probeIn = null;
                    // Emit unmatched build rows for LEFT/FULL
                    if (type == HashJoinOperator.JoinType.LEFT
                        || type == HashJoinOperator.JoinType.FULL) {
                        for (Slot slot : buildSlots) {
                            if (!slot.matched) {
                                pending.add(combine(slot.row, null));
                            }
                        }
                    }
                    buildTable = null;
                    buildSlots = null;
                    continue;
                }

                Object key = compositeKey(rightRow, rightKeyIndexes);
                List<Slot> matches = key != null ? buildTable.get(key) : null;
                if (matches != null && !matches.isEmpty()) {
                    for (Slot slot : matches) {
                        slot.matched = true;
                        pending.add(combine(slot.row, rightRow));
                    }
                } else if (type == HashJoinOperator.JoinType.RIGHT
                    || type == HashJoinOperator.JoinType.FULL) {
                    pending.add(combine(null, rightRow));
                }
                continue;
            }

            // Advance to the next partition
            currentPartition++;
            if (currentPartition >= partitionCount) {
                return null;
            }
            loadBuildSide(currentPartition);
            probeIn = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(rightPartitions[currentPartition]), 1 << 16));
        }
    }

    /** Eligible for the in-memory path: INNER/LEFT with a small inner (right) side. */
    private boolean canBuildInMemory() {
        // Escape hatch: -Ddbf.join.inMemory=off forces the disk-partitioned path.
        if ("off".equalsIgnoreCase(System.getProperty("dbf.join.inMemory"))) {
            return false;
        }
        if (type != HashJoinOperator.JoinType.INNER && type != HashJoinOperator.JoinType.LEFT) {
            return false; // RIGHT/FULL need build-side match tracking -> use Grace
        }
        long est = right.estimateRowCount();
        return est >= 0 && est <= MAX_IN_MEMORY_BUILD_ROWS;
    }

    /** Loads the right side into a hash map keyed by the (composite) join key. */
    private void buildRightInMemory() throws IOException, SQLException {
        memRight = new HashMap<>();
        Object[] row;
        while ((row = right.next()) != null) {
            Object key = compositeKey(row, rightKeyIndexes);
            if (key != null) { // a NULL key never matches, so it need not be stored
                memRight.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            }
        }
    }

    /**
     * Streams the left side against the in-memory right hash. Preserves left
     * order; emits each left row's matches (INNER/LEFT) or left+null when a LEFT
     * row has no match. Identical result set to the Grace path.
     */
    private Object[] nextInMemory() throws IOException, SQLException {
        while (true) {
            if (!pending.isEmpty()) {
                return pending.poll();
            }
            Object[] leftRow = left.next();
            if (leftRow == null) {
                return null;
            }
            Object key = compositeKey(leftRow, leftKeyIndexes);
            List<Object[]> matches = key != null ? memRight.get(key) : null;
            if (matches != null && !matches.isEmpty()) {
                for (Object[] r : matches) {
                    pending.add(combine(leftRow, r));
                }
            } else if (type == HashJoinOperator.JoinType.LEFT) {
                pending.add(combine(leftRow, null));
            }
            // INNER with no match: drop the left row and continue.
        }
    }

    private void partitionInputs() throws IOException, SQLException {
        tempDir = Files.createTempDirectory("dbf_join_");
        leftPartitions = createPartitionFiles("left_");
        rightPartitions = createPartitionFiles("right_");

        spillSide(left, leftPartitions, leftKeyIndexes);
        spillSide(right, rightPartitions, rightKeyIndexes);
    }

    private Path[] createPartitionFiles(String prefix) throws IOException {
        Path[] files = new Path[partitionCount];
        for (int i = 0; i < partitionCount; i++) {
            files[i] = Files.createTempFile(tempDir, prefix + i + "_", ".tmp");
        }
        return files;
    }

    private void spillSide(RowStream side, Path[] partitions, int[] keyIndexes)
            throws IOException, SQLException {
        DataOutputStream[] outs = new DataOutputStream[partitionCount];
        try {
            for (int i = 0; i < partitionCount; i++) {
                outs[i] = new DataOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(partitions[i]), 1 << 16));
            }
            Object[] row;
            while ((row = side.next()) != null) {
                Object key = compositeKey(row, keyIndexes);
                RowSerializer.writeRow(outs[partitionOf(key)], row);
            }
        } finally {
            for (DataOutputStream out : outs) {
                if (out != null) {
                    try { out.close(); } catch (IOException ignored) { }
                }
            }
        }
    }

    private int partitionOf(Object key) {
        if (key == null) return 0; // never matches, but must go somewhere
        // Scramble before taking the modulus: integral doubles (1.0, 2.0...)
        // have all-zero low bits, so a raw hashCode() % N would put nearly
        // every numeric key in partition 0
        int h = key.hashCode();
        h ^= (h >>> 20) ^ (h >>> 12);
        h ^= (h >>> 7) ^ (h >>> 4);
        return Math.floorMod(h, partitionCount);
    }

    private void loadBuildSide(int partition) throws IOException {
        buildTable = new HashMap<>();
        buildSlots = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(leftPartitions[partition]), 1 << 16))) {
            Object[] row;
            while ((row = readOrNull(in)) != null) {
                Slot slot = new Slot(row);
                buildSlots.add(slot);
                Object key = compositeKey(row, leftKeyIndexes);
                if (key != null) {
                    buildTable.computeIfAbsent(key, k -> new ArrayList<>()).add(slot);
                }
            }
        }
    }

    private Object[] readOrNull(DataInputStream in) throws IOException {
        try {
            return RowSerializer.readRow(in);
        } catch (EOFException e) {
            return null;
        }
    }

    /**
     * Join keys must compare consistently across types: numbers compare by
     * value (5 matches 5.0), strings are trimmed (DBF pads with spaces).
     */
    /**
     * Builds the join key for a row: a single normalized value for one key
     * column, or a {@code List} of them for a composite key. Returns null when
     * any key component is null, since a NULL join key never matches.
     */
    private static Object compositeKey(Object[] row, int[] keyIndexes) {
        if (keyIndexes.length == 1) {
            int idx = keyIndexes[0];
            return normalizeKey(idx >= 0 && idx < row.length ? row[idx] : null);
        }
        List<Object> key = new ArrayList<>(keyIndexes.length);
        for (int idx : keyIndexes) {
            Object v = normalizeKey(idx >= 0 && idx < row.length ? row[idx] : null);
            if (v == null) {
                return null;
            }
            key.add(v);
        }
        return key;
    }

    private static Object normalizeKey(Object value) {
        if (value == null) return null;
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        String s = value.toString().trim();
        if (s.isEmpty()) return null;
        // A numeric-looking string joins against numeric columns
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return s.toUpperCase();
        }
    }

    private Object[] combine(Object[] leftRow, Object[] rightRow) {
        Object[] out = new Object[leftWidth + rightWidth];
        if (leftRow != null) {
            System.arraycopy(leftRow, 0, out, 0, leftWidth);
        }
        if (rightRow != null) {
            System.arraycopy(rightRow, 0, out, leftWidth, rightWidth);
        }
        return out;
    }

    @Override
    public String[] getColumnNames() {
        return outputColumns;
    }

    @Override
    public int[] getColumnTypes() {
        return outputTypes;
    }

    @Override
    public void reset() throws IOException, SQLException {
        cleanup();
        currentPartition = -1;
        pending.clear();
        partitioned = false;
        inMemoryMode = false;
        left.reset();
        right.reset();
    }

    @Override
    public boolean supportsReset() {
        return left.supportsReset() && right.supportsReset();
    }

    @Override
    public long estimateRowCount() {
        return Math.max(left.estimateRowCount(), right.estimateRowCount());
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            cleanup();
            try { left.close(); } catch (IOException ignored) { }
            right.close();
        }
    }

    private void cleanup() throws IOException {
        if (probeIn != null) {
            try { probeIn.close(); } catch (IOException ignored) { }
            probeIn = null;
        }
        buildTable = null;
        buildSlots = null;
        memRight = null;
        deletePartitions(leftPartitions);
        deletePartitions(rightPartitions);
        leftPartitions = null;
        rightPartitions = null;
        if (tempDir != null) {
            try { Files.deleteIfExists(tempDir); } catch (IOException ignored) { }
            tempDir = null;
        }
    }

    private void deletePartitions(Path[] partitions) {
        if (partitions == null) return;
        for (Path p : partitions) {
            try { Files.deleteIfExists(p); } catch (IOException ignored) { }
        }
    }
}
