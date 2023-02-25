---
title: "Optimize a PARTITION - SELECT query up to 60x faster"
date: 2023-02-12T14:23:03+08:00
draft: false
tags: ['performance', 'PostgreSQL']
---

This post demonstrates my experience of optimizing a PARTITION - SELECT query,
and how I made it up to 60x faster.

## Original Query and the use case

Our App is a simple excel data version control system,
the data is organized by project,
key and data is stored in seperated table called `dbKey` and `dbData` .

```sql
create table dbKey (
 id serial ,
 project_id int,
-- keys goes here
-- NOTE: key can be 1...N fields, and we use string.Join(fields, sep)
-- to handle it has the key string in backend service
 name text 
);
create table dbData (
 id serial ,
 key_id int ,
 timestamp int 

 ---  data stores at here
);
```

and there's also a sheet_version table that stores the `version`, `timestamp` information.

```sql
create table sheet_version (
 id serial ,
 version integer,
 timestamp int 
);
```

Every time we need to get specific version of data (let's say: version `2` ), we access `sheet_version`  table first,
and get the `sheet_version.timestamp` to construct the `PARTITION - SELECT` query. 

To get the actual data, we need to do these steps:
1. Partition the data table `dbData` by `key_id`,
2. Rank it by `timestamp (DESC)`, get the `rank=1` datas from `dbData`
3. Join `dbKey` and `dbData` back togetter.

Here's the query:

```sql
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
where
dbKey.project_id = 10
and rank =1 
and finalDBData.key_id = dbKey.id;
```

Here's the [db<>fiddle](https://dbfiddle.uk/CKXXlQUE) you can play with this query.

>We choose this design because it can save a lot of space to store every version of data.
If version `2` has 10 keys, each key has 50 data,
and if we change data under only 1 key, we only have to re-insert all data under this modified key.
and only need to insert `50` data.
Of course, this design has some limitations, but in this post, let's focus on the `PARTITION - SELECT` query optimization.

## Identifying the root cause

```sql
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

### Useless index and time-consuming Sequential scan

This query is slow because it has to:

1. Scan the whole `dbData` table
2. partition it by `key_id`, and rank the timestamp.
3. Join it with `dbKey` table with `rank=1` and `finalDBData.key_id = dbKey.id`

Planner tends to range over every row in data table to get `rank=1` data
because the `rank=1` `key_id - timestamp` can be anywhere in the whole table. 

This query it's so slow, we current have about `30000` keys in key table,
each project has about `2000` keys, and almost `100` milion
data rows in data table,
it usually takes at least `60` second to get the particular version of data. 

Here's the plan of this query:

```
------------------------------------------------------------------------------------------------------------------------------------------------------------------
 Hash Join  (cost=1125351.65..1289874.58 rows=5621 width=57) (actual time=9082.308..9468.256 rows=11020 loops=1)
   Output: dbkey.id, dbkey.name, finaldbdata.id, finaldbdata.key_id, finaldbdata."timestamp", finaldbdata.rank
   Hash Cond: (finaldbdata.key_id = dbkey.id)
   Buffers: shared hit=358 read=545756, temp read=3000 written=3018
   ->  Subquery Scan on finaldbdata  (cost=1125043.98..1289482.62 rows=5614 width=20) (actual time=9077.986..9459.255 rows=11000 loops=1)
         Output: finaldbdata.id, finaldbdata.key_id, finaldbdata."timestamp", finaldbdata.rank
         Filter: (finaldbdata.rank = 1)
         Rows Removed by Filter: 1100200
         Buffers: shared hit=274 read=545756, temp read=3000 written=3018
         ->  WindowAgg  (cost=1125043.98..1275448.81 rows=1122705 width=20) (actual time=9077.985..9432.015 rows=1111200 loops=1)
               Output: dbdata.id, dbdata.key_id, dbdata."timestamp", rank() OVER (?)
               Buffers: shared hit=274 read=545756, temp read=3000 written=3018
               ->  Gather Merge  (cost=1125043.98..1255801.47 rows=1122705 width=12) (actual time=9077.972..9174.199 rows=1111200 loops=1)
                     Output: dbdata.key_id, dbdata."timestamp", dbdata.id
                     Workers Planned: 2
                     Workers Launched: 2
                     Buffers: shared hit=274 read=545756, temp read=3000 written=3018
                     ->  Sort  (cost=1124043.95..1125213.44 rows=467794 width=12) (actual time=9060.365..9078.656 rows=370400 loops=3)
                           Output: dbdata.key_id, dbdata."timestamp", dbdata.id
                           Sort Key: dbdata.key_id, dbdata."timestamp" DESC
                           Sort Method: external merge  Disk: 8304kB
                           Buffers: shared hit=274 read=545756, temp read=3000 written=3018
                           Worker 0:  actual time=9048.365..9066.503 rows=354371 loops=1
                             Sort Method: external merge  Disk: 7656kB
                             Buffers: shared hit=105 read=175482, temp read=957 written=963
                           Worker 1:  actual time=9060.662..9079.499 rows=372284 loops=1
                             Sort Method: external merge  Disk: 8040kB
                             Buffers: shared hit=105 read=180922, temp read=1005 written=1011
                           ->  Parallel Seq Scan on public.dbdata  (cost=0.00..1071990.75 rows=467794 width=12) (actual time=5.360..8698.716 rows=370400 loops=3)
                                 Output: dbdata.key_id, dbdata."timestamp", dbdata.id
                                 Filter: (dbdata."timestamp" <= 101)
                                 Rows Removed by Filter: 33296333
                                 Buffers: shared hit=192 read=545756
                                 Worker 0:  actual time=4.511..8532.085 rows=354371 loops=1
                                   Buffers: shared hit=64 read=175482
                                 Worker 1:  actual time=3.410..8640.241 rows=372284 loops=1
                                   Buffers: shared hit=64 read=180922
   ->  Hash  (cost=183.41..183.41 rows=9941 width=37) (actual time=4.312..4.313 rows=10010 loops=1)
         Output: dbkey.id, dbkey.name
         Buckets: 16384  Batches: 1  Memory Usage: 803kB
         Buffers: shared hit=84
         ->  Seq Scan on public.dbkey  (cost=0.00..183.41 rows=9941 width=37) (actual time=0.007..1.395 rows=10010 loops=1)
```

And you can also view it on [explain.dalibo.com](https://explain.dalibo.com/plan/605d87d87h149730
)




## Approach 1: Materialized View

We can use materialized view to cache the result set of particalar version of data,
but the first one who needs to get data still suffers from the slow query.

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

## Final choice: I want them all! 

We decided to use this optimized query to build the materialized view,
and maintain a materialized view (we call it `mat_view` for short) management system to organize the creation, deletion of these mat_views.

Reference:
- [PostgreSQL Wiki]( https://wiki.postgresql.org/wiki/Index-only_scans )