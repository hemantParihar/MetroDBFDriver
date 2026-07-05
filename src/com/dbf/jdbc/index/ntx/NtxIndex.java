package com.dbf.jdbc.index.ntx;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Read-only reader for a Clipper {@code .NTX} index file.
 *
 * <p>NTX is a paged B-tree. The first 1024-byte page is a header; every other
 * page holds an ordered set of key entries plus child-page pointers. Each entry
 * is {@code [uint32 childPageOffset][uint32 recordNumber][key bytes]}; a page
 * begins with a {@code uint16} count followed by {@code count+1} {@code uint16}
 * slot offsets (the extra one is the right-most child pointer). All integers
 * are little-endian. An in-order walk therefore yields keys in collation order
 * with their 1-based DBF record numbers.
 *
 * <p>This class never writes; it cannot corrupt the index. See the package for
 * the (separate, opt-in) writer.
 */
public final class NtxIndex implements Closeable {

    public static final int PAGE_SIZE = 1024;

    private final RandomAccessFile file;
    private final int signature;
    private final long rootPage;
    private final int keyEntrySize;   // bytes reserved per entry slot
    private final int keyLength;       // bytes of key material
    private final int keyDecimals;
    private final int maxItems;
    private final int halfPage;
    private final String keyExpression;

    private NtxIndex(RandomAccessFile file, int signature, long rootPage,
            int keyEntrySize, int keyLength, int keyDecimals, int maxItems,
            int halfPage, String keyExpression) {
        this.file = file;
        this.signature = signature;
        this.rootPage = rootPage;
        this.keyEntrySize = keyEntrySize;
        this.keyLength = keyLength;
        this.keyDecimals = keyDecimals;
        this.maxItems = maxItems;
        this.halfPage = halfPage;
        this.keyExpression = keyExpression;
    }

