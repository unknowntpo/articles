# Build Nested JSON in PostgreSQL


Original Stackoverflow thread: 

https://stackoverflow.com/questions/42222968/create-nested-json-from-sql-query-postgres-9-4/42226253#42226253

Suppose we have this tables:

person
car
wheel
And the relation between is:

person:car = 1:N
car:wheel = 1:N
We need to build some nested JSON Object with SQL Query to get the summary about details of each car this person has, what would you do ?

## The Goal

```json
{
    "persons": [
        {
            "person_name": "Johny",
            "cars": [
                {
                    "carid": 1,
                    "type": "Toyota",
                    "comment": "nice car",
                    "wheels": [
                        {
                            "which": "front",
                            "serial number": 11
                        },
                        {
                            "which": "back",
                            "serial number": 12
                        }
                    ]
                },
                {
                    "carid": 2,
                    "type": "Fiat",
                    "comment": "nice car",
                    "wheels": [
                        {
                            "which": "front",
                            "serial number": 21
                        },
                        {
                            "which": "back",
                            "serial number": 22
                        }
                    ]
                }
            ]
        },
        {
            "person_name": "Freddy",
            "cars": [
                {
                    "carid": 3,
                    "type": "Opel",
                    "comment": "nice car",
                    "wheels": [
                        {
                            "which": "front",
                            "serial number": 3
                        }
                    ]
                }
            ]
        }
    ]
}
```

## Approach 1 - Left Join

```
select
    json_build_object(
        'persons', json_agg(
            json_build_object(
                'person_name', p.name,
                'cars', cars
            )
        )
    ) persons
from person p
left join (
    select 
        personid,
        json_agg(
            json_build_object(
                'carid', c.id,    
                'type', c.type,
                'comment', 'nice car', -- this is constant
                'wheels', wheels
                )
            ) cars
    from
        car c
        left join (
            select 
                carid, 
                json_agg(
                    json_build_object(
                        'which', w.whichone,
                        'serial number', w.serialnumber
                    )
                ) wheels
            from wheel w
            group by 1
        ) w on c.id = w.carid
    group by personid
) c on p.id = c.personid;
```


## Approach 2 - Put sub-query in SELECT-List with `json_build_object` and `json_agg`

This is the SQL query based on Nico Van Belle's [`answer`](https://stackoverflow.com/a/42226843), but I replaced `row_to_json` with `json_buid_object`. 

```
select json_build_object(
 'persons', (
       SELECT json_agg(
        json_build_object(
         'person_id',id,
         'cars', (
           SELECT json_agg(
              json_build_object(
                 'car_id', car.id,
                 'wheels', (
                    SELECT json_agg(
                      json_build_object(
                      'wheel_id', wheel.id,
                      'whichone', wheel.whichone,
                      'serialnumber', wheel.serialnumber,
                      'car_id', wheel.carid
                        )
                    )
                   FROM wheel WHERE wheel.carid = car.id
               )    
                 )
            ) FROM car WHERE id = person.id
         )
          )
       ) FROM person
  )
);
```

You can view the result ojnline with [db<>fiddle](https://dbfiddle.uk/8CTditFw)

### Why Cost is so high ?
- Each Sub-node has to be executed `N` times, where `N` is number of `person` 

#### Query Plan
- [Approach1 - Left JOIN](https://explain.dalibo.com/plan/f1d53241e28413eg#query)
- [Approach2 - sub-query in SELECT-List](https://explain.dalibo.com/plan/847d8fc59f61e6df)

## Summary

I think putting sub-query in SELECT-List is elegant, but it's costly.

https://medium.com/@e850506/note-more-nested-json-5f3c1e4a87e
