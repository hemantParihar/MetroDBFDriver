---
title: DBF-JDBC-Driver
description: Pure-Java JDBC driver for dBASE / FoxPro / Clipper xBase (.dbf) files
---

# DBF-JDBC-Driver

**Query and modify dBASE / FoxPro / Clipper `.dbf` files with standard SQL — pure Java, no server, no native libraries.**

```java
Class.forName("com.dbf.jdbc.DBFDriver");
try (Connection c = DriverManager.getConnection("jdbc:dbf:C:/data;indexRead=on");
     Statement st = c.createStatement();
     ResultSet rs = st.executeQuery(
         "SELECT TOP 25 C_HEAD, CUST_DESC FROM MASTER " +
         "WHERE L_FLAG = 'X' AND UCASE(CUST_DESC) LIKE 'CA%' " +
         "ORDER BY UCASE(CUST_DESC)")) {
    while (rs.next())
        System.out.println(rs.getInt("C_HEAD") + "  " + rs.getString("CUST_DESC"));
}
```

> Full documentation lives in the project [README](https://github.com/USER/DBF-JDBC-Driver#readme).
> Replace `USER` with your GitHub username after you push.

---

## Highlights

- **Standard SQL** over `.dbf` tables: `SELECT`, `WHERE`, `GROUP BY`, `HAVING`, `ORDER BY`, `TOP n`/`LIMIT n`, `INNER`/`LEFT JOIN`.
- **Aggregates** (`COUNT/SUM/AVG/MIN/MAX`) over columns and expressions, even across joins.
- **Big function library** (string / numeric / date / conditional) plus `CAST`, `EXTRACT`, `CASE WHEN`.
- **DML & DDL**: `INSERT`/`UPDATE`/`DELETE`, batched prepared inserts, `CREATE`/`DROP`/`ALTER TABLE`.
- **Clipper `.NTX` indexes**: automatic fast seeks (read) and optional maintenance (write).
- **Transactions** with real `commit()` / `rollback()` (undo log).
- **Multi-process safe writes** via OS-level file locking.
- **Memory-safe**: spill-to-disk sort and hash join for data larger than the heap.
- **Memo fields**: `.DBT` (dBASE) and `.FPT` (FoxPro).

## Supported formats

| Format | Read | Write |
|---|:---:|:---:|
| dBASE III / III+ | ✅ | ✅ |
| dBASE IV / 5 | ✅ | written as III+ |
| FoxPro 2.x / Visual FoxPro | ✅ | — |
| Clipper 5 | ✅ | — |
| Clipper `.NTX` index | ✅ | ✅ (opt-in) |

## Connection URL

```
jdbc:dbf:<folder>[;charset=Cp1252][;indexRead=on][;indexWrite=on]
```

| Option | Default | Meaning |
|---|---|---|
| `charset` | `UTF-8` | text field encoding |
| `indexRead` | `off` | seek `.NTX` indexes for matching queries |
| `indexWrite` | `off` | keep `.NTX` indexes updated on writes |
| `index` | — | `on` = enable reads, `off` = disable all |

See the [README](https://github.com/USER/DBF-JDBC-Driver#readme) for the full SQL grammar, function list, data types, transactions, indexing details and limitations.
