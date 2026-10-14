# ZDCache — Searchable Dimensions Cache (Java 21, Gradle)

An in-process, multi-type, per-type-bounded object cache with specification-driven searchable dimensions 
and secondary indexes. Cache writes are explicitly submitted; the cache does **not** track mutations to 
objects returned by reference.

## Installation

```xml
<dependency>
    <groupId>io.github.zdcache</groupId>
    <artifactId>zdcache</artifactId>
    <version>0.2.0</version>
</dependency>
```

```kotlin
implementation("io.github.zdcache:zdcache:0.2.0")
```

zdcache has no runtime dependencies, but the cache can use Caffeine under the hood if configured so.
To use `IdentityStoreFactory.caffeine()`, add `com.github.ben-manes.caffeine:caffeine` as well.

## Requirements and build

- JDK 21+
- Gradle 9.3.0 via `./gradlew` (Windows: `gradlew.bat`) or a locally installed Gradle
- Internet access on first build for Gradle and Maven Central dependencies

```sh
./gradlew test
./gradlew jmh             # JMH benchmarks against Caffeine, cache2k, Guava and CQEngine
./gradlew footprint       # heap bytes per cached entry
./gradlew build
```

**Wrapper note:** The official Gradle 9.7.0 wrapper JAR is committed in `gradle/wrapper`.
If it is missing, the included `gradlew`/`gradlew.bat` scripts download it and verify its
SHA-256 before running it. Alternatively, run `gradle test` with a locally installed Gradle.

## Quick start

```java
import io.github.zdcache.*;
import io.github.zdcache.store.IdentityStoreFactory;
import java.util.List;

record Address(String city, List<String> tags) {}
record Person(long id, String name, int age, List<Address> addresses) {}

var cache = ZDCache.builder()
    .defaultMaxPerType(10_000)
    .capacity(Person.class, 5_000)
    .build();

// IMPORTANT: reuse this exact identity instance in replacement specifications.
var personId = IdentityDimension.of(Person.class, "id", Person::id);

// Method references name the dimensions: name, age, addresses.city and addresses.tags.
var spec = SearchSpecification.builder(personId)
    .prefix(Person::name)
    .range(Person::age)
    .each(Person::addresses, address -> address
        .prefix(Address::city)
        .exactEach(Address::tags))
    .build();

cache.put(new Person(1, "Simon", 32,
    List.of(new Address("Oslo", List.of("home", "office")))), spec);
cache.put(new Person(2, "Simone", 25, List.of()));

// The method reference gives both the type and the dimension:
List<Person> people = cache.search(Person::name, "si");
List<Person> aged = cache.search(Person::age, ValueRange.between(20, 40));
// Nested dimensions by their path, as a compact string or as a chain of method references:
List<Person> inOslo = cache.search(Person.class, "addresses.city", "os");
List<Person> inOsloToo = cache.search(Person::addresses, Address::city, "os");
Person one = cache.get(Person.class, 1L);

// Full per-type reindex; unchanged identity object is required.
var nextSpec = SearchSpecification.builder(personId)
    .prefix(Person::name)
    .build();
cache.updateSpecification(Person.class, nextSpec);

// Switch identity-store implementations with no changes to the search code:
var usingCaffeine = ZDCache.builder()
    .identityStore(IdentityStoreFactory.caffeine())
    .build();

// The default buffered LRU store keeps strict LRU order; approximate recency trades
// exact order under heavy contention for higher concurrent throughput:
var forThroughput = ZDCache.builder()
    .recency(Recency.APPROXIMATE)
    .build();

// One lock per type is the default; Locking.SINGLE shares one lock across all types:
var serialized = ZDCache.builder()
    .locking(Locking.SINGLE)
    .build();

// Several calls across types as one step that no other thread sees half done:
cache.atomically(() -> {
    Person simon = cache.get(Person.class, 1L);
    cache.put(new Person(simon.id(), simon.name(), simon.age() + 1, simon.addresses()));
    cache.remove(Person.class, 2L);
});

// Reads that do not count as use, and housekeeping:
Person quiet = cache.peek(Person.class, 1L);
boolean cached = cache.contains(Person.class, 2L);
List<Person> everyone = cache.values(Person.class);
cache.clear(Person.class);
```

### Other ways to declare and search

