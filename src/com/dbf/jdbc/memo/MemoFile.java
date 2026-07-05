package com.dbf.jdbc.memo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * DBF memo file handler for .DBT (dBASE III/IV) and .FPT (FoxPro) formats.
 *
 * Format facts (verified against the DANS DBF library and the xBase spec):
 * - The memo block number in the .DBF record is ASCII digits, not binary.
 * - dBASE III memo text is terminated by 0x1A and may span many blocks.
 * - dBASE IV memo blocks start with the magic FF FF 08 00 followed by a
 *   little-endian length that INCLUDES the 8-byte block header.
 * - FoxPro .FPT block headers are big-endian: 4-byte type (1 = text),
 *   4-byte data length (data only, header not included).
 * - The .DBT header stores the next free block at offset 0 (little-endian);
 *   .FPT stores it at offset 0 big-endian. dBASE IV also stores the block
 *   size at offset 20 (little-endian short).
 */
public class MemoFile {
    private static final int DBT_DEFAULT_BLOCK_SIZE = 512;
    private static final int FPT_DEFAULT_BLOCK_SIZE = 64;
    private static final byte MEMO_END_MARKER = 0x1A;
    private static final int DBASE4_BLOCK_MAGIC = 0xFFFF0800;
    private static final int MAX_MEMO_BYTES = 16 * 1024 * 1024; // corruption guard

    private final RandomAccessFile file;
    private MemoType type;
    private final Charset charset;
    private final int blockSize;
    private boolean closed = false;

    public enum MemoType {
        DBT,    // dBASE III/IV memo
        FPT     // FoxPro memo
    }

    public MemoFile(String dbfFilePath, Charset charset) throws IOException {
        this(dbfFilePath, charset, false);
    }

    /**
     * @param dbfFilePath path of the .dbf file (the memo path is derived)
     * @param writable    open read-write; creates an empty dBASE III .DBT
     *                    when no memo file exists yet
     */
    public MemoFile(String dbfFilePath, Charset charset, boolean writable) throws IOException {
        this.charset = charset != null ? charset : StandardCharsets.UTF_8;
        String mode = writable ? "rw" : "r";

        String basePath = dbfFilePath.substring(0, dbfFilePath.length() - 4);
        java.io.File fptFile = new java.io.File(basePath + ".fpt");
        java.io.File dbtFile = new java.io.File(basePath + ".dbt");

        if (fptFile.exists()) {
            this.type = MemoType.FPT;
            this.file = new RandomAccessFile(fptFile, mode);
            // FPT header is big-endian; block size is at offset 6-7
            int size = 0;
            if (this.file.length() >= 8) {
                this.file.seek(6);
                size = this.file.readUnsignedShort();
            }
            this.blockSize = size > 0 ? size : FPT_DEFAULT_BLOCK_SIZE;
        } else if (dbtFile.exists()) {
            this.type = MemoType.DBT;
            this.file = new RandomAccessFile(dbtFile, mode);
            // dBASE IV stores the block size at offset 20 (little-endian);
            // dBASE III leaves it zero, meaning 512
            int size = 0;
            if (this.file.length() >= 22) {
                this.file.seek(20);
                int lo = this.file.read();
                int hi = this.file.read();
                size = (lo & 0xFF) | ((hi & 0xFF) << 8);
            }
            this.blockSize = size >= 16 ? size : DBT_DEFAULT_BLOCK_SIZE;
        } else if (writable) {
            // Create an empty dBASE III .DBT
            this.type = MemoType.DBT;
            this.file = new RandomAccessFile(dbtFile, "rw");
            this.blockSize = DBT_DEFAULT_BLOCK_SIZE;
            byte[] header = new byte[DBT_DEFAULT_BLOCK_SIZE];
            header[0] = 1; // next free block (little-endian) = 1
            header[16] = 0x03; // dBASE III version marker
            this.file.write(header);
        } else {
            this.file = null;
            this.type = MemoType.DBT;
            this.blockSize = DBT_DEFAULT_BLOCK_SIZE;
        }
    }

