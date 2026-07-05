package com.dbf.jdbc.execution.streaming;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Compact binary row codec used by spill-to-disk operators
 * (external sort, partitioned hash join).
 */
public final class RowSerializer {
    private static final byte T_NULL = 0;
    private static final byte T_STRING = 1;
    private static final byte T_INT = 2;
    private static final byte T_LONG = 3;
    private static final byte T_DOUBLE = 4;
    private static final byte T_BOOLEAN = 5;
    private static final byte T_DATE = 6;
    private static final byte T_TIMESTAMP = 7;
    private static final byte T_OTHER = 8; // toString fallback

    private RowSerializer() {
    }

    public static void writeRow(DataOutputStream out, Object[] row) throws IOException {
        out.writeInt(row.length);
        for (Object value : row) {
            if (value == null) {
                out.writeByte(T_NULL);
            } else if (value instanceof String) {
                out.writeByte(T_STRING);
                writeString(out, (String) value);
            } else if (value instanceof Integer) {
                out.writeByte(T_INT);
                out.writeInt((Integer) value);
            } else if (value instanceof Long) {
                out.writeByte(T_LONG);
                out.writeLong((Long) value);
            } else if (value instanceof Double) {
                out.writeByte(T_DOUBLE);
                out.writeDouble((Double) value);
            } else if (value instanceof Boolean) {
                out.writeByte(T_BOOLEAN);
                out.writeBoolean((Boolean) value);
            } else if (value instanceof java.sql.Timestamp) {
                out.writeByte(T_TIMESTAMP);
                out.writeLong(((java.sql.Timestamp) value).getTime());
            } else if (value instanceof java.util.Date) {
                out.writeByte(T_DATE);
                out.writeLong(((java.util.Date) value).getTime());
            } else {
                out.writeByte(T_OTHER);
                writeString(out, value.toString());
            }
        }
    }

    /** Reads one row; throws EOFException at end of stream. */
    public static Object[] readRow(DataInputStream in) throws IOException {
        int length = in.readInt();
        Object[] row = new Object[length];
        for (int i = 0; i < length; i++) {
            byte type = in.readByte();
            switch (type) {
                case T_NULL: row[i] = null; break;
                case T_STRING: row[i] = readString(in); break;
                case T_INT: row[i] = in.readInt(); break;
                case T_LONG: row[i] = in.readLong(); break;
                case T_DOUBLE: row[i] = in.readDouble(); break;
                case T_BOOLEAN: row[i] = in.readBoolean(); break;
                case T_DATE: row[i] = new java.sql.Date(in.readLong()); break;
                case T_TIMESTAMP: row[i] = new java.sql.Timestamp(in.readLong()); break;
                case T_OTHER: row[i] = readString(in); break;
                default:
                    throw new IOException("Corrupt spill file: unknown type tag " + type);
            }
        }
        return row;
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int len = in.readInt();
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
