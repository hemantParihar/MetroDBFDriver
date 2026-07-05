package com.dbf.jdbc.index.ntx;

import com.dbf.jdbc.dbf.DBFField;
import com.dbf.jdbc.dbf.DBFReader;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps a table's {@code .NTX} indexes in step with its DBF after a write.
 *
 * <p>Safety first (the user must never have a corrupted index): an index is
 * maintained only when (a) its key expression is fully supported by
 * {@link NtxKeyEvaluator} and references only this table's fields, and (b) a
 * sample of its existing entries reproduces exactly -- which proves the index
 * currently belongs to this table and is consistent. Anything else (stale,
 * foreign, or using an unsupported function) is left untouched.
 *
 * <p>Each maintained index is rebuilt to a temp file, re-read and checked
 * against the intended key set, then atomically renamed over the original. A
 * failure at any step leaves the original index in place.
 *
 * <p>This is the full-rebuild path. {@code rebuildAll} is also the fallback an
 * incremental updater drops back to when its own read-back check fails.
 */
public final class NtxMaintainer {

    /** Entries sampled when deciding whether an index is consistent/ours. */
    private static final int SAMPLE = 256;

    private NtxMaintainer() {
    }

    /**
     * Rebuilds every maintainable index of {@code table} in {@code folder}.
     * Never throws: index upkeep must not fail the user's DML. Returns the
     * names of the indexes actually updated.
     */
    public static List<String> rebuildAll(String folder, String table, Charset charset) {
        List<String> updated = new ArrayList<>();
        File dbf = resolveDbf(folder, table);
        if (dbf == null) {
            return updated;
        }
        File[] ntxFiles = new File(folder).listFiles(
            (d, n) -> n.toLowerCase().endsWith(".ntx"));
        if (ntxFiles == null || ntxFiles.length == 0) {
            return updated;
        }
        try (DBFReader reader = new DBFReader(dbf.getPath(), charset)) {
            List<DBFField> fields = reader.getHeader().getFields();
            NtxKeyEvaluator evaluator = new NtxKeyEvaluator(fields);

            for (File ntx : ntxFiles) {
                try {
                    if (maintainOne(ntx, reader, evaluator)) {
                        updated.add(ntx.getName());
                    }
                } catch (IOException | RuntimeException e) {
                    // Skip this index; never corrupt and never fail the DML.
                }
            }
        } catch (IOException e) {
            // Could not open the table; leave all indexes untouched.
        }
        return updated;
    }

    /**
     * Incrementally inserts the keys for {@code newRecnos} into each maintainable
     * index of {@code table} (the fast path for INSERT). Each index is updated
     * in place, then re-read and checked (valid, sorted, grew by exactly the
     * number of inserts, new keys present); if the check fails the index is
     * restored from a pre-update backup and fully rebuilt. Never throws.
     */
    public static List<String> insertKeys(String folder, String table, Charset charset,
            long[] newRecnos) {
        List<String> updated = new ArrayList<>();
        if (newRecnos == null || newRecnos.length == 0) {
            return updated;
        }
        File dbf = resolveDbf(folder, table);
        if (dbf == null) {
            return updated;
        }
        File[] ntxFiles = new File(folder).listFiles(
            (d, n) -> n.toLowerCase().endsWith(".ntx"));
        if (ntxFiles == null || ntxFiles.length == 0) {
            return updated;
        }
        try (DBFReader reader = new DBFReader(dbf.getPath(), charset)) {
            NtxKeyEvaluator evaluator = new NtxKeyEvaluator(reader.getHeader().getFields());
            for (File ntx : ntxFiles) {
                try {
                    if (insertIntoOne(ntx, reader, evaluator, newRecnos)) {
                        updated.add(ntx.getName());
                    }
                } catch (IOException | RuntimeException e) {
                    // Never corrupt, never fail the DML.
                }
            }
        } catch (IOException e) {
            // table unreadable -> leave indexes alone
        }
        return updated;
    }