    /** Reads the memo text starting at the given block, or null for block 0. */
    public String readMemo(int blockNumber) throws IOException {
        if (blockNumber <= 0 || file == null) return null;

        long offset = (long) blockNumber * blockSize;
        if (offset >= file.length()) {
            return "";
        }
        file.seek(offset);

        if (type == MemoType.FPT) {
            // Big-endian block header: type, then data length (data only)
            int typeMarker = file.readInt();
            int length = file.readInt();
            if (length <= 0) return "";
            byte[] data = readBounded(length);
            if (typeMarker == 1) {
                return new String(data, charset);
            }
            // Pictures/objects: return as ISO-8859-1 so bytes survive
            return new String(data, StandardCharsets.ISO_8859_1);
        }

        // DBT: distinguish dBASE IV blocks by their magic number
        int first = file.readInt();
        if (first == DBASE4_BLOCK_MAGIC) {
            // dBASE IV: little-endian length that includes this 8-byte header
            int lengthWithHeader = Integer.reverseBytes(file.readInt());
            int length = lengthWithHeader - 8;
            if (length <= 0) return "";
            return new String(readBounded(length), charset);
        }

        // dBASE III: text runs until 0x1A, possibly across many blocks.
        // The four bytes just read are part of the text.
        file.seek(offset);
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        byte[] chunk = new byte[blockSize];
        long remaining = file.length() - offset;
        while (remaining > 0 && text.size() < MAX_MEMO_BYTES) {
            int n = file.read(chunk, 0, (int) Math.min(chunk.length, remaining));
            if (n <= 0) break;
            for (int i = 0; i < n; i++) {
                if (chunk[i] == MEMO_END_MARKER) {
                    return new String(text.toByteArray(), charset);
                }
                text.write(chunk[i]);
            }
            remaining -= n;
        }
        return new String(text.toByteArray(), charset);
    }

    private byte[] readBounded(int length) throws IOException {
        int safe = (int) Math.min(Math.min(length, MAX_MEMO_BYTES),
            file.length() - file.getFilePointer());
        if (safe <= 0) return new byte[0];
        byte[] data = new byte[safe];
        file.readFully(data);
        return data;
    }

    /**
     * Appends the text as a new memo entry and returns its block number.
     * Blocks are zero-padded to the block boundary and the next-free-block
     * pointer in the file header is updated.
     */
    public int writeMemo(String text) throws IOException {
        if (file == null) {
            throw new IOException("Memo file not available for writing");
        }

        byte[] textBytes = text.getBytes(charset);
        int blockIndex = readNextFreeBlock();
        if (blockIndex < 1) {
            blockIndex = (int) Math.max(1, (file.length() + blockSize - 1) / blockSize);
        }

        int totalBytes;
        file.seek((long) blockIndex * blockSize);
        if (type == MemoType.FPT) {
            file.writeInt(1); // type: text (big-endian)
            file.writeInt(textBytes.length); // data length (big-endian)
            file.write(textBytes);
            totalBytes = textBytes.length + 8;
        } else {
            // dBASE III convention: text followed by two 0x1A markers
            file.write(textBytes);
            file.writeByte(MEMO_END_MARKER);
            file.writeByte(MEMO_END_MARKER);
            totalBytes = textBytes.length + 2;
        }

        // Pad the final block with zeros
        int blocksUsed = (totalBytes + blockSize - 1) / blockSize;
        long endOfBlocks = (long) (blockIndex + blocksUsed) * blockSize;
        long padding = endOfBlocks - file.getFilePointer();
        if (padding > 0) {
            file.write(new byte[(int) padding]);
        }

        writeNextFreeBlock(blockIndex + blocksUsed);
        return blockIndex;
    }

    private int readNextFreeBlock() throws IOException {
        if (file.length() < 4) return 1;
        file.seek(0);
        int raw = file.readInt();
        // .DBT stores it little-endian, .FPT big-endian
        return type == MemoType.DBT ? Integer.reverseBytes(raw) : raw;
    }

    private void writeNextFreeBlock(int block) throws IOException {
        file.seek(0);
        file.writeInt(type == MemoType.DBT ? Integer.reverseBytes(block) : block);
    }

    public boolean hasMemoFile() {
        return file != null;
    }

    public MemoType getType() {
        return type;
    }

    public int getBlockSize() {
        return blockSize;
    }

    public void close() throws IOException {
        if (!closed && file != null) {
            file.close();
            closed = true;
        }
    }
}
