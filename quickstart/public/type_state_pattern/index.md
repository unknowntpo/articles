# API Design: Use type state pattern to avoid ambiguous option flags


For example，`ZADD` is a command that add member with score to sorted set, and it can accept `NX` or `XX` as option.

```
ZADD key [NX | XX] [GT | LT] [CH] [INCR] score member [score member
  ...]
```

* XX: Only update elements that already exist. Don't add new elements.
* NX: Only add new elements. Don't update already existing elements.
`NX` 與 `XX` 只能選一個，在 go-redis 是用這樣的 structure 來裝 argument, 但這就必須要額外的註解與檢查來告訴使用者 `NX`, `XX` 是互斥的。

```
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

但在 `redis/rueidis` 裡面提供了 command builder, 
Type System 就會直接禁止你同時設定 `NX`, `XX`

```go
client.B().Zadd().Key("1").Nx().Gt().Ch().Incr().ScoreMember().ScoreMember(1, "1").ScoreMember(1, "1").Build()
```


