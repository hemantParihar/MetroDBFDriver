package com.dbf.jdbc.tx;

import com.dbf.jdbc.index.ntx.NtxMaintainer;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A manual transaction (used when {@code autoCommit} is off): records enough to
 * undo every change so {@link #rollback()} can restore the prior state, and
 * holds an exclusive {@link TableLock} on each touched table for isolation.
 *
 * <p>Undo strategy (no whole-file copy): at the first write to a table we note
 * its original record count; for every record an UPDATE/DELETE overwrites we
 * keep its original bytes. {@link #rollback()} rewrites those bytes and
 * truncates the file back to the original count (which drops any appended
 * INSERTs), then fixes the header count and EOF marker.
 *
 * <p>Indexes are left untouched during the transaction: {@link #commit()}
 * rebuilds the touched tables' indexes from the final data, and after a
 * rollback the indexes still match the restored (original) data, so no
 * index-specific undo is needed.
 */
public final class Transaction {

    private final String folder;
    private final Charset charset;
    private final boolean maintainIndexes;
    private final LockScheme lockScheme;
    private final Map<String, TableTxn> tables = new LinkedHashMap<>();

    public Transaction(String folder, Charset charset, boolean maintainIndexes, LockScheme lockScheme) {
        this.folder = folder;
        this.charset = charset;
        this.maintainIndexes = maintainIndexes;
        this.lockScheme = lockScheme;
    }

    /** Whether this transaction has already locked/baselined the given table. */
    public boolean hasTable(String tableName) {
        return tables.containsKey(tableName.toUpperCase());
    }

    /**
     * Locks {@code tableName} and records its baseline (record count, geometry).
     * Idempotent: a no-op if already begun. Must be called before any write.
     */
    public void beginTable(String tableName) throws IOException {
        String key = tableName.toUpperCase();
        if (tables.containsKey(key)) {
            return;
        }
        String path = folder + "/" + tableName + ".dbf";
        WriteLock lock = lockScheme.acquire(path);
        try {
            TableTxn t = new TableTxn(tableName, path, lock);
            t.readBaseline();
            tables.put(key, t);
        } catch (IOException | RuntimeException e) {
            lock.close();
            throw e;
        }
    }

    /** Saves a record's original bytes before an UPDATE/DELETE overwrites it. */
    public void captureOriginal(String tableName, int recno, byte[] originalBytes) {
        TableTxn t = tables.get(tableName.toUpperCase());
        if (t == null) {
            return;
        }
        // Only records that existed at baseline need byte-restoration; appended
        // rows are removed by truncation. First capture wins (original state).
        if (recno <= t.originalCount && !t.oldRecords.containsKey(recno)) {
            t.oldRecords.put(recno, originalBytes.clone());
        }
    }

    /** Finalizes: rebuild touched indexes from committed data, then release locks. */
    public void commit() {
        try {
            if (maintainIndexes) {
                for (TableTxn t : tables.values()) {
                    NtxMaintainer.rebuildAll(folder, t.name, charset);
                }
            }
        } finally {
            releaseAll();
        }
    }

    /** Undoes every change, then releases locks. */
    public void rollback() {
        try {
            for (TableTxn t : tables.values()) {
                try {
                    t.undo();
                } catch (IOException e) {
                    // Continue undoing other tables; a failure here is already
                    // a serious condition but we must still release locks.
                }
            }
        } finally {
            releaseAll();
        }
    }

    private void releaseAll() {
        for (TableTxn t : tables.values()) {
            t.lock.close();
        }
        tables.clear();
    }

    // ==================== per-table state ====================

    private static final class TableTxn {
        final String name;
        final String path;
        final WriteLock lock;
        int headerSize;
        int recordSize;
        long originalCount;
        final Map<Integer, byte[]> oldRecords = new LinkedHashMap<>();

        TableTxn(String name, String path, WriteLock lock) {
            this.name = name;
            this.path = path;
            this.lock = lock;
        }

        void readBaseline() throws IOException {
            try (RandomAccessFile raf = new RandomAccessFile(path, "r")) {
                byte[] h = new byte[32];
                raf.readFully(h);
                this.originalCount = u32(h, 4);
                this.headerSize = u16(h, 8);
                this.recordSize = u16(h, 10);
            }
        }

        void undo() throws IOException {
            try (RandomAccessFile raf = new RandomAccessFile(path, "rw")) {
                // 1. Restore overwritten records.
                for (Map.Entry<Integer, byte[]> e : oldRecords.entrySet()) {
                    long offset = headerSize + (long) (e.getKey() - 1) * recordSize;
                    raf.seek(offset);
                    raf.write(e.getValue());
                }
                // 2. Drop appended rows: truncate to the original record count.
                long dataEnd = headerSize + originalCount * recordSize;
                raf.setLength(dataEnd + 1);
                raf.seek(dataEnd);
                raf.write(0x1A); // EOF marker
                // 3. Restore the header record count (little-endian uint32 @ offset 4).
                raf.seek(4);
                raf.write((int) (originalCount & 0xFF));
                raf.write((int) ((originalCount >>> 8) & 0xFF));
                raf.write((int) ((originalCount >>> 16) & 0xFF));
                raf.write((int) ((originalCount >>> 24) & 0xFF));
            }
        }

        private static int u16(byte[] b, int o) {
            return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
        }

        private static long u32(byte[] b, int o) {
            return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8)
                | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
        }
    }
}
