package com.dbf.jdbc;

/**
 * DBF file format constants
 * Based on public DBF specification (XBase file format)
 */
public final class DBFConstants {
    
    // DBF Version identifiers
    public static final byte DBF_III_PLUS_MEMO = (byte)0x83;      // FoxBASE+/dBASE III PLUS with memo
    public static final byte DBF_III_PLUS_NO_MEMO = 0x03;    // dBASE III PLUS no memo
    public static final byte DBF_IV_MEMO = (byte)0x8B;             // dBASE IV with memo
    public static final byte DBF_IV_NO_MEMO = 0x0B;          // dBASE IV no memo
    public static final byte DBF_VISUAL_FOXPRO = 0x30;       // Visual FoxPro
    public static final byte DBF_VISUAL_FOXPRO_AI = 0x31;    // Visual FoxPro AutoIncrement
    public static final byte DBF_VISUAL_FOXPRO_VAR = 0x32;   // Visual FoxPro Varchar/Varbinary
    
    // Field types
    public static final char FIELD_TYPE_CHARACTER = 'C';
    public static final char FIELD_TYPE_NUMERIC = 'N';
    public static final char FIELD_TYPE_FLOAT = 'F';
    public static final char FIELD_TYPE_DATE = 'D';
    public static final char FIELD_TYPE_LOGICAL = 'L';
    public static final char FIELD_TYPE_MEMO = 'M';
    public static final char FIELD_TYPE_BINARY = 'B';
    public static final char FIELD_TYPE_DOUBLE = 'O';
    public static final char FIELD_TYPE_INTEGER = 'I';
    public static final char FIELD_TYPE_TIMESTAMP = 'T';
    public static final char FIELD_TYPE_CURRENCY = 'Y';
    
    // Header sizes
    public static final int HEADER_SIZE = 32;
    public static final int FIELD_DESCRIPTOR_SIZE = 32;
    public static final int TERMINATOR = 0x0D;
    
    // Record flags
    public static final byte RECORD_ACTIVE = 0x20;
    public static final byte RECORD_DELETED = 0x2A;
    
    // Date values
    public static final int DATE_OFFSET = 1900;
    ;
    
    // Memo block sizes
    public static final int DBT_BLOCK_SIZE = 512;
    public static final int FPT_BLOCK_SIZE = 64;
    
    // Memo signatures
    public static final byte[] FPT_SIGNATURE = {0x00, 0x00, 0x00, 0x00};
    
    private DBFConstants() {}
}