    /** Opens an NTX file and parses its header. Caller must {@link #close()}. */
    public static NtxIndex open(String path) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(path, "r");
        try {
            byte[] header = new byte[PAGE_SIZE];
            raf.readFully(header);
            int signature = u16(header, 0);
            long rootPage = u32(header, 4);
            int keyEntrySize = u16(header, 12);
            int keyLength = u16(header, 14);
            int keyDecimals = u16(header, 16);
            int maxItems = u16(header, 18);
            int halfPage = u16(header, 20);
            String keyExpr = cString(header, 22, 256);
            return new NtxIndex(raf, signature, rootPage, keyEntrySize, keyLength,
                keyDecimals, maxItems, halfPage, keyExpr);
        } catch (IOException e) {
            raf.close();
            throw e;
        }
    }

    public String keyExpression() {
        return keyExpression;
    }

    public int keyLength() {
        return keyLength;
    }

    public int keyEntrySize() {
        return keyEntrySize;
    }

    public int keyDecimals() {
        return keyDecimals;
    }

    public int maxItems() {
        return maxItems;
    }

    public int halfPage() {
        return halfPage;
    }

    public int signature() {
        return signature;
    }

    public long rootPage() {
        return rootPage;
    }

    /** Raw bytes of the page at the given file offset (for inspection/writing). */
    public byte[] rawPage(long offset) throws IOException {
        return readPage(offset);
    }

    /** Number of used keys on the page at {@code offset}. */
    public int pageCount(long offset) throws IOException {
        return u16(readPage(offset), 0);
    }

    /** One (key, recordNumber) pair from the index, in collation order. */
    public static final class Entry {
        public final byte[] key;
        public final long recordNumber; // 1-based DBF record number

        Entry(byte[] key, long recordNumber) {
            this.key = key;
            this.recordNumber = recordNumber;
        }

        public String keyString() {
            return new String(key, StandardCharsets.ISO_8859_1);
        }
    }

    /** Receives entries during a walk; return false to stop early. */
    public interface EntryVisitor {
        boolean visit(Entry entry) throws IOException;
    }

    /** In-order walk of the whole index (keys ascending). */
    public void forEach(EntryVisitor visitor) throws IOException {
        walk(rootPage, null, visitor);
    }

    /**
     * Returns up to {@code limit} record numbers whose key begins with
     * {@code prefix}, in index (ascending) order. {@code limit <= 0} means all.
     * The walk is pruned to the matching key range, so this is the seek that
     * makes prefix/equality lookups fast.
     */
    public List<Long> seekPrefix(byte[] prefix, int limit) throws IOException {
        List<Long> out = new ArrayList<>();
        walk(rootPage, prefix, entry -> {
            if (startsWith(entry.key, prefix)) {
                out.add(entry.recordNumber);
                return limit <= 0 || out.size() < limit;
            }
            return true;
        });
        return out;
    }

    /**
     * Recursive in-order traversal. When {@code prefix} is non-null the
     * descent skips subtrees that cannot contain a matching key.
     */
    private boolean walk(long pageOffset, byte[] prefix, EntryVisitor visitor)
            throws IOException {
        if (pageOffset <= 0) {
            return true;
        }
        byte[] page = readPage(pageOffset);
        int count = u16(page, 0);

        for (int i = 0; i < count; i++) {
            int slot = u16(page, 2 + i * 2);
            long child = u32(page, slot);
            long recno = u32(page, slot + 4);
            byte[] key = new byte[keyLength];
            System.arraycopy(page, slot + 8, key, 0, keyLength);

            // Prune: if a prefix is given and this key is already past it,
            // the left child may still hold matches, but nothing after does.
            int cmp = prefix == null ? -1 : comparePrefix(key, prefix);

            if (!walk(child, prefix, visitor)) {
                return false;
            }
            if (prefix == null || cmp == 0) {
                if (!visitor.visit(new Entry(key, recno))) {
                    return false;
                }
            }
            if (prefix != null && cmp > 0) {
                // This key is beyond the prefix range; later keys are larger
                // still, so we are done with this page and its right subtrees.
                return true;
            }
        }
        // Right-most child pointer (slot at index == count).
        int lastSlot = u16(page, 2 + count * 2);
        long lastChild = u32(page, lastSlot);
        return walk(lastChild, prefix, visitor);
    }

    private byte[] readPage(long offset) throws IOException {
        byte[] page = new byte[PAGE_SIZE];
        file.seek(offset);
        file.readFully(page);
        return page;
    }

    /** -1 if key < prefix-range, 0 if key starts with prefix, +1 if key > range. */
    private static int comparePrefix(byte[] key, byte[] prefix) {
        int n = Math.min(key.length, prefix.length);
        for (int i = 0; i < n; i++) {
            int a = key[i] & 0xFF;
            int b = prefix[i] & 0xFF;
            if (a != b) {
                return a < b ? -1 : 1;
            }
        }
        return key.length >= prefix.length ? 0 : -1;
    }

    private static boolean startsWith(byte[] key, byte[] prefix) {
        if (key.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (key[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() throws IOException {
        file.close();
    }

    // ==================== little-endian helpers ====================

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] b, int off) {
        return (b[off] & 0xFFL)
            | ((b[off + 1] & 0xFFL) << 8)
            | ((b[off + 2] & 0xFFL) << 16)
            | ((b[off + 3] & 0xFFL) << 24);
    }

    private static String cString(byte[] b, int off, int max) {
        int end = off;
        int limit = Math.min(b.length, off + max);
        while (end < limit && b[end] != 0) {
            end++;
        }
        return new String(b, off, end - off, StandardCharsets.ISO_8859_1).trim();
    }

    // Suppress unused-field warnings while these are reserved for the writer/planner.
    @SuppressWarnings("unused")
    private int reserved() {
        return keyEntrySize + keyDecimals + maxItems + halfPage;
    }

    @SuppressWarnings("unused")
    private static boolean unusedPredicateHook(Predicate<byte[]> p, byte[] k) {
        return p.test(k);
    }
}