Method references keep dimension names and searches in sync when a property is renamed; a chain of method references searches nested dimensions up to five levels deep, and deeper paths use the string. Explicit names, lambdas and property names are also supported, and every search can name its dimension as a string. A type has one specification, so use this equivalent declaration instead of the one above, not alongside it:

```java
var equivalentSpec = SearchSpecification.builder(personId)
    .prefix("name", Person::name)                                    // explicit name with a method reference
    .prefixEach("nameWords", p -> List.of(p.name().split(" ")))      // derived values need a lambda and a name
    .range("age")                                                    // property name, read by reflection
    .each("addresses", Person::addresses, address -> address
        .prefix("city", Address::city))
    .build();

List<Person> people = cache.search(Person.class, "name", "si");
List<Person> sameAgain = cache.search(Person.class, Person::name, "si");
List<Person> inOslo = cache.search(Person.class, "addresses.city", "os");
List<Person> aged = cache.search(Person.class, "age", ValueRange.between(20, 40));
```

## Basic/default specification

When the cache has not seen a type, an explicit-key insertion creates a basic,
key-only `SearchSpecification`. A key extractor cannot be inferred from an arbitrary
POJO or record, so use `put(Type.class, key, entity)` for the initial insertion:

```java
cache.put(Person.class, 42L, new Person(42, "Siri", 29, List.of()));
var existing = cache.getSpecification(Person.class);
// Capture its SAME IdentityDimension instance, even on later specification updates.
var upgraded = SearchSpecification.builder(existing.identity())
    .prefix(Person::name).build();
cache.updateSpecification(Person.class, upgraded);
cache.put(Person.class, 43L, new Person(43, "Simon", 28, List.of()));
```

An explicit-identity type continues to require the explicit-ID overload on writes.
A registered extracting identity supports `put(entity)` directly. Asking to search
an unregistered type or a basic-only type raises
`MissingSearchSpecificationStrategyException`. Asking for a missing dimension on a
searchable type raises `UnknownSearchDimensionException`.

## Identity, specification and update rules

- Each class has its own capacity, `IdentityStore`, specification and indexes, and by
  default its own `ReentrantReadWriteLock`, so operations on different types are
  independent. `locking(Locking.SINGLE)` makes all types share one lock: searches
  still run in parallel, but a write to any type waits for writes to every type.
- `atomically(...)` runs several calls, across types, so that no other thread sees
  the state in between. It takes the shared lock, or every type's lock in a fixed
  order, and a lock-free `get` that meets a running block waits for it. Nested
  blocks run inside the outer one.
- `put(entity, specification)` registers that spec on first use. Further inserts
  with a supplied spec must pass **the same specification object**. A different
  spec instance throws `ConflictingSearchSpecificationException`, even if it was
  built from equivalent lambdas. This intentionally avoids pretending lambdas
  have reliable structural equality.
- `updateSpecification(type, replacement)` is the *only* specification-changing
  API. It requires the **same identity-dimension object**, rebuilds *all*
  dimension indexes from cached entities, and publishes under the per-type lock.
  Failed extraction/indexing during this rebuild preserves the old specification.
- Ordinary `put` replaces the entity and removes its old secondary index entries
  using captured index keys. This works even if the old entity was mutated by an
  outside holder. Ordinary changes by reference are not observed until `put()`.
- `search` returns `List<T>` with unique root entities, read straight from the
  stored entries the indexes reference. Prefix indexing is case-insensitive using
  `Locale.ROOT`, **starts-with**, not fuzzy matching or substring matching.
- A single root can contribute multiple values to a nested-list dimension.
  Each trie node holds the entries whose value ends there, so removing one root
  leaves prefixes shared with other roots intact.
- Searches, `peek`, `contains` and `values` do not count as use in the eviction
  order; `get` does. A full specification reindex does not promote entries.
- The default `IdentityStoreBufferedLru` and `IdentityStoreLinkedHashMap` are strict
  LRU. `recency(Recency.APPROXIMATE)` lets the buffered store drop recency updates
  under heavy contention and exceed capacity until its next maintenance. Caffeine has
  a frequency-aware eviction policy, so its victim choice differs from LRU.
- Caffeine adapter is limited to size eviction, with direct-executor maintenance
  run by the calling thread, including lock-free reads, and an explicit `cleanUp()`
  under the type lock when settling. Its eviction callback only
  queues index-cleanup work, and never takes an external lock.

