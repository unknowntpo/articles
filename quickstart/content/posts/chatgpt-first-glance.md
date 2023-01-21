---
title: "Chatgpt First Glance"
date: 2023-01-21T12:00:58+08:00
draft: false
author: unknowntpo
---


This is my first glance of ChatGPT, and I ask her to generate a peice of code in Haskell, which can map a function to a list.

The result she generated is totally correct, and can be run in [playground](https://play-haskell.tomsmeding.com/saved/Sir1t4Zr
).

```haskell
addOneToEach :: [Int] -> [Int]
addOneToEach xs = map (+1) xs

myMap :: (a -> b) -> [a] -> [b]
myMap _ [] = []
myMap f (x:xs) = f x : myMap f xs

main = do
   let myList = [1, 2, 3, 4]
   let doubledList = myMap (*2) myList
   print doubledList
   -- Output: [2,4,6,8]
```

Here's the link to our chat: https://sharegpt.com/c/yedzb1N