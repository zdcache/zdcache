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

## Benchmark

zdcache is compared with three key-value caches (Caffeine, cache2k and Guava) and with CQEngine, an indexed in-memory collection. JMH measures single-threaded `get` and `put`, eight threads reading and writing concurrently, and prefix, exact, compound and range searches at 10,000 and 100,000 entries. JOL measures memory per entry. Caches without indexes answer searches by scanning every entry.

**[Open the full interactive report](https://zdcache.github.io/zdcache/benchmark/)** for every library and every zdcache identity store, with confidence intervals and a table view.

> **Disclaimer:** these numbers were collected by running the benchmark on the author's development machine. Results vary with hardware, JVM, load and data shape, so treat them as an indication of gains and losses, not as guarantees. Run the benchmark on your own machine and data before deciding.

<!-- benchmark-summary:start -->
Measured 2026-10-14T11:09:47Z on Java 21.0.6 with 11 CPUs, 3 forks per benchmark, at 100,000 entries. Lower is better for time and memory; higher is better for operations per second.

**Single thread and memory**

| | get | put | Memory per entry |
|---|---:|---:|---:|
| zdcache | 25 ns | 101 ns | 123 B |
| zdcache, 4 indexes | 40 ns | 2,508 ns | 424 B |
| zdcache, approximate recency | 27 ns | 133 ns | – |
| zdcache, approximate, 4 indexes | 48 ns | 2,448 ns | – |
| Caffeine | 29 ns | 121 ns | 109 B |
| cache2k | 12 ns | 37 ns | 107 B |
| Guava | 38 ns | 105 ns | 99 B |
| CQEngine, 4 indexes | 396 ns | 4,255 ns | 769 B |

**Concurrent, 8 threads (million operations per second)**

| | Reads only | Mixed: reads | Mixed: writes |
|---|---:|---:|---:|
| zdcache | 42 | 12 | 0.54 |
| zdcache, 4 indexes | 42 | 22 | 0.14 |
| zdcache, approximate recency | 63 | 27 | 2.7 |
| zdcache, approximate, 4 indexes | 41 | 33 | 0.12 |
| Caffeine | 90 | 55 | 7.5 |
| cache2k | 179 | 98 | 7.5 |
| Guava | 15 | 17 | 0.01 |
| CQEngine, 4 indexes | 15 | 12 | 0.10 |

**Locking across four types (million operations per second)**

| | Writes to separate types | `atomically` blocks | Same calls without a block | Mixed: reads | Mixed: writes |
|---|---:|---:|---:|---:|---:|
| zdcache, `Locking.PER_TYPE` | 2.4 | 0.25 | 0.43 | 14 | 0.79 |
| zdcache, `Locking.SINGLE` | 0.41 | 0.24 | 0.22 | 17 | 0.23 |

**Search**

| | Prefix | Exact | Compound | Range |
|---|---:|---:|---:|---:|
| zdcache, 4 indexes | 1.3 µs | 13 µs | 2.3 µs | 156 µs |
| CQEngine, 4 indexes | 20 µs | 20 µs | 35 µs | 223 µs |
| Caffeine (full scan) | 920 µs | 1,138 µs | 1,146 µs | 1,332 µs |
| cache2k (full scan) | 2,371 µs | 2,843 µs | 2,578 µs | 2,774 µs |
| Guava (full scan) | 2,509 µs | 3,054 µs | 2,908 µs | 2,370 µs |

<!-- benchmark-summary:end -->

How to read it: on one thread, zdcache's key lookup is in the same range as Caffeine's (25–27 ns for `get` against 29 ns, 101–133 ns for `put` against 121 ns), with cache2k the fastest, and keeping four indexes up to date costs about 2.5 µs per `put`. Searches through indexes are about 15× faster than CQEngine for prefix and compound queries, 1.4–1.5× for exact and range queries, and 8–2,000× faster than scanning a cache. Under concurrent load the default strict LRU store reads at about 45% of Caffeine's rate and writes at under a tenth of it. With `recency(Recency.APPROXIMATE)` zdcache reads at 65–90% of Caffeine's rate between runs and writes at about 40% of it, giving up exact LRU order under heavy contention. Across four types, `Locking.PER_TYPE` writes to separate types about six times faster than `Locking.SINGLE`; `atomically` blocks run at the same rate under both, and with `PER_TYPE` they cost about 40% of the throughput of the same calls made separately. Choose zdcache when you look cached objects up by more than their key; for key-only caching with many concurrent writes, Caffeine or cache2k fits better.

None of the benchmarks put the caches under eviction pressure. Caffeine and cache2k do not evict in LRU order, Guava evicts in LRU order per segment and zdcache in strict LRU order by default. CQEngine never evicts.

### What is measured

| Benchmark | Measures | Threads | Entries | Workload |
|---|---|---:|---|---|
| `KeyLookupBenchmark.get` | Average time per `get` | 1 | 10,000 and 100,000 | Random keys from a fixed sequence of 65,536; every key is present |
| `KeyLookupBenchmark.put` | Average time per `put` | 1 | 10,000 and 100,000 | Replaces existing entries, alternating between two versions of each person |
| `SearchBenchmark.prefixName` | Average time per search | 1 | 10,000 and 100,000 | The first two letters of a random person's name |
| `SearchBenchmark.exactCity` | Average time per search | 1 | 10,000 and 100,000 | One of 50 cities, about 2% of the entries |
| `SearchBenchmark.rangeAge` | Average time per search | 1 | 10,000 and 100,000 | A 10-year age window, about 16% of the entries |
| `SearchBenchmark.compoundCityAge` | Average time per search | 1 | 10,000 and 100,000 | A city and a 10-year age window |
| `ConcurrentBenchmark.readOnly` | Operations per second | 8 | 100,000 | Every thread reads random keys |
| `ConcurrentBenchmark.mixed` | Operations per second, reads and writes separately | 6 + 2 | 100,000 | Six threads read while two replace existing entries |
| `LockingBenchmark.writesToSeparateTypes` | Operations per second | 4 | 100,000 per type | Four types; each thread replaces entries of its own type |
| `LockingBenchmark.atomicBlocksAcrossTypes` | `atomically` blocks per second | 4 | 100,000 per type | Each block reads one type and replaces an entry in it and in the next type |
| `LockingBenchmark.separateCallsAcrossTypes` | Calls per second, counted per group of three | 4 | 100,000 per type | The same read and two writes, without a block |
| `LockingBenchmark.mixed` | Operations per second, reads and writes separately | 6 + 2 | 100,000 per type | Six threads read random types while two replace entries of their own type |
| `MemoryFootprint` | Heap bytes per entry, measured with JOL | – | 10,000 and 100,000 | The cache filled with every person, not counting the person objects themselves |

Every entry is a `Person(id, name, city, age, tags)` with a six-letter random name, one of 50 cities, an age
from 18 to 80 and up to three tags, generated from a fixed seed. `LockingBenchmark` instead uses four record
types, `Customer`, `Vendor`, `Warehouse` and `Carrier`, each with a name, a city and a balance and indexed on
name (prefix) and city (exact), so every write takes its type's lock. Each cache can hold every entry, so
nothing is evicted.

| Subject in the report | Library | Identity store | Recency | Indexes | Benchmarks |
|---|---|---|---|---|---|
| zdcache | zdcache | Buffered LRU (default) | Strict | None | Key lookup, concurrent, memory |
| zdcache, 4 indexes | zdcache | Buffered LRU (default) | Strict | 4 | All |
| zdcache, approximate recency | zdcache | Buffered LRU | Approximate | None | Key lookup, concurrent |
| zdcache, approximate, 4 indexes | zdcache | Buffered LRU | Approximate | 4 | Key lookup, concurrent |
| LinkedHashMap | zdcache | LinkedHashMap | Strict | None | Key lookup, concurrent, memory |
| LinkedHashMap, 4 indexes | zdcache | LinkedHashMap | Strict | 4 | All |
| LinkedHashMap, 4 indexes, lambdas | zdcache | LinkedHashMap | Strict | 4, declared with lambdas | Key lookup |
| Caffeine store | zdcache | Caffeine | Frequency-aware | None | Key lookup, concurrent, memory |
| Caffeine store, 4 indexes | zdcache | Caffeine | Frequency-aware | 4 | All |
| Caffeine | Caffeine 3.2.4 | – | Frequency-aware | None; searches scan | All |
| cache2k | cache2k 2.6.1 | – | Clock-Pro | None; searches scan | All |
| Guava | Guava 33.7.2 | – | LRU per segment | None; searches scan | All |
| CQEngine, 4 indexes | CQEngine 3.6.0 | – | Never evicts | 4 | All |
| zdcache, PER_TYPE locking | zdcache | Buffered LRU (default) | Strict | 2 per type | Locking |
| zdcache, SINGLE locking | zdcache | Buffered LRU (default) | Strict | 2 per type | Locking |

The four zdcache indexes are name (prefix), city (exact), age (range) and city with age (compound). The
other zdcache subjects declare them by property name. CQEngine uses radix-tree, hash and navigable
indexes, answers the compound query with two of them, and has its writes serialized. Guava gets 5% extra
capacity so its per-segment limits do not evict early. Caffeine runs its maintenance on its default
executor; the Caffeine store inside zdcache runs it on the calling thread.

Only `LockingBenchmark` uses several types, so it is the only one where `Locking.PER_TYPE` and
`Locking.SINGLE` behave differently, and the only one that measures `atomically`. Every other zdcache subject
uses the default `Locking.PER_TYPE`.

| Plan | Forks | Warm-up | Measurement | Time |
|---|---|---|---|---|
| `benchmark-full.properties` | 3 per benchmark | 1 × 1 s for key lookup, 1 × 500 ms for concurrent and locking, 1 × 600 ms for search | 2 × 300 ms, 2 × 500 ms for concurrent and locking | About 13 minutes |
| `benchmark-quick.properties` | 1, 2 for concurrent and locking | As the full plan | As the full plan | About 6 minutes |

### Running the benchmark

```bash
./benchmark -full                  # settings in src/jmh/resources/benchmark-full.properties (about 13 minutes)
./benchmark -quick                 # settings in src/jmh/resources/benchmark-quick.properties (about 6 minutes)
./benchmark -jmh "SearchBenchmark -f 1 -p subject=zdcache-buffered-indexed,cqengine"   # custom JMH options
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
