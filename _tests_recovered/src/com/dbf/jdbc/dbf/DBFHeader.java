package com.dbf.jdbc.dbf;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import com.dbf.jdbc.DBFConstants;

/**
 * DBF file header - 32 bytes plus field descriptors
 */
public class DBFHeader {
    private byte version;
    private Date lastUpdate;
    private int recordCount;
    private int headerSize;
    private int recordSize;
    private byte incompleteTransaction;
    private byte encryptionFlag;
    private int freeRecordThread;
    private byte[] reserved = new byte[12];
    private byte productionMdxFlag;
    private byte languageDriverId;
    private byte[] reserved2 = new byte[2];
    
    private List<DBFField> fields = new ArrayList<>();
    private int fieldCount;
    
    // Header size constant
    public static final int FIXED_HEADER_SIZE = 32;
    public static final int FIELD_DESCRIPTOR_SIZE = 32;
    public static final byte TERMINATOR = 0x0D;
    private boolean hasMemo = false;
    private int memoBlockSize = 512;
    
    public void read(RandomAccessFile file, Charset charset) throws IOException {
        file.seek(0);
        
        // Version
        version = file.readByte();
        
        // Last update (YY, MM, DD)
        int year = file.readByte() + 1900;
        int month = file.readByte();
        int day = file.readByte();
//        lastUpdate = new Date(year - 1900, month - 1, day);
        Calendar cal = Calendar.getInstance();
        cal.set(year, month - 1, day, 0, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);
        lastUpdate = new Date(cal.getTimeInMillis());
        
        // Record count
        recordCount = file.readInt();
        
        // Header size
        headerSize = file.readUnsignedShort();
        
        // Record size
        recordSize = file.readUnsignedShort();
        
        // Reserved bytes
        file.readFully(reserved);
        incompleteTransaction = reserved[0];
        encryptionFlag = reserved[1];
        freeRecordThread = (reserved[2] & 0xFF) | ((reserved[3] & 0xFF) << 8);
        
        // Production MDX flag
        productionMdxFlag = file.readByte();
        
        // Language driver ID
        languageDriverId = file.readByte();
        
        // Reserved 2 bytes
        file.readFully(reserved2);
        
        // Read field descriptors
        fields.clear();
        int offset = 1; // Records start with 1-byte deletion flag
        
        while (true) {
            DBFField field = readFieldDescriptor(file, charset);
            if (field == null) break;
            
            field.setOffset(offset);
            field.setPosition(fields.size() + 1);
            offset += field.getLength();
            fields.add(field);
        }
        
        fieldCount = fields.size();
        
        // Verify header size
        int expectedHeaderSize = FIXED_HEADER_SIZE + (fields.size() * FIELD_DESCRIPTOR_SIZE) + 1;
        if (headerSize != expectedHeaderSize) {
            // Warning: header size mismatch
        }
        
     // After reading fields, check for memo fields
        for (DBFField field : fields) {
            if (field.getType() == DBFConstants.FIELD_TYPE_MEMO) {
                hasMemo = true;
                break;
            }
        }
        
        // For FoxPro, read memo block size from header
        if (version == DBFConstants.DBF_VISUAL_FOXPRO && hasMemo) {
            file.seek(0);
            file.skipBytes(6);
            memoBlockSize = file.readUnsignedShort();
        }
        
        // Skip to data records
        file.seek(headerSize);
        
     
    }
    
    private DBFField readFieldDescriptor(RandomAccessFile file, Charset charset) throws IOException {
        byte[] nameBytes = new byte[11];
        file.readFully(nameBytes);
        
        // Check for terminator
        if (nameBytes[0] == TERMINATOR) {
            file.skipBytes(FIELD_DESCRIPTOR_SIZE - 1);
            return null;
        }
        
        DBFField field = new DBFField();
        
        // Field name (null-terminated)
        int nameLength = 0;
        while (nameLength < nameBytes.length && nameBytes[nameLength] != 0) {
            nameLength++;
        }
        String name = new String(nameBytes, 0, nameLength, charset).trim();
        field.setName(name);
        
        // Field type
        field.setType((char) file.readByte());
        
        // Field offset (calculated, stored as 4 bytes but we compute)
        file.skipBytes(4);
        
        // Field length
        field.setLength(file.readUnsignedByte());
        
        // Decimal count
        field.setDecimalCount(file.readUnsignedByte());
        
        // Reserved bytes (2 bytes)
        file.skipBytes(2);
        
        // Work area ID
        file.skipBytes(1);
        
        // Reserved (2 bytes)
        file.skipBytes(2);
        
        // Set flags (for Visual FoxPro)
        byte flags = file.readByte();
        field.setFlags(flags);
        
        // Auto-increment next value (for Visual FoxPro)
        field.setAutoIncrementNext(file.readInt());
        
        // Step (for Visual FoxPro)
        field.setStep(file.readByte());
        
        // Remaining bytes
        file.skipBytes(8);
        
        return field;
    }
    
