package com.dbf.jdbc.index.ntx;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a Clipper-compatible {@code .NTX} index by bulk-loading a balanced
 * B-tree from a sorted set of (key, recno) entries. This is the full-rebuild
 * path -- the safe baseline used both as the standalone writer and as the
 * fallback whenever an incremental update fails its read-back check.
 *
 * <p>Clipper compatibility: the original 1024-byte header is copied verbatim
 * and only three fields are patched -- the update counter (offset 2), the root
 * page offset (4) and the next-free-page pointer (8). Every other header field
 * Clipper wrote (signature, key length/decimals, max items, half page, the key
 * expression, unique/descend flags, ...) is preserved exactly. Pages use the
 * same on-disk layout the reader was verified against: {@code u16 count}, then
 * {@code count+1} {@code u16} slot offsets, then entries
 * {@code [u32 childPageOffset][u32 recno][key]} at stride {@code keyEntrySize}.
 *
 * <p>This class only produces bytes; orchestration (compute entries from the
 * DBF, write to a temp file, verify, atomically replace) lives in the caller.
 */
public final class NtxWriter {

    private NtxWriter() {
    }

    /** A (key, recno) pair to index. Keys must already be the exact Clipper key bytes. */
    public static final class Entry {
        public final byte[] key;
        public final long recno;

        public Entry(byte[] key, long recno) {
            this.key = key;
            this.recno = recno;
        }
    }

    /**
     * Rebuilds {@code outputPath} as a fresh NTX over {@code sortedEntries}
     * (must be ascending by key bytes), reusing the structural parameters and
     * header of {@code templatePath} (an existing NTX for the same key).
     */
    public static void rebuild(String templatePath, String outputPath,
            List<Entry> sortedEntries) throws IOException {
        byte[] header;
        int keyLen;
        int entrySize;
        int maxItems;
        try (NtxIndex tpl = NtxIndex.open(templatePath)) {
            header = tpl.rawPage(0);
            keyLen = tpl.keyLength();
            entrySize = tpl.keyEntrySize();
            maxItems = tpl.maxItems();
        }

        // Build the B-tree in memory, then assign page offsets.
        Node root = bulkLoad(sortedEntries, maxItems);
        List<Node> pages = new ArrayList<>();
        assignPages(root, pages); // root gets page 1 (offset 1024)

        long rootOffset = root == null ? 0 : root.pageOffset;
        long nextFree = (long) (pages.size() + 1) * NtxIndex.PAGE_SIZE;

        // Patch header: update counter (+1), root offset, next-free pointer.
        putU16(header, 2, (u16(header, 2) + 1) & 0xFFFF);
        putU32(header, 4, rootOffset);
        putU32(header, 8, nextFree);

        int entryStart = 2 + (maxItems + 1) * 2;
        try (RandomAccessFile out = new RandomAccessFile(outputPath, "rw")) {
            out.setLength(0);
            out.write(header);
            for (Node node : pages) {
                out.seek(node.pageOffset);
                out.write(serializePage(node, keyLen, entrySize, entryStart, maxItems));
            }
            // Ensure the file ends on the next-free boundary.
            if (out.length() < nextFree) {
                out.setLength(nextFree);
            }
        }
    }

    // ==================== in-memory B-tree ====================

    private static final class Node {
        final boolean leaf;
        final List<byte[]> keys = new ArrayList<>();
        final List<Long> recnos = new ArrayList<>();
        final List<Node> children = new ArrayList<>(); // size = keys+1 when !leaf
        long pageOffset;

        Node(boolean leaf) {
            this.leaf = leaf;
        }
    }

    private static final class Split {
        final byte[] key;
        final long recno;
        final Node right;

        Split(byte[] key, long recno, Node right) {
            this.key = key;
            this.recno = recno;
            this.right = right;
        }
    }

    /** Bulk-loads by inserting the (already sorted) entries into a B-tree. */
    private static Node bulkLoad(List<Entry> entries, int maxItems) {
        Node root = new Node(true);
        for (Entry e : entries) {
            Split s = insert(root, e.key, e.recno, maxItems);
            if (s != null) {
                Node newRoot = new Node(false);
                newRoot.keys.add(s.key);
                newRoot.recnos.add(s.recno);
                newRoot.children.add(root);
                newRoot.children.add(s.right);
                root = newRoot;
            }
        }
        return entries.isEmpty() ? null : root;
    }

