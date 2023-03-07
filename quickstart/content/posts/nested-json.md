---
title: "Build Nested JSON in PostgreSQL"
date: 2023-03-07T10:03:56+08:00
draft: true
---

# Build nested JSON in PostgreSQL

My note of this stack overflow thread. 

## 

## Approach 2

### Why Cost is so high ?
- Each Sub-node has to be executed `N` times, where `N` is number of `person` 

## Summary

I think putting sub-query in SELECT-List is elegant, but it's costly.

https://medium.com/@e850506/note-more-nested-json-5f3c1e4a87e