    public void write(RandomAccessFile file, Charset charset) throws IOException {
        file.seek(0);
        
        // Version
        file.writeByte(version);
        
        // Last update
        LocalDate now = LocalDate.now();
        file.writeByte(now.getYear() - 1900);
        file.writeByte(now.getMonthValue());
        file.writeByte(now.getDayOfMonth());
        
        // Record count
        file.writeInt(recordCount);
        
        // Header size
        headerSize = FIXED_HEADER_SIZE + (fields.size() * FIELD_DESCRIPTOR_SIZE) + 1;
        file.writeShort(headerSize);
        
        // Record size
        recordSize = calculateRecordSize();
        file.writeShort(recordSize);
        
        // Reserved bytes
        file.write(reserved);
        
        // Production MDX flag
        file.writeByte(productionMdxFlag);
        
        // Language driver ID
        file.writeByte(languageDriverId);
        
        // Reserved 2 bytes
        file.write(reserved2);
        
        // Write field descriptors
        for (DBFField field : fields) {
            writeFieldDescriptor(file, field, charset);
        }
        
        // Write terminator
        file.writeByte(TERMINATOR);
        
        // Ensure at correct position
        file.seek(headerSize);
    }
    
    private void writeFieldDescriptor(RandomAccessFile file, DBFField field, Charset charset) throws IOException {
        // Field name (11 bytes, null-padded)
        byte[] nameBytes = field.getName().getBytes(charset);
        for (int i = 0; i < 11; i++) {
            file.writeByte(i < nameBytes.length ? nameBytes[i] : 0);
        }
        
        // Field type
        file.writeByte(field.getType());
        
        // Field offset (4 bytes, reserved)
        for (int i = 0; i < 4; i++) {
            file.writeByte(0);
        }
        
        // Field length
        file.writeByte(field.getLength());
        
        // Decimal count
        file.writeByte(field.getDecimalCount());
        
        // Reserved bytes (2 bytes)
        file.writeByte(0);
        file.writeByte(0);
        
        // Work area ID
        file.writeByte(0);
        
        // Reserved (2 bytes)
        file.writeByte(0);
        file.writeByte(0);
        
        // Flags
        file.writeByte(field.getFlags());
        
        // Auto-increment next value
        file.writeInt(field.getAutoIncrementNext());
        
        // Step
        file.writeByte(field.getStep());
        
        // Reserved (8 bytes)
        for (int i = 0; i < 8; i++) {
            file.writeByte(0);
        }
    }
    
    private int calculateRecordSize() {
        int size = 1; // Deletion flag
        for (DBFField field : fields) {
            size += field.getLength();
        }
        return size;
    }
    
    // Getters and setters
    public byte getVersion() { return version; }
    public void setVersion(byte version) { this.version = version; }
    
    public Date getLastUpdate() { return lastUpdate; }
    public void setLastUpdate(Date lastUpdate) { this.lastUpdate = lastUpdate; }
    
    public int getRecordCount() { return recordCount; }
    public void setRecordCount(int recordCount) { this.recordCount = recordCount; }
    
    public int getHeaderSize() { return headerSize; }
    public int getRecordSize() { return recordSize; }
    
    public List<DBFField> getFields() { return fields; }
    public void setFields(List<DBFField> fields) { 
        this.fields = fields;
        updateFieldOffsets();
    }
    
    public void addField(DBFField field) {
        field.setPosition(fields.size() + 1);
        fields.add(field);
        updateFieldOffsets();
    }
    
    private void updateFieldOffsets() {
        int offset = 1;
        for (DBFField field : fields) {
            field.setOffset(offset);
            offset += field.getLength();
        }
        recordSize = offset;
    }
    
    public DBFField getField(int index) {
        return fields.get(index);
    }
    
    public DBFField getField(String name) {
        for (DBFField field : fields) {
            if (field.getName().equalsIgnoreCase(name)) {
                return field;
            }
        }
        return null;
    }
    
    public int getFieldCount() { return fields.size(); }
    public boolean hasMemo() { return hasMemo; }
    public int getMemoBlockSize() { return memoBlockSize; }
}