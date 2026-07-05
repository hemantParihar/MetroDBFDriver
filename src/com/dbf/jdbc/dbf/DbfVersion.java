package com.dbf.jdbc.dbf;

/**
 * Centralizes the per-version format constants for the xBase family.
 * <p>
 * These values are ported from the DANS DBF library's {@code Version} enum
 * (Apache 2.0) and the published xBase format specifications. Previously
 * these numbers were scattered inline across the writer, header parser and
 * memo handler, hard-coded for dBASE III only; collecting them here makes
 * the version-specific differences explicit and validatable.
 *
 * <table border="1">
 *   <caption>Version constants</caption>
 *   <tr><th>Version</th><th>max C</th><th>max N</th><th>hdr term</th>
 *       <th>memo data offset</th><th>memo end marker / len</th></tr>
 *   <tr><td>dBASE III</td><td>254</td><td>19</td><td>1</td><td>0</td><td>0x1A1A / 2</td></tr>
 *   <tr><td>dBASE IV</td><td>254</td><td>20</td><td>1</td><td>8</td><td>none / 0</td></tr>
 *   <tr><td>dBASE 5</td><td>254</td><td>20</td><td>1</td><td>8</td><td>none / 0</td></tr>
 *   <tr><td>Clipper 5</td><td>1024</td><td>19</td><td>2</td><td>0</td><td>0x1A / 1</td></tr>
 *   <tr><td>FoxPro 2.6</td><td>254</td><td>20</td><td>1</td><td>8</td><td>none / 0</td></tr>
 * </table>
 */
public enum DbfVersion {
    DBASE_3("dBASE III+", 254, 19, 1, 0, 0x1a1a, 2, "CNDLM"),
    DBASE_4("dBASE IV", 254, 20, 1, 8, 0x00, 0, "CNDLMF"),
    DBASE_5("dBASE 5", 254, 20, 1, 8, 0x00, 0, "CNDLMFBG"),
    CLIPPER_5("Clipper 5", 1024, 19, 2, 0, 0x1a, 1, "CNDLM"),
    FOXPRO_26("FoxPro 2.6", 254, 20, 1, 8, 0x00, 0, "CNDLMFGP");

    // Version byte written to offset 0 of the .dbf header.
    private static final int BYTE_DBASE3_NO_MEMO = 0x03;
    private static final int BYTE_DBASE3_MEMO = 0x83;
    private static final int BYTE_DBASE4_MEMO = 0x8B;
    private static final int BYTE_FOXPRO_MEMO = 0xF5;

    private final String displayName;
    private final int maxLengthCharField;
    private final int maxLengthNumberField;
    private final int lengthHeaderTerminator;
    private final int memoDataOffset;
    private final int memoFieldEndMarker;
    private final int memoFieldEndMarkerLength;
    private final String fieldTypes;

    DbfVersion(String displayName, int maxLengthCharField, int maxLengthNumberField,
               int lengthHeaderTerminator, int memoDataOffset, int memoFieldEndMarker,
               int memoFieldEndMarkerLength, String fieldTypes) {
        this.displayName = displayName;
        this.maxLengthCharField = maxLengthCharField;
        this.maxLengthNumberField = maxLengthNumberField;
        this.lengthHeaderTerminator = lengthHeaderTerminator;
        this.memoDataOffset = memoDataOffset;
        this.memoFieldEndMarker = memoFieldEndMarker;
        this.memoFieldEndMarkerLength = memoFieldEndMarkerLength;
        this.fieldTypes = fieldTypes;
    }

    public String displayName() {
        return displayName;
    }

    /** Maximum length of a character (C) field for this version. */
    public int maxCharLength() {
        return maxLengthCharField;
    }

    /** Maximum length of a numeric (N/F) field for this version. */
    public int maxNumberLength() {
        return maxLengthNumberField;
    }

    /**
     * Number of 0x0D terminator bytes after the field-descriptor array.
     * Clipper 5 uses two; everything else uses one. Needed to compute the
     * field count from the header length.
     */
    public int headerTerminatorLength() {
        return lengthHeaderTerminator;
    }

    /** Bytes of block header before memo text (8 for dBASE IV/5/FoxPro, else 0). */
    public int memoDataOffset() {
        return memoDataOffset;
    }

    /** End-of-memo marker value for this version (0 when none is used). */
    public int memoFieldEndMarker() {
        return memoFieldEndMarker;
    }

    /** Number of bytes the end-of-memo marker occupies (0, 1 or 2). */
    public int memoFieldEndMarkerLength() {
        return memoFieldEndMarkerLength;
    }

    /** Whether the given DBF field-type letter is legal for this version. */
    public boolean supportsType(char type) {
        return fieldTypes.indexOf(Character.toUpperCase(type)) >= 0;
    }

    /** The version byte to write to the header, given whether a memo file is present. */
    public int versionByte(boolean hasMemo) {
        if (!hasMemo) {
            return BYTE_DBASE3_NO_MEMO;
        }
        switch (this) {
            case DBASE_4:
            case DBASE_5:
                return BYTE_DBASE4_MEMO;
            case FOXPRO_26:
                return BYTE_FOXPRO_MEMO;
            case DBASE_3:
            case CLIPPER_5:
            default:
                return BYTE_DBASE3_MEMO;
        }
    }

    /**
     * Detects the version from the header's version byte and the header
     * length. Clipper 5 is distinguished by a two-byte terminator, which
     * makes {@code headerLength % 32 == 2}.
     */
    public static DbfVersion fromHeader(int versionByte, int headerLength) {
        if (headerLength % 32 == 2) {
            return CLIPPER_5;
        }
        switch (versionByte & 0xFF) {
            case BYTE_DBASE3_NO_MEMO:
            case BYTE_DBASE3_MEMO:
                return DBASE_3;
            case BYTE_DBASE4_MEMO:
            case 0x0B: // dBASE IV no memo
                return DBASE_4;
            case BYTE_FOXPRO_MEMO:
                return FOXPRO_26;
            case 0x30: // Visual FoxPro
            case 0x31:
            case 0x32:
                return FOXPRO_26;
            default:
                return DBASE_3;
        }
    }
}
