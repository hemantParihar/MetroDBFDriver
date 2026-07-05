package com.dbf.jdbc.index.ntx;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Incremental, on-disk insert into a Clipper {@code .NTX} B-tree: descend to
 * the target leaf, insert the key, and split pages bottom-up as needed,
 * touching only the pages on the root-to-leaf path plus any newly allocated
 * split pages -- O(log n) I/O instead of rebuilding the whole index.
 *
 * <p>New pages are appended at the end of the file and the header's next-free
 * pointer is advanced; the page layout written is identical to what
 * {@link NtxWriter} produces and {@link NtxIndex} reads. After updating, the
 * caller re-opens the index and checks it is still a valid sorted tree of the
 * expected size; if anything is off it falls back to a full rebuild, so a bug
 * here can never leave a corrupt index in place.
 */
public final class NtxUpdater implements Closeable {

    private final RandomAccessFile raf;
    private final int keyLen;
    private final int entrySize;
    private final int maxItems;
    private final int entryStart;
    private long rootOffset;
    private long nextFree;
    private int updateCounter;

    public NtxUpdater(String path) throws IOException {
        this.raf = new RandomAccessFile(path, "rw");
        byte[] header = new byte[NtxIndex.PAGE_SIZE];
        raf.seek(0);
        raf.readFully(header);
        this.updateCounter = u16(header, 2);
        this.rootOffset = u32(header, 4);
        this.nextFree = u32(header, 8);
        this.entrySize = u16(header, 12);
        this.keyLen = u16(header, 14);
        this.maxItems = u16(header, 18);
        this.entryStart = 2 + (maxItems + 1) * 2;
        if (nextFree < NtxIndex.PAGE_SIZE) {
            nextFree = Math.max(raf.length(), NtxIndex.PAGE_SIZE);
        }
    }

    /** Inserts one (key, recno). Key must be exactly {@code keyLen} bytes (padded). */
    public void insert(byte[] keyIn, long recno) throws IOException {
        byte[] key = fit(keyIn);

        if (rootOffset == 0) {
            Node leaf = new Node(true);
            leaf.keys.add(key);
            leaf.recnos.add(recno);
            long off = allocate();
            writeNode(off, leaf);
            rootOffset = off;
            return;
        }

        // Descend to the leaf, recording the child index taken at each level.
        List<Frame> path = new ArrayList<>();
        long off = rootOffset;
        while (true) {
            Node node = readNode(off);
            int pos = lowerBound(node.keys, key);
            path.add(new Frame(off, node, pos));
            if (node.leaf) {
                break;
            }
            off = node.children.get(pos);
        }

        // Insert into the leaf.
        Frame leafFrame = path.get(path.size() - 1);
        leafFrame.node.keys.add(leafFrame.pos, key);
        leafFrame.node.recnos.add(leafFrame.pos, recno);

        // Walk back up, splitting overflowed nodes.
        Promotion promo = null;
        for (int level = path.size() - 1; level >= 0; level--) {
            Frame fr = path.get(level);
            Node node = fr.node;

            if (promo != null) {
                // A child split below: insert the promoted separator here.
                node.keys.add(fr.pos, promo.key);
                node.recnos.add(fr.pos, promo.recno);
                node.children.add(fr.pos + 1, promo.rightPage);
                promo = null;
            }

            if (node.keys.size() <= maxItems) {
                writeNode(fr.offset, node);
                continue; // no split; ancestors unchanged above the promo point
            }

            // Split this node; left stays at fr.offset, right is a new page.
            int mid = node.keys.size() / 2;
            byte[] midKey = node.keys.get(mid);
            long midRecno = node.recnos.get(mid);
            Node right = node.splitOff(mid);

            long rightOff = allocate();
            writeNode(fr.offset, node);
            writeNode(rightOff, right);
            promo = new Promotion(midKey, midRecno, rightOff);

            if (level == 0) {
                // Root split -> new root holding the single promoted key.
                Node newRoot = new Node(false);
                newRoot.keys.add(midKey);
                newRoot.recnos.add(midRecno);
                newRoot.children.add(fr.offset);
                newRoot.children.add(rightOff);
                long newRootOff = allocate();
                writeNode(newRootOff, newRoot);
                rootOffset = newRootOff;
            }
        }
    }

    /**
     * Removes the entry ({@code key}, {@code recno}) when it lives in a leaf
     * that stays non-empty -- the common case for an UPDATE's old key. Returns
     * false (changing nothing) for the harder cases: key held in an internal
     * node, or a leaf that would become empty, or not found. The caller then
     * falls back to a full rebuild, so balance/merge logic is never needed here.
     */
    public boolean delete(byte[] keyIn, long recno) throws IOException {
        byte[] key = fit(keyIn);
        if (rootOffset == 0) {
            return false;
        }
        long off = rootOffset;
        while (true) {
            Node node = readNode(off);
            int pos = lowerBound(node.keys, key);
            boolean here = pos < node.keys.size()
                && NtxWriter.compare(node.keys.get(pos), key) == 0
                && node.recnos.get(pos) == recno;
            if (here) {
                if (!node.leaf) {
                    return false; // internal-node delete -> rebuild
                }
                node.keys.remove(pos);
                node.recnos.remove(pos);
                node.children.remove(node.children.size() - 1); // keep children = keys+1
                if (node.keys.isEmpty()) {
                    return false; // empty leaf needs parent fixup -> rebuild
                }
                writeNode(off, node);
                return true;
            }
            if (node.leaf) {
                return false; // not found -> rebuild
            }
            off = node.children.get(pos);
        }
    }

