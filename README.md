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
./gradlew compareStores   # simple smoke comparison, NOT JMH
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
  benchmark/StoreComparison.java   # simple smoke comparison
src/test/java/io/github/zdcache/ZDCacheTest.java
```

For trustworthy performance comparisons, add **JMH** measurements with warm-up,
allocation profiling, varied query cardinality, eviction pressure and concurrent
PUT/search workloads. The included `compareStores` task is only an executable
smoke check, not an evidence-quality benchmark.
