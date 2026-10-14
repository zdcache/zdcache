package io.github.zdcache;

import io.github.zdcache.store.IdentityStoreFactory;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SearchResultConsistencyTest {
    record Person(long id, String name, String city, List<String> tags) {}

    static final IdentityDimension<Person, Long> PERSON_ID = IdentityDimension.of(Person.class, "id", Person::id);
    static final SearchSpecification<Person, Long> SPEC = SearchSpecification.builder(PERSON_ID)
        .prefix(Person::name)
        .exact(Person::city)
        .prefixEach(Person::tags)
        .build();

    private static void forEachStore(int capacity, Consumer<ZDCache> scenario) {
        for (var stores : List.of(IdentityStoreFactory.bufferedLru(), IdentityStoreFactory.linkedHashMap(),
                IdentityStoreFactory.caffeine())) {
            for (Entries entries : Entries.values()) {
                var cache = ZDCache.builder().defaultMaxPerType(capacity).identityStore(stores).entries(entries).build();
                cache.register(SPEC);
                scenario.accept(cache);
            }
        }
    }

    private static Person person(long id, String name, String city, String... tags) {
        return new Person(id, name, city, List.of(tags));
    }

    @Test void anEqualReplacementIsReturnedInsteadOfTheOldInstance() {
        forEachStore(10, cache -> {
            var old = person(1, "Simon", "Oslo");
            var replacement = person(1, "Simon", "Oslo");
            cache.put(old);
            cache.put(replacement);

            List<Person> found = cache.search(Person::name, "si");

            assertEquals(1, found.size());
            assertSame(replacement, found.getFirst());
            assertSame(replacement, cache.search(Person::city, "Oslo").getFirst());
        });
    }

    @Test void aReplacedEntityIsNeverReturnedUnderItsOldValues() {
        forEachStore(10, cache -> {
            cache.put(person(1, "Simon", "Oslo"));
            var moved = person(1, "Simon", "Bergen");
            cache.put(moved);

            assertTrue(cache.search(Person::city, "Oslo").isEmpty());
            assertSame(moved, cache.search(Person::city, "Bergen").getFirst());
        });
    }

    @Test void removedEntitiesAreNotReturned() {
        forEachStore(10, cache -> {
            cache.put(person(1, "Simon", "Oslo"));
            cache.put(person(2, "Siri", "Oslo"));

            cache.remove(Person.class, 1L);

            assertEquals(List.of(2L), cache.search(Person::city, "Oslo").stream().map(Person::id).toList());
        });
    }

    @Test void evictedEntitiesDisappearFromSearchOnEveryStore() {
        forEachStore(3, cache -> {
            for (long id = 0; id < 50; id++) cache.put(person(id, "P" + id, "Oslo", "tag"));

            List<Person> found = cache.search(Person::city, "Oslo");

            assertEquals(cache.size(Person.class), found.size());
            for (Person p : found) assertSame(p, cache.peek(Person.class, p.id()));
            assertEquals(found.size(), cache.search(Person::tags, "t").size());
        });
    }

    @Test void anEntityMatchingSeveralValuesIsReturnedOnce() {
        forEachStore(10, cache -> {
            var tagged = person(1, "Simon", "Oslo", "tea", "team", "teal");
            cache.put(tagged);

            List<Person> found = cache.search(Person::tags, "tea");

            assertEquals(1, found.size());
            assertSame(tagged, found.getFirst());
        });
    }

    @Test void aRebuiltSpecificationReturnsTheCurrentEntities() {
        forEachStore(10, cache -> {
            cache.put(person(1, "Simon", "Oslo"));
            var current = person(1, "Simon", "Bergen");
            cache.put(current);
            var byCityOnly = SearchSpecification.builder(PERSON_ID).exact(Person::city).build();

            cache.updateSpecification(Person.class, byCityOnly);

            assertSame(current, cache.search(Person::city, "Bergen").getFirst());
            assertTrue(cache.search(Person::city, "Oslo").isEmpty());
        });
    }
}
