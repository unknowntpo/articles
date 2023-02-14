---
title: "Optimize a SQL query for version controlling data up to 60x faster - Part 1: Slow Partition Query"
date: 2023-02-14T08:28:30+08:00
draft: true
---

This query is slow

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
It temd to range over every row in data table to get `rank=1` data, then join it with key table, finally returns it back to client.

so it's so slow,
we current have about `30000` keys in key table, each project has about `2000` keys, and almost `100` milion
data rows in data table. 
It usually takes at least 60 second to get the particular version of data. 