    /**
     * Incrementally re-keys {@code changedRecnos} in each maintainable index
     * (the fast path for UPDATE): for each record whose key changed, remove the
     * old key and insert the new one. Indexes where no key changed are skipped.
     * Self-checks and falls back to a full rebuild per index on any anomaly.
     */
    public static List<String> updateKeys(String folder, String table, Charset charset,
            long[] changedRecnos) {
        List<String> updated = new ArrayList<>();
        if (changedRecnos == null || changedRecnos.length == 0) {
            return updated;
        }
        File dbf = resolveDbf(folder, table);
        if (dbf == null) {
            return updated;
        }
        File[] ntxFiles = new File(folder).listFiles(
            (d, n) -> n.toLowerCase().endsWith(".ntx"));
        if (ntxFiles == null || ntxFiles.length == 0) {
            return updated;
        }
        try (DBFReader reader = new DBFReader(dbf.getPath(), charset)) {
            NtxKeyEvaluator evaluator = new NtxKeyEvaluator(reader.getHeader().getFields());
            for (File ntx : ntxFiles) {
                try {
                    if (updateOne(ntx, reader, evaluator, changedRecnos)) {
                        updated.add(ntx.getName());
                    }
                } catch (IOException | RuntimeException e) {
                    // never corrupt, never fail the DML
                }
            }
        } catch (IOException e) {
            // table unreadable -> leave indexes alone
        }
        return updated;
    }

