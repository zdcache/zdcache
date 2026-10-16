package io.github.zdcache;

import io.github.zdcache.store.IdentityStoreFactory;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IndexReplacementTest {
    record Person(Long id, String name, String city, int age, List<String> tags, String note) {}

    private static final SearchSpecification<Person, Long> SPEC =
        SearchSpecification.builder(IdentityDimension.of(Person.class, "id", Person::id))
            .prefix(Person::name)
            .exact(Person::city)
            .range(Person::age)
            .exactEach("tag", Person::tags)
            .compound("cityAge", c -> c.exact(Person::city).range(Person::age))
            .build();

    private static void forEachStore(Consumer<ZDCache> scenario) {
        for (Entries entries : Entries.values()) {
            for (Searches searches : Searches.values()) {
                scenario.accept(ZDCache.builder().identityStore(IdentityStoreFactory.bufferedLru()).entries(entries)
                    .searches(searches).build());
                scenario.accept(ZDCache.builder().identityStore(IdentityStoreFactory.bufferedLru()).recency(Recency.APPROXIMATE)
                    .entries(entries).searches(searches).build());
                scenario.accept(ZDCache.builder().identityStore(IdentityStoreFactory.linkedHashMap()).entries(entries)
                    .searches(searches).build());
                scenario.accept(ZDCache.builder().identityStore(IdentityStoreFactory.caffeine()).entries(entries)
                    .searches(searches).build());
            }
        }
    }

    private static void assertOnly(Person expected, List<Person> found) {
        assertEquals(1, found.size(), () -> "found " + found);
        assertSame(expected, found.getFirst());
    }

    private static void assertFoundEverywhere(ZDCache cache, Person person) {
        assertOnly(person, cache.search(Person.class, "name", person.name().substring(0, 3)));
        assertOnly(person, cache.search(Person.class, "city", person.city()));
        assertOnly(person, cache.search(Person.class, "age", ValueRange.between(person.age(), person.age())));
        assertOnly(person, cache.search(Person.class, "cityAge", person.city(), ValueRange.between(person.age(), person.age())));
        for (String tag : person.tags()) assertOnly(person, cache.search(Person.class, "tag", tag));
    }

    @Test void anUpdateKeepingEveryIndexedValueIsFoundAsTheNewObject() {
        forEachStore(cache -> {
            cache.put(new Person(1L, "Simon", "Oslo", 29, List.of("a", "b"), "first"), SPEC);
            Person updated = new Person(1L, "Simon", "Oslo", 29, List.of("a", "b"), "second");
            cache.put(updated);
            assertFoundEverywhere(cache, updated);
            assertSame(updated, cache.get(Person.class, 1L));
        });
    }

    @Test void anUpdateChangingSomeValuesMovesOnlyThose() {
        forEachStore(cache -> {
            cache.put(new Person(1L, "Simon", "Oslo", 29, List.of("a", "b"), "first"), SPEC);
            Person moved = new Person(1L, "Simon", "Bergen", 29, List.of("b", "c"), "second");
            cache.put(moved);
            assertFoundEverywhere(cache, moved);
            assertTrue(cache.search(Person.class, "city", "Oslo").isEmpty());
            assertTrue(cache.search(Person.class, "cityAge", "Oslo", ValueRange.between(29, 29)).isEmpty());
            assertTrue(cache.search(Person.class, "tag", "a").isEmpty());
        });
    }

    @Test void multiValuedDimensionsInAnotherOrderAreStillReplaced() {
        forEachStore(cache -> {
            cache.put(new Person(1L, "Simon", "Oslo", 29, List.of("a", "b", "c"), "first"), SPEC);
            Person reordered = new Person(1L, "Simon", "Oslo", 29, List.of("c", "a", "b"), "second");
            cache.put(reordered);
            assertFoundEverywhere(cache, reordered);
        });
    }

    @Test void replacingOnePersonLeavesOthersWithTheSameValuesAlone() {
        forEachStore(cache -> {
            Person other = new Person(2L, "Simone", "Oslo", 29, List.of("a"), "other");
            cache.put(new Person(1L, "Simon", "Oslo", 29, List.of("a"), "first"), SPEC);
            cache.put(other);
            Person updated = new Person(1L, "Simon", "Oslo", 29, List.of("a"), "second");
            cache.put(updated);
            List<Person> inOslo = cache.search(Person.class, "city", "Oslo");
            assertEquals(2, inOslo.size());
            assertTrue(inOslo.stream().anyMatch(p -> p == updated));
            assertTrue(inOslo.stream().anyMatch(p -> p == other));
            assertOnly(other, cache.search(Person.class, "name", "Simone"));
        });
    }

    @Test void anUpdateThatIsEvictedRightAwayLeavesNoTraceInTheIndexes() {
        var cache = ZDCache.builder().defaultMaxPerType(1).identityStore(IdentityStoreFactory.bufferedLru()).build();
        cache.put(new Person(1L, "Simon", "Oslo", 29, List.of("a"), "first"), SPEC);
        cache.put(new Person(2L, "Siri", "Oslo", 41, List.of("a"), "second"));
        Person updated = new Person(2L, "Siri", "Oslo", 41, List.of("a"), "third");
        cache.put(updated);
        assertNull(cache.get(Person.class, 1L));
        assertOnly(updated, cache.search(Person.class, "city", "Oslo"));
        assertOnly(updated, cache.search(Person.class, "tag", "a"));
    }
}
