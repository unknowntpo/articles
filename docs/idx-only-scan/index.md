# Part3: Use Index-Only Scan to make our query even faster


But this query still can be better,

There's a new feature introduced in PostgreSQL 9.2, which allow us to get data from index itself, without touching the actual table data.

:round_pushpin: TODO: consider the visibility map


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

Here's the example [ query plan ](https://explain.dalibo.com/plan/86114340afae7349)

Reference:
- [PostgreSQL Wiki]( https://wiki.postgresql.org/wiki/Index-only_scans )
