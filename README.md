# DBF-JDBC-Driver

A pure-Java **JDBC driver for DBF (dBASE / FoxPro / Clipper xBase) database files** — query and modify `.dbf` tables with standard SQL, including aggregates, joins, a rich function library, transactions with rollback, multi-process safe writes, and **read + write support for Clipper `.NTX` indexes**.

No native libraries, no server — just point a JDBC URL at a folder of `.dbf` files.

```java
Class.forName("com.dbf.jdbc.DBFDriver");
try (Connection c = DriverManager.getConnection("jdbc:dbf:C:/data");
     Statement st = c.createStatement();
     ResultSet rs = st.executeQuery(
         "SELECT TOP 25 C_HEAD, CUST_DESC FROM MASTER " +
         "WHERE L_FLAG = 'X' AND UCASE(CUST_DESC) LIKE 'CA%' " +
         "ORDER BY UCASE(CUST_DESC)")) {
    while (rs.next()) {
        System.out.println(rs.getInt("C_HEAD") + "  " + rs.getString("CUST_DESC"));
    }
}
```

---

## Table of contents

- [Features](#features)
- [Supported xBase / DBF formats](#supported-xbase--dbf-formats)
- [Getting started](#getting-started)
- [Connection URL & options](#connection-url--options)
- [SQL reference](#sql-reference)
  - [SELECT](#select)
  - [DDL & DML](#ddl--dml)
  - [Data types](#data-types-create-table)
  - [Functions](#functions)
  - [Operators & literals](#operators--literals)
- [Indexes (Clipper .NTX)](#indexes-clipper-ntx)
- [Transactions & concurrency](#transactions--concurrency)
- [JDBC support](#jdbc-support)
- [Limitations](#limitations)
- [Building](#building)
- [Credits & license](#credits--license)

---

## Features

- **Pure Java**, single jar, zero dependencies — works anywhere a JDBC `DriverManager` does.
- **SQL query engine**: `SELECT` with `WHERE`, `GROUP BY`, `HAVING`, `ORDER BY`, `TOP n` / `LIMIT n`, joins (`INNER` / `LEFT`), table aliases and self-joins.
- **Aggregates** (`COUNT`, `SUM`, `AVG`, `MIN`, `MAX`) over columns *and* expressions, including aggregates over joined tables.
- **Large, SQL:2003-aligned function library** — string, numeric, date/time and conditional functions, plus `CAST`, `EXTRACT` and `CASE WHEN`.
- **DML**: `INSERT`, `UPDATE`, `DELETE`, batched `PreparedStatement` inserts, `getGeneratedKeys()` (returns RECNO).
- **DDL**: `CREATE TABLE`, `DROP TABLE`, `ALTER TABLE ADD/DROP/RENAME COLUMN/RENAME TO`.
- **Memory-safe execution**: external merge-sort (spills to disk) for `ORDER BY` and a partitioned hash join for joins — handles datasets larger than the heap.
- **Clipper `.NTX` index support**: automatic index seeks for fast lookups, plus optional index maintenance on writes (opt-in).
- **Transactions**: real `commit()` / `rollback()` via an undo log.
- **Concurrency**: cross-process exclusive write locking so multiple users can write safely.
- **Memo fields**: reads/writes `.DBT` (dBASE III/IV) and `.FPT` (FoxPro) memo files.
- **Quoted identifiers**: `` `name` `` (MySQL-style) and `[name]` (MS-Access/SQL-Server-style).

---

## Supported xBase / DBF formats

| Format | Read | Write | Memo file | Notes |
|---|:---:|:---:|---|---|
| dBASE III / III+ | ✅ | ✅ | `.DBT` | Default write format |
| dBASE IV | ✅ | ➖ | `.DBT` | Read; written as dBASE III |
| dBASE 5 | ✅ | ➖ | `.DBT` | Read |
| FoxPro 2.x / Visual FoxPro | ✅ | ➖ | `.FPT` | Read; FoxPro memo supported |
| Clipper 5 | ✅ | ➖ | `.DBT` | Read (2-byte header terminator detected) |
| Clipper index `.NTX` | ✅ | ✅* | — | Seek + maintain (opt-in, see below) |

\* `.NTX` write/maintenance is **opt-in** (`indexWrite=on`) and only touches indexes the driver can reproduce byte-for-byte and verify.

**Field types**: `C` character, `N` numeric, `F` float, `D` date, `L` logical, `M` memo, `I` integer, plus `B`/`G`/`O`/`P` where present (version-dependent). A `NUMERIC(n,0)` field reads as an integer (`Long`); `NUMERIC(n,d>0)` reads as a `Double`.

> **Not supported:** dBASE 7 / Visual FoxPro auto-increment & special column types, `.CDX`/`.MDX`/`.NDX` index formats (only Clipper `.NTX` is implemented).

---

## Getting started

1. Add the driver jar (`dbf-jdbc-driver-1.0.0.jar`) to your classpath.
2. The driver auto-registers via `META-INF/services/java.sql.Driver`. (`Class.forName("com.dbf.jdbc.DBFDriver")` also works.)
3. Connect to the **folder** that contains your `.dbf` files. Each `.dbf` is one table (the table name is the file name without extension, case-insensitive).

```java
String url = "jdbc:dbf:/path/to/folder";        // Linux/macOS
String url = "jdbc:dbf:C:/accounts/data";        // Windows
Connection conn = DriverManager.getConnection(url);
```

Requires Java 8+ (developed and tested on a modern JDK).

---

## Connection URL & options

```
jdbc:dbf:<folder>[;key=value;key=value...]
```

Options can also be passed via a `java.util.Properties` argument.

| Option | Default | Meaning |
|---|---|---|
| `charset` | `UTF-8` | Character set used to decode/encode text fields (e.g. `Cp1252`, `ISO-8859-1`). |
| `indexRead` | `off` | When `on`, eligible queries seek a matching `.NTX` index instead of scanning. |
| `indexWrite` | `off` | When `on`, `INSERT`/`UPDATE`/`DELETE` keep matching `.NTX` indexes up to date. |
| `index` | — | Shortcut: `index=on` enables index reads; `index=off` disables both. |

```java
// Fast indexed lookups, leave indexes read-only
DriverManager.getConnection("jdbc:dbf:C:/data;indexRead=on");

// Indexed lookups and keep indexes in sync on writes
DriverManager.getConnection("jdbc:dbf:C:/data;indexRead=on;indexWrite=on");
```

---

## SQL reference

### SELECT

```sql
SELECT [TOP n] <select-list>
FROM   <table> [alias] [ {INNER|LEFT} JOIN <table> [alias] ON <a.col = b.col> ... ]
[WHERE  <condition>]
[GROUP BY <cols>]
[HAVING <condition>]
[ORDER BY <expr> [ASC|DESC] ...]
[LIMIT n]
```

- `SELECT *`, qualified columns (`M.CUST_DESC`), expressions and constants (`0 AS x`, `'' AS y`, `null AS z`).
- `RECNO()` pseudo-column (1-based record number).
- `TOP n` (with optional `PERCENT`, treated as a row count) and `LIMIT n`.
- `ORDER BY` on columns, expressions, or aggregate functions (incl. non-selected columns).
- Joins must use a single equi-join condition in `ON` (put any extra conditions in `WHERE`).

### DDL & DML

```sql
CREATE TABLE t (ID NUMERIC(6), NAME CHAR(30), BAL NUMERIC(12,2), DOB DATE, NOTE MEMO);
DROP TABLE t;
ALTER TABLE t ADD COLUMN EMAIL CHAR(40);
ALTER TABLE t DROP COLUMN EMAIL;
ALTER TABLE t RENAME COLUMN NAME TO FULLNAME;
ALTER TABLE t RENAME TO customers;

INSERT INTO t (ID, NAME) VALUES (1, 'Alice');
UPDATE t SET NAME = 'Bob' WHERE ID = 1;
DELETE FROM t WHERE ID = 1;     -- soft delete (xBase deleted flag)
```

`PreparedStatement` is supported, including batched inserts:

```java
try (PreparedStatement ps = conn.prepareStatement(
         "INSERT INTO t (ID, NAME) VALUES (?, ?)")) {
    for (...) { ps.setInt(1, id); ps.setString(2, name); ps.addBatch(); }
    ps.executeBatch();
}
```

### Data types (CREATE TABLE)

| SQL keyword(s) | DBF type | Notes |
|---|---|---|
| `CHAR`, `CHARACTER`, `VARCHAR` | `C` | length 1–254 |
| `NUMERIC`, `DECIMAL`, `NUMBER` | `N` | `NUMERIC(p[,s])` |
| `INT`, `INTEGER`, `SMALLINT`, `BIGINT` | `N` | integer (scale 0) |
| `DOUBLE`, `FLOAT`, `REAL` | `N` | scale 5 |
| `DATE` | `D` | 8 bytes |
| `BOOLEAN`, `LOGICAL`, `BIT` | `L` | 1 byte |
| `MEMO`, `TEXT`, `CLOB`, `LONGVARCHAR` | `M` | creates a `.DBT` |

Tables are written in **dBASE III+** format. Column names follow xBase rules (≤ 10 chars, letter first).

### Functions

**String:** `UPPER`/`UCASE`, `LOWER`/`LCASE`, `TRIM`/`ALLTRIM`, `LTRIM`, `RTRIM`, `LEN`/`LENGTH`, `CHAR_LENGTH`/`CHARACTER_LENGTH`, `OCTET_LENGTH`, `LEFT`, `RIGHT`, `SUBSTR`/`SUBSTRING`, `CONCAT`, `REPLACE`, `LPAD`, `RPAD`, `REPEAT`, `SPACE`, `REVERSE`, `ASCII`, `CHR`/`CHAR`, `INITCAP`, `LOCATE(sub,str[,start])`, `INSTR(str,sub)`, `POSITION(sub,str)`, `STR`, `VAL`

**Numeric:** `ABS`, `ROUND`, `INT`, `MOD`, `CEIL`/`CEILING`, `FLOOR`, `POWER`/`POW`, `SQRT`, `EXP`, `LN`, `LOG`, `LOG10`, `SIGN`, `TRUNC`/`TRUNCATE`, `PI`, `RAND`/`RANDOM`

**Date/time:** `YEAR`, `MONTH`, `DAY`, `HOUR`, `MINUTE`, `SECOND`, `CURRENT_DATE`, `CURRENT_TIME`, `CURRENT_TIMESTAMP`, `NOW`, `DATEDIFF(d1,d2)`, `DATEADD(date,days)`, `DAYOFWEEK`, `DAYOFYEAR`, `QUARTER`, `WEEK`, `LAST_DAY`, `MONTHNAME`, `DAYNAME`, `TO_DATE`

**Conditional / null:** `ISNULL`, `ISBLANK`, `NVL`, `NVL2`, `COALESCE`, `NULLIF`, `IIF`, `DECODE`, `GREATEST`, `LEAST`

**Aggregate:** `COUNT`, `SUM`, `AVG`, `MIN`, `MAX`

**Special grammar:**

```sql
CAST(expr AS INTEGER|NUMERIC(p,s)|CHAR|DATE|...)
EXTRACT(YEAR|MONTH|DAY|QUARTER|... FROM d)
CASE WHEN cond THEN r [WHEN ...] [ELSE e] END      -- searched
CASE x WHEN v THEN r [WHEN ...] [ELSE e] END        -- simple
```

`CURRENT_DATE`, `CURRENT_TIME`, `CURRENT_TIMESTAMP` may be used with or without parentheses.

**Arithmetic is type-preserving:** `int + int = int`, `int + double = double`, `SUM(int) = int`, `AVG(int) = int`, `SUM(double)`/`AVG(double) = double` — so an integer value comes back as `11455`, not `11455.0`.

### Operators & literals

- Comparison: `=`, `<>` / `!=`, `<`, `<=`, `>`, `>=`
- Logical: `AND`, `OR`, `NOT`
- `LIKE` (`%`, `_`), `IN (...)`, `BETWEEN`, `IS [NOT] NULL`
- String concatenation with `+` (when either side is non-numeric)
- Date literals MS-Access style: `#2025-04-01#` or `#04/01/2025#`
- String literals in single quotes: `'X'`

---

## Indexes (Clipper .NTX)

The driver can read and maintain **Clipper `.NTX`** B-tree indexes that sit alongside your `.dbf`.

- **Reads** (`indexRead=on`): when a query's `WHERE` matches an index's leading key columns (equality and/or a prefix `LIKE 'abc%'`), the driver **seeks** the index instead of scanning. If the query also has `ORDER BY` on the indexed column and `TOP n`, it streams just the first *n* rows in index order — so type-ahead lookups stay fast even for broad prefixes.
- **Writes** (`indexWrite=on`): `INSERT`/`UPDATE`/`DELETE` incrementally update every index the driver can safely maintain; anything it can't reproduce/verify is left untouched (never corrupted).
- **Introspection**: `DatabaseMetaData.getIndexInfo(...)` lists each table's indexes, their columns and key expressions.

Index usage is **automatic** — you never name an index in SQL. The driver picks the most selective applicable index, and falls back to a full scan when none applies.

```java
// See which indexes exist for a table
ResultSet ix = conn.getMetaData().getIndexInfo(null, null, "MASTER", false, true);
```

---

## Transactions & concurrency

```java
conn.setAutoCommit(false);
try (Statement st = conn.createStatement()) {
    st.executeUpdate("UPDATE accounts SET bal = bal - 100 WHERE id = 1");
    st.executeUpdate("UPDATE accounts SET bal = bal + 100 WHERE id = 2");
    conn.commit();      // persist; rebuilds any maintained indexes
} catch (SQLException e) {
    conn.rollback();    // undo every change
}
```

- **Rollback** uses an undo log (no whole-file copy): it restores overwritten records and removes appended rows.
- **Multi-writer safety**: every write takes an **exclusive OS-level lock** on the table (via a `<table>.dbf.lck` file), so multiple processes/users can write concurrently without corrupting the file. Locks are released at `commit()`/`rollback()` (or per statement in auto-commit mode).
- The `Connection` object itself is thread-safe.

> Isolation level is **read-uncommitted**: the lock prevents *corruption*, not dirty reads. Concurrent writes to the **same** table are serialized; different tables are independent.

---

## JDBC support

- `Driver`, `Connection`, `Statement`, `PreparedStatement`, `ResultSet`, `ResultSetMetaData`, `DatabaseMetaData`.
- `getGeneratedKeys()` returns the new RECNO(s).
- Type mapping: DBF `C → CHAR`, `N/F → NUMERIC`, `D → DATE`, `L → BOOLEAN`, `M → CLOB` (single source of truth in `com.dbf.jdbc.dbf.DbfType`).
- `jdbcCompliant()` returns `false` — this is a focused driver for xBase files, not a full SQL-92/JDBC-compliance implementation.

---

## Limitations

- Writes are emitted in **dBASE III+** format (reads cover more versions).
- Only **Clipper `.NTX`** indexes are supported (not `.CDX`/`.MDX`/`.NDX`).
- Join `ON` must be a single equi-join (put extra conditions in `WHERE`).
- Isolation is **read-uncommitted**; no savepoints.
- `CREATE`/`DROP`/`ALTER` are not covered by the write lock.
- Not implemented: window/OLAP functions, CTEs (`WITH`), `MERGE`, `POSITION(sub IN str)` infix form, `SUBSTRING(x FROM a FOR b)`, `OVERLAY`, `TRIM(LEADING/TRAILING ... FROM ...)`.

---

## Building

This is an Eclipse Java project. To build the jar from the command line:

```bash
# compile
javac -d build/classes -encoding UTF-8 $(find src -name '*.java')

# package
jar --create --file dbf-jdbc-driver-1.0.0.jar -C build/classes .
```

The `META-INF/services/java.sql.Driver` service file registers the driver automatically.

---

## Credits & license

xBase / Clipper format constants and memo-file handling were informed by the
[DANS dbf-lib](https://github.com/DANS-KNAW/dans-dbf-lib) project (Apache License 2.0).

Released under the **Apache License 2.0** (see `LICENSE`).
