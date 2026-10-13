# ZDCache — Searchable Dimensions Cache (Java 21, Gradle)

A **baseline implementation** of an in-process, multi-type, per-type-bounded object
cache with specification-driven searchable dimensions and secondary indexes. Cache writes are explicitly
submitted; the cache does **not** track mutations to objects returned by reference.

This is a starting point for development and benchmarking, not a production-ready
library. See the limitations below.

## Requirements and build

- JDK 21+
- Gradle 9.7.0 via `./gradlew` (Windows: `gradlew.bat`) or a locally installed Gradle
- Internet access on first build for Gradle and Maven Central dependencies

```sh
./gradlew test
./gradlew jmh             # JMH benchmarks against Caffeine
./gradlew footprint       # heap bytes per cached entry
./gradlew build
```

**Wrapper note:** This generated ZIP does not contain the wrapper binary. The
included `gradlew`/`gradlew.bat` scripts download the **official Gradle 9.7.0** wrapper
JAR on first run and verify its SHA-256 before executing the Gradle wrapper.
Alternatively, run `gradle test` with a locally installed Gradle.

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
    .identityStore(IdentityStoreFactory.linkedHashMap())
    .build();

// IMPORTANT: reuse this exact identity instance in replacement specifications.
var personId = IdentityDimension.of(Person.class, "id", Person::id);
var spec = SearchSpecification.builder(personId)
    .prefix("name", Person::name)
    .range("age", Person::age)
    .each("addresses", Person::addresses, address -> address
        .prefix("city", Address::city)
        .exactEach("tags", Address::tags))
    .build();

cache.put(new Person(1, "Simon", 32,
    List.of(new Address("Oslo", List.of("home", "office")))), spec);
cache.put(new Person(2, "Simone", 25, List.of()));

List<Person> people = cache.search(Person.class, "name", "si");
List<Person> inOslo = cache.search(Person.class, "addresses.city", "os");
List<Person> aged = cache.search(Person.class, "age", ValueRange.between(20, 40));
Person one = cache.get(Person.class, 1L);

// Full per-type reindex; unchanged identity object is required.
var nextSpec = SearchSpecification.builder(personId)
    .prefix("name", Person::name)
    .build();
cache.updateSpecification(Person.class, nextSpec);

// Switch identity-store implementations with no changes to the search code:
var usingCaffeine = ZDCache.builder()
    .identityStore(IdentityStoreFactory.caffeine())
    .build();
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
    .prefix("name", Person::name).build();
