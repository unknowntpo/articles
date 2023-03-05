# Understand false sharing with a simple example


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





