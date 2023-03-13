---
title: "Understand false sharing with a simple example"
date: 2023-03-05T11:00:44+08:00
draft: true
tags: ['performance', 'Concurrency', 'Go']
---

# False Sharing

## The Problem of false sharing =

### Example: slice of counters

#### Measure the performance with `go bench`

#### Detect cache miss with linux `perf`

## Example Usage of padding in standard library: `sync.Pool`

```go
type poolLocal struct {
	poolLocalInternal

	// Prevents false sharing on widespread platforms with
	// 128 mod (cache line size) = 0 .
	pad [128 - unsafe.Sizeof(poolLocalInternal{})%128]byte
}
```




