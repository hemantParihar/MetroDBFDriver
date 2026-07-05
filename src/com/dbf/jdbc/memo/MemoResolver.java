package com.dbf.jdbc.memo;

import java.io.IOException;
import java.nio.charset.Charset;

/**
 * Read-side facade over MemoFile used by DBFReader. All format handling
 * (DBT vs FPT, block sizes, terminators) lives in MemoFile.
 */
public class MemoResolver {
    private final MemoFile memoFile;

    public MemoResolver(String dbfFilePath, Charset charset) throws IOException {
        MemoFile opened = new MemoFile(dbfFilePath, charset, false);
        this.memoFile = opened.hasMemoFile() ? opened : null;
        if (this.memoFile == null) {
            opened.close();
        }
    }

    public String resolveMemo(int blockNumber) throws IOException {
        if (memoFile == null) return null;
        return memoFile.readMemo(blockNumber);
    }

    public boolean isAvailable() {
        return memoFile != null;
    }

    public void close() throws IOException {
        if (memoFile != null) {
            memoFile.close();
        }
    }
}
