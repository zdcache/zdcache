package io.github.zdcache;

import io.github.zdcache.store.IdentityStoreFactory;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CacheOperationsTest {
    static class Person {
        final long id;
        final String name;
        Person(long id, String name) { this.id = id; this.name = name; }
    }
    static class Employee extends Person {
        Employee(long id, String name) { super(id, name); }
    }
    record Note(String text) {}

    static final IdentityDimension<Person, Long> PERSON_ID =
        IdentityDimension.of(Person.class, "id", (Person p) -> p.id);
    static final SearchSpecification<Person, Long> SPEC =
        SearchSpecification.builder(PERSON_ID).prefix("name").build();

    private static ZDCache cache(IdentityStoreFactory stores, int capacity, Person... people) {
        var cache = ZDCache.builder().defaultMaxPerType(capacity).identityStore(stores).build();
        cache.register(SPEC);
        for (Person person : people) cache.put(person);
        return cache;
    }

    private static ZDCache lruOfThree(IdentityStoreFactory stores) {
        return cache(stores, 3, new Person(1, "Simon"), new Person(2, "Siri"), new Person(3, "Petter"));
    }

    private static void forEachLruStore(Consumer<IdentityStoreFactory> scenario) {
        scenario.accept(IdentityStoreFactory.linkedHashMap());
        scenario.accept(IdentityStoreFactory.bufferedLru());
    }

    private static void forEachStore(Consumer<IdentityStoreFactory> scenario) {
        scenario.accept(IdentityStoreFactory.linkedHashMap());
        scenario.accept(IdentityStoreFactory.caffeine());
        scenario.accept(IdentityStoreFactory.bufferedLru());
    }

    private static ZDCache mixed(IdentityStoreFactory stores) {
        return cache(stores, 10, new Person(1, "Simon"), new Employee(2, "Siri"), new Person(3, "Petter"));
    }

    private static List<Long> ids(List<? extends Person> people) {
        return people.stream().map(p -> p.id).sorted().toList();
    }

    @Test void peekReturnsTheEntityWithoutCountingAsUse() {
        forEachLruStore(stores -> {
            var cache = lruOfThree(stores);
            assertEquals("Simon", cache.peek(Person.class, 1L).name);

            cache.put(new Person(4, "Nina"));

            assertEquals(List.of(2L, 3L, 4L), ids(cache.values(Person.class)));
        });
    }

    @Test void containsDoesNotCountAsUse() {
        forEachLruStore(stores -> {
            var cache = lruOfThree(stores);
            assertTrue(cache.contains(Person.class, 1L));

            cache.put(new Person(4, "Nina"));

            assertFalse(cache.contains(Person.class, 1L));
        });
    }

    @Test void peekAndContainsOnlySeeInstancesOfTheRequestedType() {
        forEachStore(stores -> {
            var cache = mixed(stores);

            assertNull(cache.peek(Employee.class, 1L));
            assertEquals("Siri", cache.peek(Employee.class, 2L).name);
            assertFalse(cache.contains(Employee.class, 1L));
            assertTrue(cache.contains(Person.class, 2L));
            assertFalse(cache.contains(Person.class, 9L));
        });
    }

    @Test void containsWorksForExplicitIdentities() {
        var cache = ZDCache.builder().build();
        cache.put(Note.class, "a", new Note("first"));

        assertTrue(cache.contains(Note.class, "a"));
        assertFalse(cache.contains(Note.class, "b"));
        assertEquals("first", cache.peek(Note.class, "a").text());
    }

    @Test void valuesReturnsEveryCachedEntityOfTheRequestedType() {
        forEachStore(stores -> {
            var cache = mixed(stores);

            assertEquals(List.of(1L, 2L, 3L), ids(cache.values(Person.class)));
            assertEquals(List.of(2L), ids(cache.values(Employee.class)));
            assertThrows(UnsupportedOperationException.class,
                () -> cache.values(Person.class).add(new Person(9, "Nope")));
        });
    }

    @Test void valuesDoesNotCountAsUse() {
        forEachLruStore(stores -> {
            var cache = lruOfThree(stores);
            cache.values(Person.class);

            cache.put(new Person(4, "Nina"));

            assertFalse(cache.contains(Person.class, 1L));
        });
    }

    @Test void clearRemovesEntitiesAndTheirIndexesButKeepsTheSpecification() {
        forEachStore(stores -> {
            var cache = mixed(stores);

            cache.clear(Person.class);

            assertEquals(0, cache.size(Person.class));
            assertTrue(cache.values(Person.class).isEmpty());
            assertTrue(cache.search(Person.class, "name", "").isEmpty());
            assertNull(cache.mostRecentlyAccessedId(Person.class));
            assertSame(SPEC, cache.getSpecification(Person.class));

            cache.put(new Person(5, "Siv"));
            assertEquals(List.of(5L), ids(cache.search(Person.class, "name", "si")));
        });
    }

    @Test void clearingASubtypeOnlyRemovesItsInstances() {
        forEachStore(stores -> {
            var cache = mixed(stores);

            cache.clear(Employee.class);

            assertEquals(List.of(1L, 3L), ids(cache.values(Person.class)));
            assertEquals(List.of(1L), ids(cache.search(Person.class, "name", "si")));
        });
    }

    @Test void clearWithoutATypeEmptiesEveryTypeAndKeepsTheirSpecifications() {
        var cache = mixed(IdentityStoreFactory.linkedHashMap());
        cache.put(Note.class, "a", new Note("first"));

        cache.clear();

        assertEquals(0, cache.size(Person.class));
        assertEquals(0, cache.size(Note.class));
        assertSame(SPEC, cache.getSpecification(Person.class));
        assertNotNull(cache.getSpecification(Note.class));
        cache.put(Note.class, "b", new Note("second"));
        assertTrue(cache.contains(Note.class, "b"));
    }

    @Test void mostAndLeastRecentlyAccessedIdFollowTheEvictionOrder() {
        forEachLruStore(stores -> {
            var cache = lruOfThree(stores);
            cache.get(Person.class, 1L);

            Long mostRecent = cache.mostRecentlyAccessedId(Person.class);
            Long leastRecent = cache.leastRecentlyAccessedId(Person.class);
            assertEquals(1L, mostRecent);
            assertEquals(2L, leastRecent);

            cache.put(new Person(4, "Nina"));
            assertFalse(cache.contains(Person.class, 2L));
        });
    }

    @Test void mostAndLeastRecentlyAccessedIdDoNotCountAsUse() {
        forEachLruStore(stores -> {
            var cache = lruOfThree(stores);
            cache.leastRecentlyAccessedId(Person.class);
            cache.mostRecentlyAccessedId(Person.class);

            cache.put(new Person(4, "Nina"));

            assertFalse(cache.contains(Person.class, 1L));
        });
    }

    @Test void mostAndLeastRecentlyAccessedIdOfASubtypeSkipOtherInstances() {
        forEachStore(stores -> {
            var cache = mixed(stores);

            Long mostRecent = cache.mostRecentlyAccessedId(Employee.class);
            Long leastRecent = cache.leastRecentlyAccessedId(Employee.class);
            assertEquals(2L, mostRecent);
            assertEquals(2L, leastRecent);

            cache.remove(Employee.class, 2L);
            assertNull(cache.mostRecentlyAccessedId(Employee.class));
        });
    }

    @Test void unknownTypesHaveNoEntries() {
        var cache = ZDCache.builder().build();

        assertNull(cache.peek(Note.class, "a"));
        assertFalse(cache.contains(Note.class, "a"));
        assertTrue(cache.values(Note.class).isEmpty());
        assertNull(cache.mostRecentlyAccessedId(Note.class));
        assertNull(cache.leastRecentlyAccessedId(Note.class));
        assertDoesNotThrow(() -> cache.clear(Note.class));
        assertDoesNotThrow(() -> cache.clear());
    }
}