    @Override
    public void close() throws IOException {
        // Flush header: bump update counter, store root + next-free.
        byte[] header = new byte[NtxIndex.PAGE_SIZE];
        raf.seek(0);
        raf.readFully(header);
        putU16(header, 2, (updateCounter + 1) & 0xFFFF);
        putU32(header, 4, rootOffset);
        putU32(header, 8, nextFree);
        raf.seek(0);
        raf.write(header);
        raf.close();
    }

    // ==================== page node ====================

    private static final class Node {
        final boolean leaf;
        final List<byte[]> keys = new ArrayList<>();
        final List<Long> recnos = new ArrayList<>();
        final List<Long> children = new ArrayList<>(); // page offsets; 0 for leaf slots

        Node(boolean leaf) {
            this.leaf = leaf;
        }

        /** Splits off entries (mid, end) into a new right node; drops the median. */
        Node splitOff(int mid) {
            Node right = new Node(leaf);
            right.keys.addAll(keys.subList(mid + 1, keys.size()));
            right.recnos.addAll(recnos.subList(mid + 1, recnos.size()));
            if (!leaf) {
                right.children.addAll(children.subList(mid + 1, children.size()));
            }
            keys.subList(mid, keys.size()).clear();
            recnos.subList(mid, recnos.size()).clear();
            if (!leaf) {
                children.subList(mid + 1, children.size()).clear();
            }
            return right;
        }
    }

    private static final class Frame {
        final long offset;
        final Node node;
        final int pos;

        Frame(long offset, Node node, int pos) {
            this.offset = offset;
            this.node = node;
            this.pos = pos;
        }
    }

    private static final class Promotion {
        final byte[] key;
        final long recno;
        final long rightPage;

        Promotion(byte[] key, long recno, long rightPage) {
            this.key = key;
            this.recno = recno;
            this.rightPage = rightPage;
        }
    }

    // ==================== page I/O ====================

    private Node readNode(long offset) throws IOException {
        byte[] page = new byte[NtxIndex.PAGE_SIZE];
        raf.seek(offset);
        raf.readFully(page);
        int count = u16(page, 0);

        long[] children = new long[count + 1];
        long[] recnos = new long[count];
        byte[][] keys = new byte[count][];
        for (int i = 0; i <= count; i++) {
            int slot = u16(page, 2 + i * 2);
            children[i] = u32(page, slot);
            if (i < count) {
                recnos[i] = u32(page, slot + 4);
                keys[i] = new byte[keyLen];
                System.arraycopy(page, slot + 8, keys[i], 0, keyLen);
            }
        }
        boolean leaf = children[0] == 0; // internal nodes have non-zero child offsets
        Node node = new Node(leaf);
        for (int i = 0; i < count; i++) {
            node.keys.add(keys[i]);
            node.recnos.add(recnos[i]);
        }
        for (int i = 0; i <= count; i++) {
            node.children.add(children[i]);
        }
        return node;
    }

    private void writeNode(long offset, Node node) throws IOException {
        byte[] page = new byte[NtxIndex.PAGE_SIZE];
        int count = node.keys.size();
        putU16(page, 0, count);
        for (int i = 0; i <= count; i++) {
            int slot = entryStart + i * entrySize;
            putU16(page, 2 + i * 2, slot);
            long child = node.leaf ? 0 : node.children.get(i);
            putU32(page, slot, child);
            if (i < count) {
                putU32(page, slot + 4, node.recnos.get(i));
                byte[] key = node.keys.get(i);
                System.arraycopy(key, 0, page, slot + 8, Math.min(keyLen, key.length));
            }
        }
        raf.seek(offset);
        raf.write(page);
    }

    private long allocate() throws IOException {
        long off = nextFree;
        nextFree += NtxIndex.PAGE_SIZE;
        if (raf.length() < nextFree) {
            raf.setLength(nextFree);
        }
        return off;
    }

    private byte[] fit(byte[] in) {
        if (in.length == keyLen) {
            return in;
        }
        byte[] k = new byte[keyLen];
        int n = Math.min(in.length, keyLen);
        System.arraycopy(in, 0, k, 0, n);
        for (int i = n; i < keyLen; i++) {
            k[i] = ' ';
        }
        return k;
    }

    private int lowerBound(List<byte[]> keys, byte[] key) {
        int lo = 0;
        int hi = keys.size();
        while (lo < hi) {
            int m = (lo + hi) >>> 1;
            if (NtxWriter.compare(keys.get(m), key) < 0) {
                lo = m + 1;
            } else {
                hi = m;
            }
        }
        return lo;
    }

    // ==================== little-endian ====================

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] b, int o) {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8)
            | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
    }

    private static void putU16(byte[] b, int o, int v) {
        b[o] = (byte) (v & 0xFF);
        b[o + 1] = (byte) ((v >>> 8) & 0xFF);
    }

    private static void putU32(byte[] b, int o, long v) {
        b[o] = (byte) (v & 0xFF);
        b[o + 1] = (byte) ((v >>> 8) & 0xFF);
        b[o + 2] = (byte) ((v >>> 16) & 0xFF);
        b[o + 3] = (byte) ((v >>> 24) & 0xFF);
    }
}
