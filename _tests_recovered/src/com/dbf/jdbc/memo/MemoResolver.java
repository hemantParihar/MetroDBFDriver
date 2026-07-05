package com.dbf.jdbc.memo;

import com.dbf.jdbc.dbf.DBFField;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Pure memo field resolver
 * Responsibilities:
 * - Read memo from .DBT (dBASE) files
 * - Read memo from .FPT (FoxPro) files
 * - Cache memo blocks
 * 
 * Does NOT handle field decoding or record navigation
 */
public class MemoResolver {
    private RandomAccessFile memoFile;
    private  MemoType type;
    private Charset charset;
    private final int blockSize;
    private boolean closed = false;
    
    public enum MemoType {
        DBT,    // dBASE III/IV memo (512 byte blocks)
        FPT     // FoxPro memo (variable block size)
    }
    
    public MemoResolver(String filePath, Charset charset) throws IOException {
        this.charset = charset != null ? charset : StandardCharsets.UTF_8;
        
        String basePath = filePath.substring(0, filePath.length() - 4);
        
        java.io.File fptFile = new java.io.File(basePath + ".fpt");
        java.io.File dbtFile = new java.io.File(basePath + ".dbt");
        
        if (fptFile.exists()) {
            this.type = MemoType.FPT;
            this.memoFile = new RandomAccessFile(fptFile, "r");
            this.memoFile.seek(6);
            this.blockSize = this.memoFile.readUnsignedShort();
        } else if (dbtFile.exists()) {
            this.type = MemoType.DBT;
            this.memoFile = new RandomAccessFile(dbtFile, "r");
            this.blockSize = 512;
        } else {
            this.memoFile = null;
            this.blockSize = 512;
        }
    }
    
    public String resolveMemo(int blockNumber) throws IOException {
        if (blockNumber == 0 || memoFile == null) return null;
        
        long offset = (long) blockNumber * blockSize;
        
        if (offset >= memoFile.length()) return "";
        
        memoFile.seek(offset);
        
        if (type == MemoType.FPT) {
            int typeMarker = memoFile.readInt();
            int length = memoFile.readInt();
            
            if (typeMarker == 0 || length <= 8) return "";
            
            int dataLength = length - 8;
            if (dataLength > 65536) dataLength = 65536;
            
            byte[] data = new byte[dataLength];
            int bytesRead = memoFile.read(data, 0, Math.min(dataLength, 
                (int)(memoFile.length() - memoFile.getFilePointer())));
            
            if (bytesRead <= 0) return "";
            
            if (bytesRead < dataLength) {
                byte[] trimmed = new byte[bytesRead];
                System.arraycopy(data, 0, trimmed, 0, bytesRead);
                data = trimmed;
            }
            
            if (typeMarker == 1) {
                return new String(data, charset);
            } else if (typeMarker == 2) {
                return new String(data, StandardCharsets.ISO_8859_1);
            }
            return "";
        } else {
            byte[] data = new byte[blockSize];
            int bytesRead = memoFile.read(data, 0, blockSize);
            if (bytesRead <= 0) return "";
            
            int len = 0;
            while (len < bytesRead && data[len] != 0) len++;
            
            return new String(data, 0, len, charset);
        }
    }
    
    public boolean isAvailable() {
        return memoFile != null;
    }
    
    public void close() throws IOException {
        if (!closed && memoFile != null) {
            memoFile.close();
            closed = true;
        }
    }
}