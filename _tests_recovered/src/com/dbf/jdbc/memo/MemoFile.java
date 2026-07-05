package com.dbf.jdbc.memo;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

/**
 * DBF Memo file handler for .DBT (dBASE III/IV) and .FPT (FoxPro) formats
 */
public class MemoFile {
    private final RandomAccessFile file;
    private MemoType type;
    private final Charset charset;
    private final int blockSize;
    private boolean closed = false;
    
    public enum MemoType {
        DBT,    // dBASE III/IV memo
        FPT     // FoxPro memo
    }
    
    public MemoFile(String filePath, Charset charset) throws IOException  {
        this.charset = charset != null ? charset : StandardCharsets.UTF_8;
        
        // Determine memo file type and path
        String basePath = filePath.substring(0, filePath.length() - 4);
        
        // Check for .fpt first (FoxPro), then .dbt (dBASE)
        java.io.File fptFile = new java.io.File(basePath + ".fpt");
        java.io.File dbtFile = new java.io.File(basePath + ".dbt");
        
        if (fptFile.exists()) {
            this.type = MemoType.FPT;
            this.file = new RandomAccessFile(fptFile, "r");
            // Read block size from header (offset 6)
            this.file.seek(6);
            this.blockSize = this.file.readUnsignedShort();
        } else if (dbtFile.exists()) {
            this.type = MemoType.DBT;
            this.file = new RandomAccessFile(dbtFile, "r");
            // DBT uses 512-byte blocks
            this.blockSize = 512;
        } else {
            // No memo file exists
            this.file = null;
            this.blockSize = 512;
        }
    }
    
    public String readMemo(int blockNumber) throws IOException, SQLException {
        if (blockNumber == 0 || file == null) return null;
        
        long offset = (long) blockNumber * blockSize;
        
        // Validate offset is within file
        if (offset >= file.length()) {
            return "";
        }
        
        file.seek(offset);
        
        if (type == MemoType.FPT) {
            // FoxPro FPT format
            int typeMarker = file.readInt();
            int length = file.readInt();
            
            if (typeMarker == 0 || length <= 8) return "";
            
            // Ensure we don't read beyond file
            int dataLength = length - 8;
            if (dataLength > 65536) { // Limit memo size to 64KB for safety
                dataLength = 65536;
            }
            
            byte[] data = new byte[dataLength];
            int bytesRead = file.read(data, 0, Math.min(dataLength, (int)(file.length() - file.getFilePointer())));
            
            if (bytesRead <= 0) return "";
            
            // Truncate to actual bytes read
            if (bytesRead < dataLength) {
                byte[] trimmed = new byte[bytesRead];
                System.arraycopy(data, 0, trimmed, 0, bytesRead);
                data = trimmed;
            }
            
            // Check if it's binary or text
            if (typeMarker == 1) {
                return new String(data, charset);
            } else if (typeMarker == 2) {
                // Binary memo - treat as string for now
                return new String(data, StandardCharsets.ISO_8859_1);
            }
            return "";
        } else {
            // dBASE DBT format
            byte[] data = new byte[blockSize];
            int bytesRead = file.read(data, 0, blockSize);
            
            if (bytesRead <= 0) return "";
            
            // Find null terminator
            int len = 0;
            while (len < bytesRead && data[len] != 0) {
                len++;
            }
            
            return new String(data, 0, len, charset);
        }
    }
    
    public void writeMemo(int blockNumber, String text) throws IOException {
        if (file == null) {
            throw new IOException("Memo file not available for writing");
        }
        
        byte[] textBytes = text.getBytes(charset);
        
        if (type == MemoType.FPT) {
            // FoxPro FPT format
            long offset = (long) blockNumber * blockSize;
            file.seek(offset);
            file.writeInt(1); // Type marker (text)
            file.writeInt(textBytes.length + 8); // Total length
            file.write(textBytes);
        } else {
            // dBASE DBT format
            long offset = (long) blockNumber * blockSize;
            file.seek(offset);
            int bytesToWrite = Math.min(textBytes.length, blockSize);
            file.write(textBytes, 0, bytesToWrite);
            
            // Pad with zeros if needed
            if (bytesToWrite < blockSize) {
                byte[] padding = new byte[blockSize - bytesToWrite];
                file.write(padding);
            }
        }
    }
    
    public int allocateNewBlock() throws IOException {
        if (file == null) {
            throw new IOException("Memo file not available");
        }
        
        long newBlock = file.length() / blockSize;
        file.setLength((newBlock + 1) * blockSize);
        return (int) newBlock;
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