## Locking and atomicity

Every single call is atomic on its own: a `put`, `remove` or `search` never sees, or leaves behind, half an
update of one type. Two settings decide what happens beyond that.

| | `Locking.PER_TYPE` (default) | `Locking.SINGLE` |
|---|---|---|
| Locks | One read-write lock per type | One read-write lock shared by every type |
| Writes to different types | Run in parallel | Wait for each other |
| Searches, `peek`, `values` | Run in parallel | Run in parallel |
| `get` on the buffered or Caffeine store | Lock-free | Lock-free |
| `atomically(...)` | Takes every type's lock, in a fixed order | Takes the one shared lock |
| Choose it when | Types are written independently, which suits most workloads | Writes must happen one at a time across all types, or most writes run in `atomically` blocks |

`atomically(...)` works with both settings. Use it when several calls must look like one step to other
threads:

```java
record Customer(long id, String name, int openOrders) {}
record Order(long id, long customerId) {}

// Each call is atomic, but another thread can run between them and see the order
// removed while the customer still counts it:
cache.remove(Order.class, 42L);
Customer customer = cache.get(Customer.class, 7L);
cache.put(new Customer(customer.id(), customer.name(), customer.openOrders() - 1));

// As one step: other threads see both changes or neither.
cache.atomically(() -> {
    Customer current = cache.get(Customer.class, 7L);
    cache.remove(Order.class, 43L);
    cache.put(new Customer(current.id(), current.name(), current.openOrders() - 1));
});

// Read several types as one consistent view; the block returns a value:
List<Order> open = cache.atomically(() -> {
    Customer current = cache.get(Customer.class, 7L);
    return current.openOrders() == 0 ? List.of() : cache.search(Order::customerId, 7L);
});
```

While a block runs, every other thread that reads or writes the cache waits, including lock-free `get`, in
both settings. Keep blocks short, and do not wait inside a block for another thread that uses the cache: it
cannot proceed until the block ends. A block nested inside another runs as part of the outer one. With
`PER_TYPE` a block takes one lock per type, so blocks cost more with many types; with `SINGLE` they take one
lock, but ordinary writes to different types no longer run in parallel.

## Choosing a configuration

Three configurations cover most uses. They differ in how often entries change, how many threads use the
cache and whether a type is searched. The figures come from the published benchmark run below, on one thread
unless stated, between 1,000 and 50,000 entries per type.

### 1. Read-mostly data that is searched: the default

```java
var cache = ZDCache.builder().build();                     // buffered LRU, strict recency, Locking.PER_TYPE
```

Shines when entries are put seldom and read often, by key or by search: reference data, catalogues, or
configuration loaded at start-up and refreshed now and then.

- `get` takes 18–28 ns with or without indexes, in the same range as Caffeine's 19–27 ns.
- Searches through indexes take 0.05–1 µs at 1,000 entries, up to 8 µs at 10,000 and up to 56 µs at 50,000,
  where the broadest query, an age range, returns about 8,000 entries. Scanning a cache without indexes is
  6–1,900× slower, even at 1,000 entries.
- Eviction follows exact LRU order.
- The cost is in `put`: with four indexes it takes 0.5 µs at 1,000 entries and 1.5 µs at 50,000, which hardly
  matters when puts are rare.

### 2. Searched data that is updated often: one entry per id

```java
var cache = ZDCache.builder().entries(Entries.PER_ID).build();
```

Shines when indexed entries are replaced often, above all when an update leaves most indexed values as they
were, such as a status or a balance changing while the name and city stay the same.

- An update that keeps the indexed values takes 235–454 ns instead of 475–1,021 ns, 2.0–2.3× faster.
- An update that changes every indexed value is still 1.3–2.0× faster, most at small sizes: 247 against 498 ns
  at 1,000 entries, 1.2 against 1.5 µs at 50,000.
- `get`, searches and memory per entry are the same as with the default. Types without indexes are not
  affected.

### 3. Key lookups from many threads: approximate recency

```java
var cache = ZDCache.builder().recency(Recency.APPROXIMATE).build();
```

Shines when many threads get and put types without indexes, such as session or token caches, or lookups in
front of a slower service.

- With four threads, 1.4–1.5× the reads of strict mode, and under mixed load 2.4–2.6× the reads and 2.6–4.8×
  the writes; reads at 50–65% of Caffeine's rate and writes at about 80%.
