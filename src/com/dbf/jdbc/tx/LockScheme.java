package com.dbf.jdbc.tx;

import java.io.IOException;

/**
 * Selects how the driver takes a table write lock, and (for the Clipper schemes)
 * carries the exact byte-range offsets used. The offsets match the values used
 * by Clipper's DBFNTX RDD (as documented for Harbour's {@code DB_DBFLOCK_*}
 * schemes), so a lock taken here mutually excludes the record/header/file locks
 * a running Clipper application places on the same {@code .dbf}.
 *
 * <p>A whole-table write is taken as the Clipper <b>FLOCK</b> (file lock): an
 * exclusive lock over the region {@code [base, base+flockSize)}. Clipper's
 * per-record locks live at {@code base + recNo} and its header lock at
 * {@code base}, all inside that region, so our FLOCK conflicts with any of them.
 */
public enum LockScheme {

    /** Clipper DBFNTX (classic): base 1,000,000,000; FLOCK size 1,000,000,000. */
    CLIPPER(1_000_000_000L, 1_000_000_000L),

    /** Clipper DBFNTX with ntxlock2.obj (large files): base 4,000,000,000; FLOCK size 294,967,295. */
    CLIPPER2(4_000_000_000L, 294_967_295L),

    /** Private side-car {@code <table>.dbf.lck} file (no coordination with native apps). */
    SIDECAR(0L, 0L);

    private final long base;
    private final long flockSize;

    LockScheme(long base, long flockSize) {
        this.base = base;
        this.flockSize = flockSize;
    }

    /** Lock-region start offset on the {@code .dbf} (Clipper FLOCK base). */
    public long base() {
        return base;
    }

    /** Lock-region length on the {@code .dbf} (Clipper FLOCK size). */
    public long flockSize() {
        return flockSize;
    }

    /** Parses a scheme name from a URL/property value; defaults to {@link #CLIPPER}. */
    public static LockScheme parse(String value) {
        if (value == null) {
            return CLIPPER;
        }
        switch (value.trim().toLowerCase()) {
            case "clipper":
            case "dbfntx":
            case "ntx":
                return CLIPPER;
            case "clipper2":
            case "ntxlock2":
                return CLIPPER2;
            case "sidecar":
            case "lck":
            case "none":
            case "off":
                return SIDECAR;
            default:
                return CLIPPER;
        }
    }

    /**
     * Acquires the exclusive write lock for {@code dbfPath} under this scheme
     * (blocks until granted).
     */
    public WriteLock acquire(String dbfPath) throws IOException {
        return this == SIDECAR ? TableLock.acquire(dbfPath) : ClipperLock.acquire(dbfPath, this);
    }
}
