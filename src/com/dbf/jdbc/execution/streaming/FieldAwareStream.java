package com.dbf.jdbc.execution.streaming;

import com.dbf.jdbc.dbf.DBFField;
import java.util.List;

/**
 * A RowStream that knows the DBF field definitions of its output columns,
 * one field per column. Lets ResultSet metadata report real types instead
 * of defaulting everything to VARCHAR.
 */
public interface FieldAwareStream {
    /** Field definitions for the stream's output columns, or null if unknown. */
    List<DBFField> getFields();
}
