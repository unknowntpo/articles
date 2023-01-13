---
title: "Box Trait"
date: 2023-01-13T20:02:53+08:00
draft: true
---

This is the first time I practice Box trait in Rust!

```rust
struct Sheep {}
struct Cow {}

trait Animal {
    fn noise(&self) -> &'static str;
}

impl Animal for Sheep {
    fn noise(&self) -> &'static str {
        "baaaaah!"
    }
}

impl Animal for Cow {
    fn noise(&self) -> &'static str {
        "moooo!"
    }
}

fn random_animal(random_number: f64) -> Box<dyn Animal> {
    if random_number < 0.5 {
        Box::new(Sheep{})
    } else {
        Box::new(Cow{})
    }
}

fn main() {
    let random_number = 0.234;
    let animal = random_animal(random_number);
    println!("You've randomly chosen an animal, and it says {}", animal.noise())
}
```

Reference: [Returning Traits with dyn](https://doc.rust-lang.org/rust-by-example/trait/dyn.html#returning-traits-with-dyn)