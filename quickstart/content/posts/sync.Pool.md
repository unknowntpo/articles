---
title: "Sync.Pool"
date: 2023-02-13T18:50:03+08:00
draft: false
---

Experiment:

To demonstrate the improvement of our code, I design a simple benchmark,

There are three ways we can get data from database.

- Use `[]Author` to hold the data (Structure Binding)
- Use `[][]string` to hold the data (Unify Container without `sync.Pool`)
- Use `[][]string` to hold the data, and use sync.Pool to reuse `[][]string` (Unify Container with sync.Pool)


For row number between 1000, 10000, 100000, 200000, 500000, 1000000,
we use 8 worker to perform 16 jobs, every job gets all rows from the `author` table.

```
$ make bench/all BENCHTIME=3s
go test -benchmem -benchtime=3s \
		-bench=BenchmarkContainer \
		-cpuprofile=data/all_cpu.prof \
		-memprofile=data/alL_mem.prof | tee data/result_all.txt
goos: darwin
goarch: arm64
pkg: github.com/unknowntpo/playground-2022/go/xorm/unifyContainer
BenchmarkContainer/StructureBinding-1000-8         	    5053	    653425 ns/op	  105773 B/op	    3169 allocs/op
BenchmarkContainer/UnifyContainerWithPool-1000-8   	    5924	    611228 ns/op	   70151 B/op	    1935 allocs/op
BenchmarkContainer/UnifyContainerNoPool-1000-8     	    5208	    651323 ns/op	  102072 B/op	    3114 allocs/op
BenchmarkContainer/StructureBinding-10000-8        	    5427	    659206 ns/op	  105489 B/op	    3160 allocs/op
BenchmarkContainer/UnifyContainerWithPool-10000-8  	    6195	    604051 ns/op	   69708 B/op	    1921 allocs/op
BenchmarkContainer/UnifyContainerNoPool-10000-8    	    5478	    651302 ns/op	  101682 B/op	    3101 allocs/op
BenchmarkContainer/StructureBinding-100000-8       	    5546	    668369 ns/op	  105165 B/op	    3150 allocs/op
BenchmarkContainer/UnifyContainerWithPool-100000-8 	    5560	    617810 ns/op	   69864 B/op	    1926 allocs/op
BenchmarkContainer/UnifyContainerNoPool-100000-8   	    5415	    667851 ns/op	  101830 B/op	    3106 allocs/op
BenchmarkContainer/StructureBinding-200000-8       	       1	3274696000 ns/op	5253137944 B/op	156903591 allocs/op
BenchmarkContainer/UnifyContainerWithPool-200000-8 	    5420	    627802 ns/op	   69801 B/op	    1924 allocs/op
BenchmarkContainer/UnifyContainerNoPool-200000-8   	    5320	    676557 ns/op	  102103 B/op	    3115 allocs/op
BenchmarkContainer/StructureBinding-500000-8       	       1	8412835750 ns/op	13122194640 B/op	392252160 allocs/op
BenchmarkContainer/UnifyContainerWithPool-500000-8 	    5118	    635275 ns/op	   69935 B/op	    1928 allocs/op
BenchmarkContainer/UnifyContainerNoPool-500000-8   	    5277	    682187 ns/op	  101732 B/op	    3103 allocs/op
BenchmarkContainer/StructureBinding-1000000-8      	       1	16956583167 ns/op	26113702448 B/op	784498952 allocs/op
BenchmarkContainer/UnifyContainerWithPool-1000000-8         	    5800	    591626 ns/op	   69094 B/op	    1901 allocs/op
BenchmarkContainer/UnifyContainerNoPool-1000000-8           	    5558	    646176 ns/op	  101537 B/op	    3097 allocs/op
PASS
ok  	github.com/unknowntpo/playground-2022/go/xorm/unifyContainer	92.278s
```

The result shows that the number of allocation per operation is quite different,

The Structure Binding Method needs the largest number of allocations, and the speed is way slower that other two methods. When row number goes high, performance get worse very quickly.

The Method of using `[][]string` with `sync.Pool` on the other hand, 
needs smallest number of memory allocation,
and compare to the one without `sync.Pool`, and because memory allocation takes significant amount of time, it's still faster.

I put my code at the [repo](https://github.com/unknowntpo/playground-2022/tree/master/go/xorm/unifyContainer), please go check it out!
