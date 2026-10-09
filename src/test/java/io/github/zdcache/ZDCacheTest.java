package io.github.zdcache;

import io.github.zdcache.store.IdentityStoreFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZDCacheTest {
    record Address(String country, String city, List<String> tags) {}
    record Person(Long id, String name, int age, List<Address> addresses) {}
    record Company(String id, String name) {}

    static final IdentityDimension<Person, Long> PERSON_ID =
        IdentityDimension.of(Person.class, "id", Person::id);
    static final IdentityDimension<Company, String> COMPANY_ID =
        IdentityDimension.of(Company.class, "id", Company::id);

    private static Person person(long id, String name, int age, Address... addresses) {
        return new Person(id, name, age, List.of(addresses));
    }

    private static SearchSpecification<Person, Long> personSpecification() {
        return SearchSpecification.builder(PERSON_ID)
            .prefix("name", Person::name)
            .range("age", Person::age)
            .each("addresses", Person::addresses, address -> address
                .prefix("city", Address::city)
                .exact("country", Address::country)
                .exactEach("tags", Address::tags))
            .build();
    }

    private static ZDCache create(IdentityStoreFactory factory, int size) {
        return ZDCache.builder()
            .defaultMaxPerType(size)
            .identityStore(factory)
            .build();
    }

    private static void forBothStores(java.util.function.Consumer<ZDCache> scenario) {
        scenario.accept(create(IdentityStoreFactory.linkedHashMap(), 10));
        scenario.accept(create(IdentityStoreFactory.caffeine(), 10));
    }

    @Test void prefixesAndNestedCollectionsAndDuplicateRoots() {
        forBothStores(cache -> {
            var spec = personSpecification();
            cache.put(person(1, "Simon", 29,
                new Address("Norway", "Oslo", List.of("home", "office")),
                new Address("Sweden", "Osnabrück", List.of("home"))), spec);
            cache.put(person(2, "Simone", 32, new Address("Norway", "Oslo", List.of("home"))));
            cache.put(person(3, "Siri", 21));
            assertEquals(Set.of(1L, 2L, 3L), ids(cache.search(Person.class, "name", "SI")));
            assertEquals(Set.of(1L, 2L), ids(cache.search(Person.class, "addresses.city", "os")));
            assertEquals(Set.of(1L, 2L), ids(cache.search(Person.class, "addresses.tags", "home")));
            assertEquals(Set.of(1L, 2L), ids(cache.search(Person.class, "addresses.country", "Norway")));
            assertEquals(Set.of(1L, 3L), ids(cache.search(Person.class, "age", ValueRange.between(20, 30))));
            assertEquals(List.of(), cache.search(Person.class, "name", "zz"));
        });
    }

    private static Set<Long> ids(List<Person> results) {
        return results.stream().map(Person::id).collect(java.util.stream.Collectors.toSet());
    }

    @Test void replacementReindexesByCapturedSnapshot() {
        forBothStores(cache -> {
            var id = IdentityDimension.of(MutablePerson.class, "id", MutablePerson::getId);
            var spec = SearchSpecification.builder(id)
                .prefix("name", MutablePerson::getName).build();
            MutablePerson first = new MutablePerson(10L, "Simon");
            cache.put(first, spec);
            first.setName("Peter"); // deliberately NOT tracked by cache
            assertEquals(1, cache.search(MutablePerson.class, "name", "si").size());
            cache.put(new MutablePerson(10L, "Siri"));
            assertTrue(cache.search(MutablePerson.class, "name", "pe").isEmpty());
            assertTrue(cache.search(MutablePerson.class, "name", "sim").isEmpty());
            assertEquals(1, cache.search(MutablePerson.class, "name", "sir").size());
        });
    }

    static class MutablePerson {
        private final long id;
        private String name;
        MutablePerson(long id, String name) { this.id = id; this.name = name; }
        Long getId() { return id; }
        String getName() { return name; }
        void setName(String name) { this.name = name; }
    }

    @Test void insertConflictsAndIdentityNeverChanges() {
        forBothStores(cache -> {
            var old = personSpecification();
            cache.put(person(1, "Simon", 25), old);
            var conflicting = SearchSpecification.builder(PERSON_ID)
                .exact("name", Person::name).build();
            assertThrows(ConflictingSearchSpecificationException.class,
                () -> cache.put(person(2, "Other", 25), conflicting));
            assertEquals(1, cache.size(Person.class));
            var differentIdentity = IdentityDimension.of(Person.class, "id", Person::id);
            assertThrows(ImmutableIdentityDimensionException.class,
                () -> cache.updateSpecification(Person.class,
                    SearchSpecification.basic(differentIdentity)));
            assertEquals(Set.of(1L), ids(cache.search(Person.class, "name", "s")));
        });
    }

    @Test void fullReindexCanAddAndRemoveDimensions() {
        forBothStores(cache -> {
            var basic = SearchSpecification.basic(PERSON_ID);
            cache.put(person(1, "Simon", 26, new Address("Norway", "Oslo", List.of())), basic);
            assertThrows(MissingSearchSpecificationStrategyException.class,
                () -> cache.search(Person.class, "name", "si"));
            var searchable = personSpecification();
            cache.updateSpecification(Person.class, searchable);
            assertEquals(Set.of(1L), ids(cache.search(Person.class, "addresses.city", "os")));
            var onlyAge = SearchSpecification.builder(PERSON_ID)
                .range("age", Person::age).build();
            cache.updateSpecification(Person.class, onlyAge);
            assertThrows(UnknownSearchDimensionException.class,
                () -> cache.search(Person.class, "name", "si"));
            assertEquals(Set.of(1L), ids(cache.search(Person.class, "age", ValueRange.between(25, 26))));
        });
    }

    @Test void failedReindexLeavesPreviousSpecificationUntouched() {
        forBothStores(cache -> {
            var old = personSpecification();
            cache.put(person(1, "Simon", 22), old);
            var broken = SearchSpecification.builder(PERSON_ID)
                .prefix("name", p -> { throw new IllegalStateException("bad accessor"); })
                .build();
            assertThrows(IllegalStateException.class,
                () -> cache.updateSpecification(Person.class, broken));
            assertSame(old, cache.getSpecification(Person.class));
            assertEquals(Set.of(1L), ids(cache.search(Person.class, "name", "si")));
        });
    }

    @Test void evictionCleansDimensionsAndTypeCapsAreIndependent() {
        for (IdentityStoreFactory factory : List.of(
                IdentityStoreFactory.linkedHashMap(), IdentityStoreFactory.caffeine())) {
            var cache = ZDCache.builder()
                .capacity(Person.class, 2)
                .capacity(Company.class, 1)
                .identityStore(factory).build();
            var spec = personSpecification();
            cache.put(person(1, "Simon", 20), spec);
            cache.put(person(2, "Siri", 21));
            cache.put(person(3, "Sarah", 22));
            assertEquals(2, cache.size(Person.class));
            // Different eviction policies: assert whichever is absent is also unindexed.
            for (long id = 1; id <= 3; id++) {
                var entity = cache.get(Person.class, id);
                String prefix = switch ((int) id) {
                    case 1 -> "sim";
                    case 2 -> "sir";
                    default -> "sar";
                };
                assertEquals(entity == null, cache.search(Person.class, "name", prefix).isEmpty());
            }
            var companySpec = SearchSpecification.builder(COMPANY_ID)
                .prefix("name", Company::name).build();
            cache.put(new Company("a", "Acme"), companySpec);
            cache.put(new Company("b", "Blue"));
            assertEquals(1, cache.size(Company.class));
            assertEquals(2, cache.size(Person.class));
        }
    }

    @Test void explicitDefaultIdentityCanBeUpgradedWithoutRekeying() {
        forBothStores(cache -> {
            cache.put(Person.class, 1L, person(1, "Simon", 20));
            assertEquals("Simon", cache.get(Person.class, 1L).name());
            assertThrows(MissingSearchSpecificationStrategyException.class,
                () -> cache.search(Person.class, "name", "si"));
            var explicitId = cache.getSpecification(Person.class).identity();
            var searchable = SearchSpecification.builder(explicitId)
                .prefix("name", Person::name).build();
            cache.updateSpecification(Person.class, searchable);
            assertEquals(Set.of(1L), ids(cache.search(Person.class, "name", "si")));
            cache.put(Person.class, 1L, person(1, "Peter", 20));
            assertTrue(cache.search(Person.class, "name", "si").isEmpty());
            assertEquals(Set.of(1L), ids(cache.search(Person.class, "name", "pe")));
            assertThrows(IllegalStateException.class, () -> cache.put(person(2, "Siri", 22)));
        });
    }

    @Test void linkedHashMapHasStrictLruWithoutReindexPromotions() {
        var cache = create(IdentityStoreFactory.linkedHashMap(), 2);
        var spec = personSpecification();
        cache.put(person(1, "Simon", 20), spec);
        cache.put(person(2, "Siri", 21));
        cache.get(Person.class, 1L); // 2 is least recently used
        cache.updateSpecification(Person.class, SearchSpecification.builder(PERSON_ID)
            .prefix("name", Person::name).build());
        cache.put(person(3, "Simone", 24));
        assertNull(cache.get(Person.class, 2L));
        assertNotNull(cache.get(Person.class, 1L));
        assertNotNull(cache.get(Person.class, 3L));
    }

    @Test void concurrentWritesAndSearchesKeepDimensionIndexesConsistent() throws Exception {
        var cache = create(IdentityStoreFactory.linkedHashMap(), 50);
        var spec = personSpecification();
        cache.put(person(1, "Simon", 20), spec);
        int loops = 600;
        var problem = new AtomicReference<Throwable>();
        var start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            tasks.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < loops; i++) {
                        cache.put(person(1, i % 2 == 0 ? "Simon" : "Peter", 20));
                    }
                } catch (Throwable error) { problem.compareAndSet(null, error); }
            }));
            for (int t = 0; t < 2; t++) {
                tasks.add(pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < loops; i++) {
                            for (Person p : cache.search(Person.class, "name", "si")) {
                                if (!p.name().startsWith("Si")) {
                                    throw new AssertionError("inconsistent index vs cached state");
                                }
                            }
                        }
                    } catch (Throwable error) { problem.compareAndSet(null, error); }
                }));
            }
            start.countDown();
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
            if (problem.get() != null) throw new AssertionError(problem.get());
        } finally {
            pool.shutdownNow();
        }
    }
}
