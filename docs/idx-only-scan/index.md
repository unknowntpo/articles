# Optimize a SELECT query up to 60x faster


## Identifying the root cause

```
SELECT
 dbKey.*, finalDBData.*
FROM
    dbKey,
    (
    SELECT
    *,
    rank() OVER (PARTITION BY key_id ORDER BY TIMESTAMP DESC) AS rank
    FROM
    dbData where "timestamp" <= 101) finalDBData
where rank =1 
and finalDBData.key_id = dbKey.id;
```

Because it has to scan the whole `dbData` table,
partition it by `key_id`,
and rank the timestamp,

<reason of seq scan>
planner tend to range over every row in data table to get `rank=1` data, then join it with key table, finally returns it back to client.

so it's so slow,
we current have about `30000` keys in key table, each project has about `2000` keys, and almost `100` milion
data rows in data table. 
It usually takes at least 60 second to get the particular version of data. 

## Improvement: Index-Only Scan

But this query still can be better,

There's a new feature introduced in PostgreSQL 9.2, which allow us to get data from index itself, without touching the actual table data.

The [documentation](https://www.postgresql.org/docs/current/indexes-index-only-scans.html) stats that
There are two fundamental restrictions on when this method can be used:

> 1. The index type must support index-only scans. B-tree indexes always do. GiST and SP-GiST indexes support index-only scans for some operator classes but not others. Other index types have no support. The underlying requirement is that the index must physically store, or else be able to reconstruct, the original data value for each index entry. As a counterexample, GIN indexes cannot support index-only scans because each index entry typically holds only part of the original data value.
> 2. The query must reference only columns stored in the index. For example, given an index on columns x and y of a table that also has a column z, these queries could use index-only scans:

The first one is staicfied because we are using `B-tree` index.

The second one can be satisfied by modifying our SQL query,

We can build the mapping between key_id and the `rank=1` timestamp first,

```sql
WITH map AS (
  SELECT 
    key_id,
    timestamp
  FROM (
    SELECT
     key_id,
     timestamp,
     rank() OVER (PARTITION BY key_id ORDER BY TIMESTAMP DESC) AS rank
    FROM
    dbData
    where "timestamp" <= 10000 and key_id < 100
) sub WHERE rank = 1)
SELECT * FROM map; 
```

Result will be like:

```
 key_id | timestamp
--------+-----------
      1 |     10000
      2 |     300
      3 |     6000
      4 |     90303
```

and then, get actual data from `dbData` with specific `key_id` and `timestamp` pair.

```sql
WITH map AS (
  SELECT 
    key_id,
    timestamp
  FROM (
    SELECT
     key_id,
     timestamp,
     rank() OVER (PARTITION BY key_id ORDER BY TIMESTAMP DESC) AS rank
    FROM
    dbData
    where "timestamp" <= 10000 and key_id < 100
) sub WHERE rank = 1) 
SELECT
 dbKey.*, dbData.*
FROM
 dbKey
 INNER JOIN map m ON m.key_id = dbKey.id
 INNER JOIN dbData ON dbData.key_id = m.key_id AND m.timestamp = dbData.timestamp;
```

The reason we build the `map` first is that the select list in `map` are all stored in the index,
which satisfied requirement `2` in the documentation,
and later when we query `dbData` , we can still have Index Scan.

Here's the example [ query plan ](https://explain.dalibo.com/plan/86114340afae7349)

Reference:
- [PostgreSQL Wiki]( https://wiki.postgresql.org/wiki/Index-only_scans )
