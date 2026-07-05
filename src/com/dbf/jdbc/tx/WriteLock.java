package com.dbf.jdbc.tx;

/**
 * An exclusive write lock on one DBF table, held for the duration of a DML
 * write (or a whole transaction) and released via {@link #close()}.
 *
 * <p>Two implementations exist, chosen per connection by {@link LockScheme}:
 * {@link TableLock} (a private side-car {@code .lck} file, safe only among this
 * driver's own connections) and {@link ClipperLock} (a byte-range lock on the
 * {@code .dbf} itself at the offsets Clipper's DBFNTX RDD uses, so the lock
 * also coordinates with a running Clipper/xBase application such as METRO).
 */
public interface WriteLock extends AutoCloseable {
    /** Releases the lock. Never throws. */
    @Override
    void close();
}
