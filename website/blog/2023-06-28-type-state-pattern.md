---
title: "API Design: Use type state pattern to avoid ambiguous option flags"
slug: type-state-pattern
authors: [unknowntpo]
tags: [Go, Redis]
---

For example, `ZADD` is a command that add member with score to sorted set, and it can accept `NX` or `XX` as option.

```
ZADD key [NX | XX] [GT | LT] [CH] [INCR] score member [score member
  ...]
```

* XX: Only update elements that already exist. Don't add new elements.
* NX: Only add new elements. Don't update already existing elements.

`NX` and `XX` can only choose one. In go-redis, this structure is used to hold arguments, but this requires extra comments and checks to tell users that `NX` and `XX` are mutually exclusive.

```go
type ZAddArgs struct {
	NX      bool
	XX      bool
	LT      bool
	GT      bool
	Ch      bool
	Members []Z
}
```

Ref: [go-redis ZAddArgs](https://pkg.go.dev/github.com/redis/go-redis/v9#ZAddArgs)

But in `redis/rueidis`, it provides a command builder where the type system directly prevents you from setting both `NX` and `XX` at the same time.

```go
client.B().Zadd().Key("1").Nx().Gt().Ch().Incr().ScoreMember().ScoreMember(1, "1").ScoreMember(1, "1").Build()
```
