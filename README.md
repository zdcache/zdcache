# ZDCache — Searchable Dimensions Cache (Java 21, Gradle)

An in-process, multi-type, per-type-bounded object cache with specification-driven searchable dimensions 
and secondary indexes. Cache writes are explicitly submitted; the cache does **not** track mutations to 
objects returned by reference.

## Installation

```xml
<dependency>
    <groupId>io.github.zdcache</groupId>
    <artifactId>zdcache</artifactId>
    <version>0.1.0</version>
</dependency>
```

```kotlin
implementation("io.github.zdcache:zdcache:0.1.0")
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

// The default buffered LRU store keeps strict LRU order; approximate recency trades
// exact order under heavy contention for higher concurrent throughput:
var forThroughput = ZDCache.builder()
    .recency(Recency.APPROXIMATE)
    .build();

// Method references name dimensions and searches, so renames stay in sync:
var byReference = SearchSpecification.builder(personId)
    .prefix(Person::name)
    .build();
List<Person> named = cache.search(Person::name, "si");

// Reads that do not count as use, and housekeeping:
Person quiet = cache.peek(Person.class, 1L);
boolean cached = cache.contains(Person.class, 2L);
List<Person> everyone = cache.values(Person.class);
cache.clear(Person.class);
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

- Each class has its own capacity, `ReentrantReadWriteLock`, `IdentityStore`, specification,
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

## Benchmark

zdcache is compared with three key-value caches (Caffeine, cache2k and Guava) and with CQEngine, an indexed in-memory collection. JMH measures single-threaded `get` and `put`, eight threads reading and writing concurrently, and prefix, exact, compound and range searches at 10,000 and 100,000 entries. JOL measures memory per entry. Caches without indexes answer searches by scanning every entry.

**[Open the full interactive report](https://zdcache.github.io/zdcache/benchmark/)** for every library and every zdcache identity store, with confidence intervals and a table view.

> **Disclaimer:** these numbers were collected by running the benchmark on the author's development machine. Results vary with hardware, JVM, load and data shape, so treat them as an indication of gains and losses, not as guarantees. Run the benchmark on your own machine and data before deciding.

<!-- benchmark-summary:start -->
Measured 2026-10-13T22:57:53Z on Java 21.0.6 with 11 CPUs, 3 forks per benchmark, at 100,000 entries. Lower is better for time and memory; higher is better for operations per second.

**Single thread and memory**

| | get | put | Memory per entry |
|---|---:|---:|---:|
| zdcache | 24 ns | 76 ns | 123 B |
| zdcache, 4 indexes | 36 ns | 2,292 ns | 424 B |
| zdcache, approximate recency | 24 ns | 85 ns | – |
| zdcache, approximate, 4 indexes | 33 ns | 2,361 ns | – |
| Caffeine | 28 ns | 106 ns | 109 B |
| cache2k | 12 ns | 34 ns | 107 B |
| Guava | 38 ns | 89 ns | 99 B |
| CQEngine, 4 indexes | 347 ns | 4,290 ns | 769 B |

**Concurrent, 8 threads (million operations per second)**

| | Reads only | Mixed: reads | Mixed: writes |
|---|---:|---:|---:|
| zdcache | 45 | 12 | 0.50 |
| zdcache, 4 indexes | 39 | 24 | 0.14 |
| zdcache, approximate recency | 90 | 39 | 3.1 |
| zdcache, approximate, 4 indexes | 49 | 31 | 0.13 |
| Caffeine | 100 | 58 | 7.5 |
| cache2k | 198 | 81 | 6.6 |
| Guava | 13 | 16 | 0.01 |
| CQEngine, 4 indexes | 16 | 12 | 0.10 |

**Search**

| | Prefix | Exact | Compound | Range |
|---|---:|---:|---:|---:|
| zdcache, 4 indexes | 1.3 µs | 13 µs | 2.4 µs | 143 µs |
| CQEngine, 4 indexes | 20 µs | 21 µs | 38 µs | 210 µs |
| Caffeine (full scan) | 917 µs | 1,137 µs | 1,143 µs | 1,322 µs |
| cache2k (full scan) | 2,823 µs | 2,780 µs | 2,654 µs | 2,975 µs |
| Guava (full scan) | 2,197 µs | 2,735 µs | 2,887 µs | 2,258 µs |
<!-- benchmark-summary:end -->

How to read it: on one thread, zdcache's key lookup is as fast as Caffeine's or faster in both recency modes (24 ns for `get`, 76–85 ns for `put`), with cache2k the fastest, and keeping four indexes up to date costs about 2.3 µs per `put`. Searches through indexes are about 15× faster than CQEngine for prefix and compound queries, 1.5× for exact and range queries, and 9–2,000× faster than scanning a cache. Under concurrent load the default strict LRU store reads at about 45% of Caffeine's rate and writes at under a tenth of it. With `recency(Recency.APPROXIMATE)` zdcache reads at 65–90% of Caffeine's rate between runs and writes at about 40% of it, giving up exact LRU order under heavy contention. Choose zdcache when you look cached objects up by more than their key; for key-only caching with many concurrent writes, Caffeine or cache2k fits better.

None of the benchmarks put the caches under eviction pressure. Caffeine and cache2k do not evict in LRU order, Guava evicts in LRU order per segment and zdcache in strict LRU order by default. CQEngine never evicts.

### Running the benchmark

```bash
./benchmark -full                  # settings in src/jmh/resources/benchmark-full.properties (about 12 minutes)
./benchmark -quick                 # settings in src/jmh/resources/benchmark-quick.properties (about 5 minutes)
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
   exception. No cross-type atomic operations.
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