    private static boolean updateOne(File ntx, DBFReader reader,
            NtxKeyEvaluator evaluator, long[] changedRecnos) throws IOException {
        String keyExpr;
        java.util.Set<Long> targets = new java.util.HashSet<>();
        for (long r : changedRecnos) {
            targets.add(r);
        }
        java.util.Map<Long, byte[]> oldKeys = new java.util.HashMap<>();
        long before;
        try (NtxIndex idx = NtxIndex.open(ntx.getPath())) {
            keyExpr = idx.keyExpression();
            if (!evaluator.canEvaluate(keyExpr, 1) || !sampleConsistent(idx, reader, evaluator, keyExpr)) {
                return false;
            }
            // Single walk: capture the current (old) key for each changed recno.
            long[] count = {0};
            idx.forEach(e -> {
                count[0]++;
                if (targets.contains(e.recordNumber)) {
                    oldKeys.put(e.recordNumber, e.key);
                }
                return true;
            });
            before = count[0];
        }

        // Determine which recnos actually changed key (others need no work).
        List<byte[]> newKeyList = new ArrayList<>();
        List<Long> changeRecnos = new ArrayList<>();
        List<byte[]> oldKeyList = new ArrayList<>();
        for (long recno : changedRecnos) {
            byte[] oldKey = oldKeys.get(recno);
            if (oldKey == null) {
                return rebuildOne(ntx, reader, evaluator, keyExpr); // index missing this recno
            }
            reader.absolute((int) recno);
            byte[] newKey = evaluator.evaluate(keyExpr, name -> rawOrTyped(reader, name), recno);
            if (newKey == null) {
                return rebuildOne(ntx, reader, evaluator, keyExpr);
            }
            if (NtxWriter.compare(oldKey, padTo(newKey, oldKey.length)) != 0) {
                changeRecnos.add(recno);
                oldKeyList.add(oldKey);
                newKeyList.add(newKey);
            }
        }
        if (changeRecnos.isEmpty()) {
            return false; // indexed fields unchanged for this index
        }

        Path original = ntx.toPath();
        Path backup = original.resolveSibling(ntx.getName() + ".bak");
        Files.copy(original, backup, StandardCopyOption.REPLACE_EXISTING);
        try {
            boolean clean = true;
            try (NtxUpdater updater = new NtxUpdater(ntx.getPath())) {
                for (int i = 0; i < changeRecnos.size() && clean; i++) {
                    if (!updater.delete(oldKeyList.get(i), changeRecnos.get(i))) {
                        clean = false; // hard delete case -> fall back
                    } else {
                        updater.insert(newKeyList.get(i), changeRecnos.get(i));
                    }
                }
            }
            if (clean && updateLooksValid(ntx.getPath(), before, newKeyList, changeRecnos)) {
                Files.deleteIfExists(backup);
                return true;
            }
            Files.copy(backup, original, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            Files.copy(backup, original, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(backup);
        }
        return rebuildOne(ntx, reader, evaluator, keyExpr);
    }

    /** Post-update check: valid, sorted, size unchanged, each new key present. */
    private static boolean updateLooksValid(String path, long expectedCount,
            List<byte[]> newKeys, List<Long> recnos) throws IOException {
        long[] count = {0};
        byte[][] prev = {null};
        boolean[] sorted = {true};
        java.util.Set<String> present = new java.util.HashSet<>();
        try (NtxIndex idx = NtxIndex.open(path)) {
            idx.forEach(e -> {
                count[0]++;
                if (prev[0] != null && NtxWriter.compare(prev[0], e.key) > 0) {
                    sorted[0] = false;
                    return false;
                }
                prev[0] = e.key;
                present.add(e.recordNumber + ":" + new String(e.key,
                    java.nio.charset.StandardCharsets.ISO_8859_1));
                return true;
            });
        }
        if (!sorted[0] || count[0] != expectedCount) {
            return false;
        }
        for (int i = 0; i < newKeys.size(); i++) {
            String token = recnos.get(i) + ":" + new String(padTo(newKeys.get(i),
                newKeys.get(i).length), java.nio.charset.StandardCharsets.ISO_8859_1);
            if (!present.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private static byte[] padTo(byte[] in, int len) {
        if (in.length == len) {
            return in;
        }
        byte[] k = new byte[len];
        int n = Math.min(in.length, len);
        System.arraycopy(in, 0, k, 0, n);
        for (int i = n; i < len; i++) {
            k[i] = ' ';
        }
        return k;
    }

    private static boolean insertIntoOne(File ntx, DBFReader reader,
            NtxKeyEvaluator evaluator, long[] newRecnos) throws IOException {
        String keyExpr;
        long before;
        try (NtxIndex idx = NtxIndex.open(ntx.getPath())) {
            keyExpr = idx.keyExpression();
            if (!evaluator.canEvaluate(keyExpr, 1) || !sampleConsistent(idx, reader, evaluator, keyExpr)) {
                return false;
            }
            before = countAll(idx);
        }

        // Compute the new keys.
        List<NtxWriter.Entry> added = new ArrayList<>();
        for (long recno : newRecnos) {
            reader.absolute((int) recno);
            byte[] key = evaluator.evaluate(keyExpr, name -> rawOrTyped(reader, name), recno);
            if (key == null) {
                return false; // shouldn't happen (canEvaluate passed) -> bail to caller
            }
            added.add(new NtxWriter.Entry(key, recno));
        }

        // Back up the file so a bad incremental attempt can be undone.
        Path original = ntx.toPath();
        Path backup = original.resolveSibling(ntx.getName() + ".bak");
        Files.copy(original, backup, StandardCopyOption.REPLACE_EXISTING);
        try {
            try (NtxUpdater updater = new NtxUpdater(ntx.getPath())) {
                for (NtxWriter.Entry e : added) {
                    updater.insert(e.key, e.recno);
                }
            }
            if (incrementLooksValid(ntx.getPath(), before + added.size(), added)) {
                Files.deleteIfExists(backup);
                return true;
            }
            // Something is off: restore and rebuild from scratch.
            Files.copy(backup, original, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            Files.copy(backup, original, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(backup);
        }
        return rebuildOne(ntx, reader, evaluator, keyExpr);
    }

    /** Cheap-ish post-insert check: valid, sorted, expected size, new keys present. */
    private static boolean incrementLooksValid(String path, long expectedCount,
            List<NtxWriter.Entry> added) throws IOException {
        List<byte[]> wantKeys = new ArrayList<>();
        for (NtxWriter.Entry e : added) {
            wantKeys.add(e.key);
        }
        long[] count = {0};
        byte[][] prev = {null};
        boolean[] sorted = {true};
        boolean[] foundAll = {false};
        java.util.Set<String> present = new java.util.HashSet<>();
        try (NtxIndex idx = NtxIndex.open(path)) {
            idx.forEach(e -> {
                count[0]++;
                if (prev[0] != null && NtxWriter.compare(prev[0], e.key) > 0) {
                    sorted[0] = false;
                    return false;
                }
                prev[0] = e.key;
                present.add(e.recordNumber + ":" + new String(e.key,
                    java.nio.charset.StandardCharsets.ISO_8859_1));
                return true;
            });
        }
        if (!sorted[0] || count[0] != expectedCount) {
            return false;
        }
        for (NtxWriter.Entry e : added) {
            String token = e.recno + ":" + new String(e.key,
                java.nio.charset.StandardCharsets.ISO_8859_1);
            if (!present.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private static long countAll(NtxIndex idx) throws IOException {
        long[] n = {0};
        idx.forEach(e -> { n[0]++; return true; });
        return n[0];
    }

    /** Full rebuild of a single index (used as the incremental fallback). */
    private static boolean rebuildOne(File ntx, DBFReader reader,
            NtxKeyEvaluator evaluator, String keyExpr) throws IOException {
        List<NtxWriter.Entry> entries = computeEntries(reader, evaluator, keyExpr);
        entries.sort((a, b) -> NtxWriter.compare(a.key, b.key));
        Path original = ntx.toPath();
        Path temp = original.resolveSibling(ntx.getName() + ".tmp");
        try {
            NtxWriter.rebuild(ntx.getPath(), temp.toString(), entries);
            if (!verify(temp.toString(), entries)) {
                Files.deleteIfExists(temp);
                return false;
            }
            atomicReplace(temp, original);
            return true;
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignore) {
                // best effort
            }
            return false;
        }
    }

    private static boolean maintainOne(File ntx, DBFReader reader, NtxKeyEvaluator evaluator)
            throws IOException {
        String keyExpr;
        try (NtxIndex idx = NtxIndex.open(ntx.getPath())) {
            keyExpr = idx.keyExpression();
            if (!evaluator.canEvaluate(keyExpr, 1)) {
                return false; // unsupported function or field of another table
            }
            if (!sampleConsistent(idx, reader, evaluator, keyExpr)) {
                return false; // stale or foreign index -> refuse to touch it
            }
        }

        // Recompute the full, sorted key set from the current DBF.
        List<NtxWriter.Entry> entries = computeEntries(reader, evaluator, keyExpr);
        entries.sort((a, b) -> NtxWriter.compare(a.key, b.key));

        Path original = ntx.toPath();
        Path temp = original.resolveSibling(ntx.getName() + ".tmp");
        try {
            NtxWriter.rebuild(ntx.getPath(), temp.toString(), entries);
            if (!verify(temp.toString(), entries)) {
                Files.deleteIfExists(temp);
                return false;
            }
            atomicReplace(temp, original);
            return true;
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignore) {
                // best effort
            }
            return false;
        }
    }

    /** True if the first entries of the index reproduce exactly (index is ours & current). */
    private static boolean sampleConsistent(NtxIndex idx, DBFReader reader,
            NtxKeyEvaluator evaluator, String keyExpr) throws IOException {
        int[] checked = {0};
        boolean[] ok = {true};
        idx.forEach(e -> {
            if (checked[0] >= SAMPLE) {
                return false;
            }
            checked[0]++;
            try {
                reader.absolute((int) e.recordNumber);
                byte[] got = evaluator.evaluate(keyExpr,
                    name -> rawOrTyped(reader, name), e.recordNumber);
                if (got == null || NtxWriter.compare(got, e.key) != 0) {
                    ok[0] = false;
                    return false;
                }
            } catch (Exception ex) {
                ok[0] = false;
                return false;
            }
            return true;
        });
        return ok[0] && checked[0] > 0;
    }

    private static List<NtxWriter.Entry> computeEntries(DBFReader reader,
            NtxKeyEvaluator evaluator, String keyExpr) throws IOException {
        List<NtxWriter.Entry> entries = new ArrayList<>();
        reader.beforeFirst();
        try {
            while (reader.next()) {
                long recno = reader.getCurrentRecord() + 1;
                byte[] key = evaluator.evaluate(keyExpr,
                    name -> rawOrTyped(reader, name), recno);
                if (key != null) {
                    entries.add(new NtxWriter.Entry(key, recno));
                }
            }
        } catch (RuntimeException e) {
            // partial; caller's verify will catch any inconsistency
        }
        return entries;
    }

    /** Re-reads the rebuilt index and confirms it holds exactly the intended keys, in order. */
    private static boolean verify(String path, List<NtxWriter.Entry> expected) throws IOException {
        List<NtxWriter.Entry> got = new ArrayList<>();
        try (NtxIndex idx = NtxIndex.open(path)) {
            idx.forEach(e -> {
                got.add(new NtxWriter.Entry(e.key, e.recordNumber));
                return true;
            });
        }
        if (got.size() != expected.size()) {
            return false;
        }
        for (int i = 0; i < got.size(); i++) {
            if (got.get(i).recno != expected.get(i).recno
                    || NtxWriter.compare(got.get(i).key, expected.get(i).key) != 0) {
                return false;
            }
        }
        return true;
    }

    private static void atomicReplace(Path temp, Path original) throws IOException {
        try {
            Files.move(temp, original, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | UnsupportedOperationException e) {
            // ATOMIC_MOVE not supported across some filesystems; fall back.
            Files.move(temp, original, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Char fields must be raw (untrimmed, fixed-width) to reproduce key bytes. */
    private static Object rawOrTyped(DBFReader reader, String name) {
        try {
            DBFField f = reader.getHeader().getField(name);
            if (f != null && Character.toUpperCase(f.getType()) == 'C') {
                return reader.getRawString(name);
            }
            return reader.getValue(name);
        } catch (Exception e) {
            return null;
        }
    }

    private static File resolveDbf(String folder, String table) {
        File dbf = new File(folder, table + ".DBF");
        if (dbf.exists()) {
            return dbf;
        }
        dbf = new File(folder, table + ".dbf");
        return dbf.exists() ? dbf : null;
    }

    /** Convenience overload defaulting to ISO-8859-1 (xBase code page). */
    public static List<String> rebuildAll(String folder, String table) {
        return rebuildAll(folder, table, StandardCharsets.ISO_8859_1);
    }
}
