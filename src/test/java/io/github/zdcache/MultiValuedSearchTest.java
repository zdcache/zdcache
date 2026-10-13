package io.github.zdcache;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MultiValuedSearchTest {
    record Address(String city, int zip) {}
    record Person(long id, String name, int age, List<String> tags, List<Address> addresses) {}

    static final IdentityDimension<Person, Long> ID = IdentityDimension.of(Person.class, "id", Person::id);

    private static SearchSpecification<Person, Long> spec() {
        return SearchSpecification.builder(ID)
            .prefix("name")
            .range("age")
            .prefixEach("tags")
            .exactEach("exactTags", Person::tags)
            .each("addresses", a -> a.prefix("city").range("zip"))
            .compound("nameAge", c -> c.exact("name").range("age"))
            .compound("nameTag", c -> c.exact("name").exactEach("tags"))
            .build();
    }

    @Test void dimensionsKnowWhetherOneEntityCanHaveSeveralValues() {
        var spec = spec();
        assertFalse(spec.dimension("name").multiValued());
        assertFalse(spec.dimension("age").multiValued());
        assertFalse(spec.dimension("nameAge").multiValued());
        assertTrue(spec.dimension("tags").multiValued());
        assertTrue(spec.dimension("exactTags").multiValued());
        assertTrue(spec.dimension("addresses.city").multiValued());
        assertTrue(spec.dimension("addresses.zip").multiValued());
        assertTrue(spec.dimension("nameTag").multiValued());
    }

    @Test void entitiesMatchingThroughSeveralValuesAreReturnedOnce() {
        var cache = ZDCache.builder().build();
        cache.put(new Person(1, "Simon", 30, List.of("home", "house", "holiday"),
            List.of(new Address("Oslo", 150), new Address("Osaka", 160), new Address("Bergen", 5000))), spec());
        cache.put(new Person(2, "Siri", 40, List.of("work"), List.of(new Address("Oslo", 170))));

        assertEquals(List.of(1L), ids(cache.search(Person.class, "tags", "ho")));
        assertEquals(List.of(1L, 2L), ids(cache.search(Person.class, "addresses.city", "os")));
        assertEquals(List.of(1L, 2L), ids(cache.search(Person.class, "addresses.zip", ValueRange.between(100, 200))));
        assertEquals(List.of(1L), ids(cache.search(Person.class, "nameTag", "Simon", "house")));
    }

    @Test void searchResultsCannotBeModified() {
        var cache = ZDCache.builder().build();
        cache.put(new Person(1, "Simon", 30, List.of(), List.of()), spec());
        var found = cache.search(Person.class, "name", "s");
        assertThrows(UnsupportedOperationException.class, () -> found.add(found.get(0)));
    }

    private static List<Long> ids(List<Person> people) {
        return people.stream().map(Person::id).sorted().toList();
    }

    @Test void replacingRemovingAndEvictingEntitiesWithSeveralValuesCleansEveryValue() {
        for (var stores : List.of(io.github.zdcache.store.IdentityStoreFactory.bufferedLru(),
                io.github.zdcache.store.IdentityStoreFactory.linkedHashMap(),
                io.github.zdcache.store.IdentityStoreFactory.caffeine())) {
            var cache = ZDCache.builder().defaultMaxPerType(2).identityStore(stores).build();
            var spec = spec();
            cache.put(new Person(1, "Simon", 30, List.of("home", "work"), List.of(new Address("Oslo", 150), new Address("Bergen", 5003))), spec);

            cache.put(new Person(1, "Simon", 30, List.of("work", "gym"), List.of(new Address("Oslo", 150))));
            assertTrue(cache.search(Person.class, "exactTags", "home").isEmpty());
            assertTrue(cache.search(Person.class, "addresses.city", "berg").isEmpty());
            assertTrue(cache.search(Person.class, "nameTag", "Simon", "home").isEmpty());
            assertEquals(1, cache.search(Person.class, "exactTags", "gym").size());

            cache.remove(Person.class, 1L);
            assertTrue(cache.search(Person.class, "exactTags", "work").isEmpty());
            assertTrue(cache.search(Person.class, "tags", "").isEmpty());

            cache.put(new Person(2, "Siri", 40, List.of("a", "b"), List.of()));
            cache.put(new Person(3, "Sven", 41, List.of("c", "d"), List.of()));
            cache.put(new Person(4, "Sara", 42, List.of("e", "f"), List.of()));
            int found = 0;
            for (String tag : List.of("a", "b", "c", "d", "e", "f")) found += cache.search(Person.class, "exactTags", tag).size();
            assertEquals(2 * cache.size(Person.class), found);
        }
    }
}
