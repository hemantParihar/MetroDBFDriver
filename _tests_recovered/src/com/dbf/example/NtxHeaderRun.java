package com.dbf.example;

import com.dbf.jdbc.index.ntx.NtxIndex;

/** Dumps NTX header params and the root-page slot layout, to nail the format. */
public class NtxHeaderRun {
    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "E:/METRO/sg20/MASTER1.NTX";
        try (NtxIndex idx = NtxIndex.open(path)) {
            int keyLen = idx.keyLength();
            int entrySize = idx.keyEntrySize();
            int maxItems = idx.maxItems();
            System.out.println("path        : " + path);
            System.out.println("signature   : 0x" + Integer.toHexString(idx.signature()));
            System.out.println("rootPage    : " + idx.rootPage());
            System.out.println("keyEntrySize: " + entrySize + "   (keyLen+8 = " + (keyLen + 8) + ")");
            System.out.println("keyLength   : " + keyLen);
            System.out.println("keyDecimals : " + idx.keyDecimals());
            System.out.println("maxItems    : " + maxItems);
            System.out.println("halfPage    : " + idx.halfPage());

            int entryStart = 2 + (maxItems + 1) * 2;
            System.out.println("entryStart(2+(maxItems+1)*2) = " + entryStart
                + " ; entryStart+(maxItems+1)*entrySize = "
                + (entryStart + (maxItems + 1) * entrySize) + " (<=1024?)");

            byte[] root = idx.rawPage(idx.rootPage());
            int count = u16(root, 0);
            System.out.println("\nroot page count = " + count);
            System.out.print("first slot offsets: ");
            for (int i = 0; i <= Math.min(count, 5); i++) {
                System.out.print(u16(root, 2 + i * 2) + " ");
            }
            System.out.println("(stride between slot0,slot1 = "
                + (u16(root, 4) - u16(root, 2)) + ")");

            // Decode the first root entry the way the reader does.
            int slot0 = u16(root, 2);
            long child0 = u32(root, slot0);
            long recno0 = u32(root, slot0 + 4);
            String key0 = new String(root, slot0 + 8, keyLen, java.nio.charset.StandardCharsets.ISO_8859_1);
            System.out.println("entry0: child=" + child0 + " recno=" + recno0 + " key=[" + key0 + "]");
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