- Writes to types without indexes run without the type's lock. Writes to types with indexes take the lock in
  either mode, so they gain little.
- Eviction order is approximate under contention. The figures come from four threads on a machine with five
  performance cores; with more threads than fast cores, approximate recency has fallen behind strict mode, so
  measure with your own thread count before switching.

Approximate recency only changes types without indexes and one entry per id only changes types with indexes,
so a cache holding both kinds can use both settings. That combination is not benchmarked.

### Size

Up to about 10,000 entries per type every configuration is quick: a `put` with four indexes stays under
0.85 µs (0.47 µs with one entry per id), every search finishes within 8 µs, and a `put` without indexes is
1.3–1.6× faster than Caffeine's (29–43 against 46–65 ns). At 50,000 entries a `put` without indexes is level
with Caffeine's, a `put` with four indexes takes up to 1.5 µs and the broadest searches up to 56 µs.

### Other settings

| Situation | Setting | Why |
|---|---|---|
| Different threads writing different types | `locking(Locking.PER_TYPE)`, the default | Writes to separate types run about six times faster than with `SINGLE` |
| Most writes in `atomically` blocks, or writes must happen one at a time | `locking(Locking.SINGLE)` | A block takes one lock instead of one per type; blocks run at the same rate under either setting, and on one thread `SINGLE` costs about the same |
| One thread, tight on memory | `identityStore(IdentityStoreFactory.linkedHashMap())` | 99 instead of 123 bytes per entry and faster single-threaded writes (51 against 70 ns at 50,000 entries), but concurrent reads run three to four times slower |
| Caffeine as the identity store | Not recommended | Not LRU, slower single-threaded `get` (39–49 ns against 19–26 ns) and the most memory per entry of the zdcache stores, though under concurrent mixed load it reads and writes faster than the default store |

### Indexes

Indexes matter more for performance than the store. Each one is updated on every `put`: four indexes take a
`put` from 30–85 ns to 0.5–1.5 µs, depending on size, and memory from 123 to 434 bytes per entry, so declare
only the dimensions you search on. A `put` inside its own `atomically` block costs 17–83 ns more, which only
shows without indexes. A compound index pays off for combinations you search often: at 50,000 entries an exact
city with an age range takes 1.6 µs as a compound, against 56 µs for the age range on its own, because the
city narrows the matches first. Method references keep dimension names right when properties are renamed.

```java
record Person(long id, String name, String city, int age) {}

var cache = ZDCache.builder()
    .defaultMaxPerType(100_000)                                            // room for the working set
    .build();                                                              // buffered LRU, strict, PER_TYPE

var spec = SearchSpecification.builder(IdentityDimension.of(Person.class, "id", Person::id))
    .prefix(Person::name)                                                  // only what you search on
    .compound("cityAge", c -> c.exact(Person::city).range(Person::age))    // for a frequent combination
    .build();
```

Switch to `recency(Recency.APPROXIMATE)` when profiling shows many threads waiting on the cache.

## Benchmark

There are two sets of benchmarks. The configuration benchmarks run zdcache alone, in every identity store with and without indexes, with `Locking.SINGLE` and with one entry per id, measuring `get`, `put`, `put` inside `atomically` and searches. The library benchmarks compare zdcache's default configuration and approximate recency, each with and without indexes, against Caffeine, cache2k, Guava, Ehcache, a plain `ConcurrentHashMap`, embedded Hazelcast and CQEngine. Both measure single-threaded `get` and `put` and prefix, exact, compound and range searches at 1,000, 3,000, 5,000, 10,000 and 50,000 entries. Four threads reading and writing concurrently, and locking across four types, are measured at 10,000 and 50,000 entries. JOL measures memory per entry. Caches without indexes answer searches by scanning every entry.