cache.updateSpecification(Person.class, upgraded);
cache.put(Person.class, 43L, new Person(43, "Simon", 28, List.of()));
```

An explicit-identity type continues to require the explicit-ID overload on writes.
A registered extracting identity supports `put(entity)` directly. Asking to search
an unregistered type or a basic-only type raises
`MissingSearchSpecificationStrategyException`. Asking for a missing dimension on a
searchable type raises `UnknownSearchDimensionException`.

## Identity, specification and update rules

- Each class has its own capacity, `ReentrantLock`, `IdentityStore`, specification,
  and indexes. Operations on different types are independent.
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
- `search` returns `List<T>` with unique root entities and internally resolves
  indexed IDs via the identity store. Prefix indexing is case-insensitive using
  `Locale.ROOT`, **starts-with**, not fuzzy matching or substring matching.
- A single root can contribute multiple values to a nested-list dimension.
  Per-node ID reference counts protect shared prefixes during removal.
- Search accesses promote matching identity entries according to the store's
  eviction policy; full specification reindex does not promote entries.
- `IdentityStoreLinkedHashMap` is strict LRU. Caffeine has a frequency-aware
  eviction policy: the two backends are not semantically identical in victim choice.
- Caffeine adapter is limited to size eviction, direct-executor maintenance and
  explicit `cleanUp()` while the type lock is held. Its eviction callback only
  queues index-cleanup work, and never takes an external lock.

## Benchmark

zdcache is compared with three key-value caches (Caffeine, cache2k and Guava) and with CQEngine, an indexed in-memory collection. JMH measures single-threaded `get` and `put`, eight threads reading and writing concurrently, and prefix, exact, compound and range searches at 10,000 and 100,000 entries. JOL measures memory per entry. Caches without indexes answer searches by scanning every entry.

**[Open the full interactive report](https://zdcache.github.io/zdcache/benchmark/)** for every library and every zdcache identity store, with confidence intervals and a table view.

> **Disclaimer:** these numbers were collected by running the benchmark on the author's development machine. Results vary with hardware, JVM, load and data shape, so treat them as an indication of gains and losses, not as guarantees. Run the benchmark on your own machine and data before deciding.

<!-- benchmark-summary:start -->
Measured 2026-10-13T13:28:26Z on Java 21.0.6 with 11 CPUs, 3 forks per benchmark, at 100,000 entries. Lower is better for time and memory; higher is better for operations per second.

**Single thread and memory**

| | get | put | Memory per entry |
|---|---:|---:|---:|
| zdcache | 28 ns | 84 ns | 123 B |
| zdcache, 4 indexes | 42 ns | 2,638 ns | 424 B |
| Caffeine | 40 ns | 125 ns | 109 B |
| cache2k | 13 ns | 40 ns | 107 B |
| Guava | 43 ns | 119 ns | 99 B |
| CQEngine, 4 indexes | 380 ns | 4,458 ns | 769 B |

**Concurrent, 8 threads (million operations per second)**

| | Reads only | Mixed: reads | Mixed: writes |
|---|---:|---:|---:|
| zdcache | 33 | 11 | 0.60 |
| zdcache, 4 indexes | 33 | 18 | 0.13 |
| Caffeine | 84 | 47 | 6.5 |
| cache2k | 179 | 79 | 6.6 |
| Guava | 13 | 15 | 0.01 |
| CQEngine, 4 indexes | 14 | 12 | 0.09 |

**Search**

| | Prefix | Exact | Compound | Range |
|---|---:|---:|---:|---:|
| zdcache, 4 indexes | 1.3 µs | 15 µs | 2.3 µs | 183 µs |
| CQEngine, 4 indexes | 20 µs | 22 µs | 35 µs | 242 µs |
| Caffeine (full scan) | 913 µs | 1,266 µs | 1,146 µs | 1,467 µs |
| cache2k (full scan) | 1,966 µs | 2,414 µs | 2,642 µs | 3,222 µs |
| Guava (full scan) | 2,537 µs | 3,101 µs | 2,895 µs | 2,733 µs |
<!-- benchmark-summary:end -->

How to read it: on one thread, zdcache's key lookup is in the same range as Caffeine and Guava, with cache2k the fastest, and keeping four indexes up to date costs about 2.6 µs per `put`. Searches through indexes are about 15× faster than CQEngine for prefix and compound queries, 1.3–1.5× for exact and range queries, and 8–2,000× faster than scanning a cache. Under concurrent load zdcache reads at about 40% of Caffeine's rate and a fifth of cache2k's while keeping strict LRU order; writes are where it lags most, at about a tenth of Caffeine's write throughput without indexes. Choose zdcache when you look cached objects up by more than their key; for key-only caching with many concurrent writes, Caffeine or cache2k fits better.

None of the benchmarks put the caches under eviction pressure. Caffeine and cache2k do not evict in LRU order, Guava evicts in LRU order per segment and zdcache in strict LRU order. CQEngine never evicts.

### Running the benchmark

```bash
./benchmark -full                  # every JMH benchmark with 3 forks, plus memory footprint (about an hour)
./benchmark -quick                 # every JMH benchmark with 1 fork and short iterations
./benchmark -jmh "SearchBenchmark -f 1 -p subject=zdcache-buffered-indexed,cqengine"   # custom JMH options
./benchmark -publish               # publish the last full run
```

Results print to the terminal and are written to `build/reports/zdcache-benchmark/index.html`. The normal `./gradlew build` does not run the benchmark.

Only a full run can be published. `./benchmark -publish` copies the report to `docs/benchmark/index.html`, which GitHub Pages serves, and refreshes the summary tables above; after a quick or custom run it refuses and asks for a full run.

## Current limitations and follow-up work

1. **Not a transactional database.** Each public cache call is serialized per
   type, but external mutation of object references is not synchronized by this
   lock. Index keys from nested collections and properties should be stable
   immutable values (strings, boxed numbers, dates, UUIDs, enums, etc.).
2. The implementation stages extraction/validation before ordinary `put`, but
   does not yet provide general rollback for unexpected exceptions (e.g. custom
   `hashCode` or `Comparable` throwing *during* an index mutation, or OOM).
3. Each search uses an **exclusive type lock**, including ID resolution, so
   benchmarking contention is important. This makes Caffeine simple to swap in
   but does not exploit its concurrent reads. No cross-type atomic operations.
4. `SearchSpecification` can traverse nested lists of properties. Intersections,
   fuzzy text, ranked similarity, geospatial indexing, sorting, pagination,
   expiration, metrics, and persistence are not yet implemented.
5. Empty-string prefix searches match all indexed names; prefix searches are
   **not** typo-tolerant. Collection indexes do not preserve per-element join
   context needed for "both conditions on the same nested child" queries.
6. Cache size limits count root entities **per type**; index memory is not
   independently bounded. Many long strings/large nested lists can consume
   much more memory than the identity store itself.
7. Cache values can be records or mutable POJOs, but callers own their mutation
   discipline. A `get()` or `search()` can return a reference whose properties
   have changed since it was indexed.
8. Caffeine tests need the real library resolved by Gradle. The creation
   environment could compile against local API stand-ins but could not run the
   actual Gradle build; run `./gradlew test` in your environment.

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
  store/IdentityStoreLinkedHashMap.java
  store/IdentityStoreCaffeine.java
src/test/java/io/github/zdcache/ZDCacheTest.java
```

For trustworthy performance comparisons, add **JMH** measurements with warm-up,
allocation profiling, varied query cardinality, eviction pressure and concurrent
PUT/search workloads. `./gradlew jmh` runs the JMH benchmarks against Caffeine and
`./gradlew footprint` measures heap bytes per entry.
