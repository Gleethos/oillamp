# Sprouts cheat sheet (verified against `io.github.globaltcad:sprouts:2.8.0`)

oillamp's records hold Sprouts persistent collections instead of `java.util.List`, `Set` and
`Map` (see "Coding conventions" in [CONTRIBUTING.md](../CONTRIBUTING.md)). These collections are
immutable: every "change" returns a new collection and leaves the old one as it was. This sheet
lists the methods you will need. Read it before writing a record.

## Tuple&lt;T&gt;: an ordered sequence

```java
Tuple<String> empty   = Tuple.of(String.class);              // ALWAYS pass the class for empty
Tuple<String> some    = Tuple.of(String.class, "a", "b");    // preferred: explicit element type
Tuple<String> fromIt  = Tuple.of(String.class, someIterable);
Tuple<Step>   built   = empty.add(step).addAll(more);        // returns a NEW tuple (structural sharing)
```

| Need | Method |
|---|---|
| size / emptiness | `size()`, `isEmpty()`, `isNotEmpty()` |
| element access | `get(int)`, `first()`, `last()` |
| grow | `add(T)`, `addAll(T...)`, `addAt(int,T)`, `addAllAt(int,Tuple)` |
| shrink | `remove(T)`, `removeAt(int)`, `removeIf(Predicate)`, `removeFirst()`, `removeLast()` |
| query | `contains(T)`, `any(Predicate)`, `all(Predicate)`, `none(Predicate)`, `firstIndexOf(T)` |
| transform | `map(Function<T,T>)`, `mapTo(Class<U>, Function<T,U>)`, `sort(Comparator)`, `reversed()`, `makeDistinct()` |
| collect | `stream().collect(Tuple.collectorOf(T.class))` |
| generic type token | `Tuple.classTyped(T.class)` |

`Tuple` is `Iterable<T>`, so enhanced `for` and Groovy's `each`/`collect`/`find` work in specs.

## ValueSet&lt;E&gt;: a set

```java
ValueSet<String> pkgs = ValueSet.of(String.class);           // empty
ValueSet<String> some = ValueSet.of("podman", "uidmap");
pkgs = pkgs.add("socat").addAll(other);
boolean has = pkgs.contains("podman");
```

Also: `of(Class,Iterable)`, `ofLinked(...)` (insertion order), `ofSorted(...)`, `containsAll(...)`,
`stream().collect(ValueSet.collectorOf(E.class))`.

> Use `ofLinked` / `ofSorted` whenever the iteration order is observable (console output,
> golden files, hashes). Plain `of` does **not** promise an order.

## Association&lt;K,V&gt;: a map

```java
Association<String,String> env = Association.between(String.class, String.class);
env = env.put("OILLAMP_SESSION", session);
Optional<String> v = env.get("OILLAMP_SESSION");             // Optional, never null
```

| Need | Method |
|---|---|
| empty | `between(K.class, V.class)`, `betweenLinked(...)`, `betweenSorted(...)` |
| single entry | `Association.of(k, v)` |
| write | `put(K,V)`, `putIfAbsent(K,V)`, `putAll(...)`, `remove(K)` |
| read | `get(K) -> Optional<V>`, `containsKey(K)`, `keySet() -> ValueSet<K>`, `values() -> Tuple<V>` |
| iterate | `Iterable<Pair<K,V>>`: `pair.first()` / `pair.second()` |

> `runtime.env` and podman `--build-arg` rendering must be **deterministic**, so build those
> with `betweenSorted(String.class, String.class)` or sort the key set before rendering.

## Rules

- No `java.util.List/Set/Map` and no arrays in any record component.
- Empty collections are created with the `Class` overload. `Tuple.of()` without a class
  cannot infer the element type and will not compile where a `Tuple<X>` is expected.
- Every "builder" in a pure function is just repeated `add`/`put` on the returned value.