**[Open the full interactive report](https://zdcache.github.io/zdcache/benchmark/)** for every library and every zdcache configuration at every size, with confidence intervals and a table view.

> **Disclaimer:** these numbers were collected by running the benchmark on the author's development machine. Results vary with hardware, JVM, load and data shape, so treat them as an indication of gains and losses, not as guarantees. Run the benchmark on your own machine and data before deciding.

<!-- benchmark-summary:start -->
Measured 2026-10-14T20:09:37Z on Java 21.0.6 with 11 CPUs, 3 forks per benchmark, at 50,000 entries. Lower is better for time and memory; higher is better for operations per second.

**Single thread and memory**

| | get | put | Memory per entry |
|---|---:|---:|---:|
| zdcache | 25 ns | 85 ns | 123 B |
| zdcache, 4 indexes | 28 ns | 1,505 ns | 434 B |
| zdcache, approximate recency | 22 ns | 81 ns | 123 B |
| zdcache, approximate, 4 indexes | 26 ns | 1,553 ns | 434 B |
| Caffeine | 27 ns | 87 ns | 109 B |
| cache2k | 10 ns | 31 ns | 107 B |
| Guava | 37 ns | 76 ns | 99 B |
| Ehcache | 34 ns | 149 ns | 131 B |
| ConcurrentHashMap, no eviction | 6.3 ns | 21 ns | 67 B |
| Hazelcast, 3 indexes | 8,054 ns | 18,119 ns | – |
| CQEngine, 4 indexes | 271 ns | 3,808 ns | 775 B |

**zdcache configurations, single thread**

| | get | put | put in `atomically` | Prefix search | Memory per entry |
|---|---:|---:|---:|---:|---:|
| Buffered LRU (default) | 26 ns | 70 ns | 153 ns | – | 123 B |
| Buffered LRU, 4 indexes | 27 ns | 1,509 ns | 1,611 ns | 0.68 µs | 434 B |
| Approximate recency | 25 ns | 79 ns | 157 ns | – | 123 B |
| Approximate recency, 4 indexes | 27 ns | 1,566 ns | 1,631 ns | 0.68 µs | 434 B |
| LinkedHashMap store | 24 ns | 51 ns | 133 ns | – | 99 B |
| LinkedHashMap store, 4 indexes | 29 ns | 1,433 ns | 1,550 ns | 0.67 µs | 410 B |
| Caffeine store | 49 ns | 125 ns | 179 ns | – | 133 B |
| Caffeine store, 4 indexes | 52 ns | 1,631 ns | 1,746 ns | 0.68 µs | 445 B |
| `Locking.SINGLE`, 4 indexes | 26 ns | 1,467 ns | 1,627 ns | 0.67 µs | 434 B |
| `Entries.PER_ID`, 4 indexes | 28 ns | 1,183 ns | 1,220 ns | 0.67 µs | 434 B |

**Concurrent, 4 threads (million operations per second)**

| | Reads only | Mixed: reads | Mixed: writes |
|---|---:|---:|---:|
| zdcache | 51 | 16 | 1.4 |
| zdcache, 4 indexes | 44 | 34 | 0.44 |
| zdcache, approximate recency | 69 | 42 | 6.9 |
| zdcache, approximate, 4 indexes | 79 | 20 | 0.33 |
| Caffeine | 141 | 87 | 8.6 |
| cache2k | 270 | 103 | 17 |
| Guava | 19 | 17 | 0.36 |
| Ehcache | 102 | 39 | 7.2 |
| ConcurrentHashMap, no eviction | 596 | 389 | 31 |
| Hazelcast, 3 indexes | 0.28 | 0.21 | 0.04 |
| CQEngine, 4 indexes | 13 | 7.5 | 0.20 |

**Locking across four types (million operations per second)**

| | Writes to separate types | `atomically` blocks | Same calls without a block | Mixed: reads | Mixed: writes |
|---|---:|---:|---:|---:|---:|
| zdcache, `Locking.PER_TYPE` | 4.5 | 0.39 | 0.83 | 11 | 2.3 |
| zdcache, `Locking.SINGLE` | 0.80 | 0.38 | 0.35 | 13 | 0.69 |

**Search**

| | Prefix | Exact | Compound | Range |
|---|---:|---:|---:|---:|
| zdcache, 4 indexes | 0.68 µs | 5.4 µs | 1.6 µs | 56 µs |
| zdcache, approximate, 4 indexes | 0.67 µs | 5.8 µs | 1.4 µs | 46 µs |
| Hazelcast, 3 indexes | 31 µs | 335 µs | 903 µs | 3,075 µs |
| CQEngine, 4 indexes | 9.8 µs | 6.5 µs | 16 µs | 80 µs |
| Caffeine (full scan) | 461 µs | 574 µs | 571 µs | 611 µs |
| cache2k (full scan) | 1,148 µs | 1,218 µs | 1,262 µs | 1,362 µs |
| Guava (full scan) | 759 µs | 957 µs | 945 µs | 961 µs |
| Ehcache (full scan) | 1,290 µs | 1,353 µs | 1,326 µs | 1,362 µs |
| ConcurrentHashMap (full scan) | 392 µs | 508 µs | 504 µs | 542 µs |

<!-- benchmark-summary:end -->

How to read it: on one thread, zdcache's `get` is in the same range as Caffeine's at every size (18–25 ns against 19–27 ns), with cache2k and a plain `ConcurrentHashMap` faster. zdcache's `put` is 1.6× faster than Caffeine's at 1,000 entries and level with it at 50,000; choose *Across sizes* in the report to see how each method grows with the number of entries. Keeping four indexes up to date costs 0.5–1.5 µs per `put`. Searches through indexes are 1.2–15× faster than CQEngine, closest for exact and range queries, 47–580× faster than Hazelcast's indexes at 50,000 entries, and 6–1,700× faster than scanning a cache. Embedded Hazelcast copies every value it returns and takes about 8 µs per `get`; it is built for distributed data, not as a local cache. Under concurrent load the default strict LRU store reads at 35–45% of Caffeine's rate and writes at 17–32% of it. With `recency(Recency.APPROXIMATE)` key-only types read at 50–65% of Caffeine's rate and write at about 80% of it. Across four types, `Locking.PER_TYPE` writes to separate types about six times faster than `Locking.SINGLE`; `atomically` blocks run at the same rate under both, and with `PER_TYPE` they reach about half the throughput of the same calls made separately. Choose zdcache when you look cached objects up by more than their key; for key-only caching with many concurrent writes, Caffeine or cache2k fits better.

None of the benchmarks put the caches under eviction pressure. Caffeine and cache2k do not evict in LRU order, Guava evicts in LRU order per segment, Ehcache by sampling and zdcache in strict LRU order by default. ConcurrentHashMap, Hazelcast as configured here and CQEngine never evict.

### What is measured

| Benchmark | Measures | Threads | Entries | Workload |
|---|---|---:|---|---|
| `ConfigurationBenchmark.get`, `LibraryBenchmark.get` | Average time per `get` | 1 | 1,000 to 50,000 | Random keys from a fixed sequence of 65,536; every key is present |
| `ConfigurationBenchmark.put`, `LibraryBenchmark.put` | Average time per `put` | 1 | 1,000 to 50,000 | Replaces existing entries, alternating between two versions of each person |
| `ConfigurationBenchmark.putKeepingIndexedValues` | Average time per `put` | 1 | 1,000 to 50,000 | Replaces existing entries with a version that changes only the tags, which are not indexed |
| `ConfigurationBenchmark.putAtomically` | Average time per `put` inside `atomically` | 1 | 1,000 to 50,000 | As `put`, each inside its own block |
| `ConfigurationSearchBenchmark.prefixName`, `LibrarySearchBenchmark.prefixName` | Average time per search | 1 | 1,000 to 50,000 | The first two letters of a random person's name |
| `ConfigurationSearchBenchmark.exactCity`, `LibrarySearchBenchmark.exactCity` | Average time per search | 1 | 1,000 to 50,000 | One of 50 cities, about 2% of the entries |
| `ConfigurationSearchBenchmark.rangeAge`, `LibrarySearchBenchmark.rangeAge` | Average time per search | 1 | 1,000 to 50,000 | A 10-year age window, about 16% of the entries |
| `ConfigurationSearchBenchmark.compoundCityAge`, `LibrarySearchBenchmark.compoundCityAge` | Average time per search | 1 | 1,000 to 50,000 | A city and a 10-year age window |
| `ConcurrentBenchmark.readOnly` | Operations per second | 4 | 10,000 and 50,000 | Every thread reads random keys |
| `ConcurrentBenchmark.mixed` | Operations per second, reads and writes separately | 3 + 1 | 10,000 and 50,000 | Three threads read while one replaces existing entries |
| `LockingBenchmark.writesToSeparateTypes` | Operations per second | 4 | 10,000 and 50,000 per type | Four types; each thread replaces entries of its own type |
| `LockingBenchmark.atomicBlocksAcrossTypes` | `atomically` blocks per second | 4 | 10,000 and 50,000 per type | Each block reads one type and replaces an entry in it and in the next type |
| `LockingBenchmark.separateCallsAcrossTypes` | Calls per second, counted per group of three | 4 | 10,000 and 50,000 per type | The same read and two writes, without a block |
| `LockingBenchmark.mixed` | Operations per second, reads and writes separately | 2 + 2 | 10,000 and 50,000 per type | Two threads read random types while two replace entries of their own type |
| `MemoryFootprint` | Heap bytes per entry, measured with JOL | – | 1,000 to 50,000 | The cache filled with every person, not counting the person objects themselves |

Every entry is a `Person(id, name, city, age, tags)` with a six-letter random name, one of 50 cities, an age
from 18 to 80 and up to three tags, generated from a fixed seed. `LockingBenchmark` instead uses four record
types, `Customer`, `Vendor`, `Warehouse` and `Carrier`, each with a name, a city and a balance and indexed on
name (prefix) and city (exact), so every write takes its type's lock. Each cache can hold every entry, so
nothing is evicted.

| Subject in the report | Library | Identity store | Recency | Indexes | Benchmarks |
|---|---|---|---|---|---|
| zdcache, Buffered LRU (default) | zdcache | Buffered LRU (default) | Strict | None | Configuration, library, concurrent, memory |
| zdcache, 4 indexes | zdcache | Buffered LRU (default) | Strict | 4 | All except locking |
| zdcache, approximate recency | zdcache | Buffered LRU | Approximate | None | Configuration, library, concurrent, memory |
| zdcache, approximate, 4 indexes | zdcache | Buffered LRU | Approximate | 4 | All except locking |
| LinkedHashMap store | zdcache | LinkedHashMap | Strict | None | Configuration, concurrent, memory |
| LinkedHashMap store, 4 indexes | zdcache | LinkedHashMap | Strict | 4 | Configuration, memory |
| Caffeine store | zdcache | Caffeine | Frequency-aware | None | Configuration, concurrent, memory |
| Caffeine store, 4 indexes | zdcache | Caffeine | Frequency-aware | 4 | Configuration, memory |
| `Locking.SINGLE`, 4 indexes | zdcache | Buffered LRU (default) | Strict | 4 | Configuration, memory |
| `Entries.PER_ID`, 4 indexes | zdcache | Buffered LRU (default) | Strict | 4 | Configuration, memory |
| Caffeine | Caffeine 3.2.4 | – | Frequency-aware | None; searches scan | Library, concurrent, memory |
| cache2k | cache2k 2.6.1 | – | Clock-Pro | None; searches scan | Library, concurrent, memory |
| Guava | Guava 33.7.2 | – | LRU per segment | None; searches scan | Library, concurrent, memory |
| Ehcache | Ehcache 3.12.0, heap tier | – | Sampled eviction | None; searches scan | Library, concurrent, memory |
| ConcurrentHashMap | JDK | – | Never evicts | None; searches scan | Library, concurrent, memory |
| Hazelcast, 3 indexes | Hazelcast 5.7.0, one embedded member | – | Never evicts | 3 | Library, concurrent |
| CQEngine, 4 indexes | CQEngine 3.6.0 | – | Never evicts | 4 | Library, concurrent, memory |
| zdcache, PER_TYPE locking | zdcache | Buffered LRU (default) | Strict | 2 per type | Locking |
| zdcache, SINGLE locking | zdcache | Buffered LRU (default) | Strict | 2 per type | Locking |

The four zdcache indexes are name (prefix), city (exact), age (range) and city with age (compound), declared
by property name. CQEngine uses radix-tree, hash and navigable indexes, answers the compound query with two of
them, and has its writes serialized. Hazelcast stores values as objects, indexes name and age as sorted and
city as hashed, answers the compound query with two of them, and copies every value it returns; it runs
without eviction because its per-partition limits would evict early, and its memory is not measured. Guava gets 5% extra
capacity so its per-segment limits do not evict early. Caffeine runs its maintenance on its default executor; the Caffeine store inside zdcache runs it
on the calling thread.

Only `LockingBenchmark` uses several types, so it is the only one where `Locking.PER_TYPE` and
`Locking.SINGLE` differ under contention. `ConfigurationBenchmark` measures the single-threaded cost of
`Locking.SINGLE` and of `atomically`. Every other zdcache subject uses the default `Locking.PER_TYPE`.

| Plan | Forks | Warm-up | Measurement | Time |
|---|---|---|---|---|
| `benchmark-full.properties` | 3 per benchmark | 1 × 1 s for key lookup, 1 × 500 ms for concurrent and locking, 1 × 600 ms for search | 2 × 300 ms, 2 × 500 ms for concurrent and locking | About 60 minutes |
| `benchmark-quick.properties` | 1, 2 for concurrent and locking | As the full plan | As the full plan | About 25 minutes |

### Running the benchmark

```bash
./benchmark -full                  # settings in src/jmh/resources/benchmark-full.properties (about 60 minutes)
./benchmark -quick                 # settings in src/jmh/resources/benchmark-quick.properties (about 25 minutes)
./benchmark -jmh "LibrarySearchBenchmark -f 1 -p subject=zdcache-buffered-indexed,cqengine"   # custom JMH options
./benchmark -publish               # publish the last full run
```

Each plan file sets forks, warm-up and measurement per benchmark (`*.` for all, a benchmark name to override), can narrow a benchmark with `sizes` and `subjects`, and sets the memory-footprint sizes with `MemoryFootprint.sizes`. Each published value is the median of the per-fork averages, so one unlucky JVM does not decide a result. Results print to the terminal and are written to `build/reports/zdcache-benchmark/index.html`. The normal `./gradlew build` does not run the benchmark.

Only a full run can be published. `./benchmark -publish` copies the report to `docs/benchmark/index.html`, which GitHub Pages serves, and refreshes the summary tables above; after a quick or custom run it refuses and asks for a full run.

## Current limitations and follow-up work

1. **Not a transactional database.** Writes are serialized per type, except
   key-only writes on stores with concurrent writes, but external mutation of
   object references is not synchronized by this lock. Index keys from nested
   collections and properties should be stable immutable values (strings, boxed
   numbers, dates, UUIDs, enums, etc.).
2. The implementation stages extraction/validation before ordinary `put`, but
   does not yet provide general rollback for unexpected exceptions (e.g. custom
   `hashCode` or `Comparable` throwing *during* an index mutation, or OOM).
3. Searches share a per-type read lock and `get` takes no type lock, but writes take
   that lock exclusively, so concurrent writes to one type are serialized. Key-only
   types on stores with concurrent writes (approximate recency, Caffeine) are the
   exception. Cross-type atomicity needs an `atomically(...)` block, which blocks
   every other writer and reader for its duration.
4. `SearchSpecification` can traverse nested lists of properties. Intersections,
   fuzzy text, ranked similarity, geospatial indexing, sorting, pagination,
   expiration, metrics, and persistence are not yet implemented.
5. Empty-string prefix searches match all indexed names; prefix searches are
   **not** typo-tolerant. Separate collection dimensions do not preserve
   per-element join context; declare a `compound` inside `each` for "both
   conditions on the same nested child" queries.
6. Cache size limits count root entities **per type**; index memory is not
   independently bounded. Many long strings/large nested lists can consume
   much more memory than the identity store itself.
7. Cache values can be records or mutable POJOs, but callers own their mutation
   discipline. A `get()` or `search()` can return a reference whose properties
   have changed since it was indexed.
8. Caffeine is an optional dependency: add `com.github.ben-manes.caffeine:caffeine`
   yourself to use `IdentityStoreFactory.caffeine()`.

## Project map

```
src/main/java/io/github/zdcache/
  ZDCache.java             # multi-type facade and builder
  SearchSpecification.java         # immutable spec and nested-list DSL
  IdentityDimension.java           # immutable per-type identity strategy
  SearchDimension.java             # indexed leaf and value extraction
  internal/TypeCache.java          # synchronization and update lifecycle
  internal/DimensionRegistry.java  # per-type indexes and snapshots
  index/                           # prefix Trie, exact HashMap, range TreeMap
  store/IdentityStore.java         # storage SPI
  store/IdentityStoreBufferedLru.java  # default store
  store/IdentityStoreLinkedHashMap.java
  store/IdentityStoreCaffeine.java
src/test/java/io/github/zdcache/ZDCacheTest.java
```

JMH benchmarks and memory measurements live in `src/jmh`; see [Benchmark](#benchmark)
for how to run and publish them. They do not yet cover eviction pressure.