    private static Split insert(Node node, byte[] key, long recno, int maxItems) {
        int pos = lowerBound(node.keys, key);
        if (node.leaf) {
            node.keys.add(pos, key);
            node.recnos.add(pos, recno);
        } else {
            Split s = insert(node.children.get(pos), key, recno, maxItems);
            if (s != null) {
                node.keys.add(pos, s.key);
                node.recnos.add(pos, s.recno);
                node.children.add(pos + 1, s.right);
            }
        }
        if (node.keys.size() > maxItems) {
            return splitNode(node);
        }
        return null;
    }

    private static Split splitNode(Node node) {
        int mid = node.keys.size() / 2;
        byte[] midKey = node.keys.get(mid);
        long midRecno = node.recnos.get(mid);

        Node right = new Node(node.leaf);
        right.keys.addAll(node.keys.subList(mid + 1, node.keys.size()));
        right.recnos.addAll(node.recnos.subList(mid + 1, node.recnos.size()));
        if (!node.leaf) {
            right.children.addAll(node.children.subList(mid + 1, node.children.size()));
        }

        // Trim the left node down to [0, mid).
        node.keys.subList(mid, node.keys.size()).clear();
        node.recnos.subList(mid, node.recnos.size()).clear();
        if (!node.leaf) {
            node.children.subList(mid + 1, node.children.size()).clear();
        }
        return new Split(midKey, midRecno, right);
    }

    /** First index whose key is >= the given key (unsigned byte comparison). */
    private static int lowerBound(List<byte[]> keys, byte[] key) {
        int lo = 0;
        int hi = keys.size();
        while (lo < hi) {
            int m = (lo + hi) >>> 1;
            if (compare(keys.get(m), key) < 0) {
                lo = m + 1;
            } else {
                hi = m;
            }
        }
        return lo;
    }

    /** Unsigned byte-wise comparison (Clipper key collation). */
    public static int compare(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = a[i] & 0xFF;
            int y = b[i] & 0xFF;
            if (x != y) {
                return x < y ? -1 : 1;
            }
        }
        return Integer.compare(a.length, b.length);
    }

    // ==================== page assignment / serialization ====================

    /** Assigns each node a 1024-aligned page offset (page 0 is the header). */
    private static void assignPages(Node root, List<Node> out) {
        if (root == null) {
            return;
        }
        // Breadth-first so layout is stable and parents precede children.
        List<Node> queue = new ArrayList<>();
        queue.add(root);
        int i = 0;
        while (i < queue.size()) {
            Node n = queue.get(i++);
            n.pageOffset = (long) (out.size() + 1) * NtxIndex.PAGE_SIZE;
            out.add(n);
            if (!n.leaf) {
                queue.addAll(n.children);
            }
        }
    }

    private static byte[] serializePage(Node node, int keyLen, int entrySize,
            int entryStart, int maxItems) {
        byte[] page = new byte[NtxIndex.PAGE_SIZE];
        int count = node.keys.size();
        putU16(page, 0, count);

        for (int i = 0; i <= count; i++) {
            int slot = entryStart + i * entrySize;
            putU16(page, 2 + i * 2, slot);

            long child = node.leaf ? 0 : node.children.get(i).pageOffset;
            putU32(page, slot, child);
            if (i < count) {
                putU32(page, slot + 4, node.recnos.get(i));
                byte[] key = node.keys.get(i);
                int len = Math.min(keyLen, key.length);
                System.arraycopy(key, 0, page, slot + 8, len);
                // pad remainder with spaces (matches fixed-width key bytes)
                for (int p = len; p < keyLen; p++) {
                    page[slot + 8 + p] = ' ';
                }
            }
        }
        return page;
    }

    // ==================== little-endian helpers ====================

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static void putU16(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >>> 8) & 0xFF);
    }

    private static void putU32(byte[] b, int off, long v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >>> 8) & 0xFF);
        b[off + 2] = (byte) ((v >>> 16) & 0xFF);
        b[off + 3] = (byte) ((v >>> 24) & 0xFF);
    }

    /** Convenience: the exact key bytes a string contributes (ISO-8859-1). */
    public static byte[] keyBytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }
}
