package io.github.zdcache.internal;

import io.github.zdcache.IdentityDimension;
import io.github.zdcache.SearchSpecification;
import io.github.zdcache.store.IdentityStore;
import io.github.zdcache.store.IdentityStoreFactory;
import io.github.zdcache.store.IndexedEntry;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TypeCacheEntriesTest {
    record Person(Long id, String name, String city) {}

    static final IdentityDimension<Person, Long> ID = IdentityDimension.of(Person.class, "id", Person::id);
    static final SearchSpecification<Person, Long> INDEXED = SearchSpecification.builder(ID)
        .prefix(Person::name).exact(Person::city).build();
    static final SearchSpecification<Person, Long> KEYS_ONLY = SearchSpecification.builder(ID).build();

    private final IdentityStore<Long, Person> store = IdentityStoreFactory.bufferedLru().create(2);

    private TypeCache<Person, Long> cache(SearchSpecification<Person, Long> spec, boolean entryPerId) {
        return new TypeCache<>(spec, store, new ReentrantReadWriteLock(), entryPerId);
    }

    private static List<Long> ids(List<Person> people) {
        return people.stream().map(Person::id).sorted().toList();
    }

    @Test void anIndexedTypeKeepsOneEntryPerIdWhenAsked() {
        var cache = cache(INDEXED, true);
        cache.put(1L, new Person(1L, "Ada", "Oslo"), null);
        IndexedEntry<Person> first = store.peek(1L);
        Person updated = new Person(1L, "Ada", "Bergen");
        cache.put(1L, updated, null);
        assertSame(first, store.peek(1L));
        assertSame(updated, first.entity());
        assertEquals(List.of(1L), ids(cache.search(Person.class, "city", "Bergen")));
        assertTrue(cache.search(Person.class, "city", "Oslo").isEmpty());
    }

    @Test void byDefaultEveryPutCreatesAnEntry() {
        var cache = cache(INDEXED, false);
        cache.put(1L, new Person(1L, "Ada", "Oslo"), null);
        IndexedEntry<Person> first = store.peek(1L);
        cache.put(1L, new Person(1L, "Ada", "Oslo"), null);
        assertNotSame(first, store.peek(1L));
    }

    @Test void keyOnlyTypesCreateAnEntryPerPutEvenWhenAsked() {
        var cache = cache(KEYS_ONLY, true);
        cache.put(1L, new Person(1L, "Ada", "Oslo"), null);
        IndexedEntry<Person> first = store.peek(1L);
        cache.put(1L, new Person(1L, "Ada", "Bergen"), null);
        assertNotSame(first, store.peek(1L));
    }

    @Test void anIdReinsertedAfterEvictionGetsAFreshEntry() {
        var cache = cache(INDEXED, true);
        cache.put(1L, new Person(1L, "Ada", "Oslo"), null);
        IndexedEntry<Person> evicted = store.peek(1L);
        cache.put(2L, new Person(2L, "Bo", "Oslo"), null);
        cache.put(3L, new Person(3L, "Cy", "Oslo"), null);
        assertNull(store.peek(1L));
        assertTrue(evicted.evicted());

        cache.put(1L, new Person(1L, "Ada", "Bergen"), null);
        assertNotSame(evicted, store.peek(1L));
        assertFalse(store.peek(1L).evicted());
        assertEquals(List.of(1L), ids(cache.search(Person.class, "city", "Bergen")));
        assertEquals(List.of(1L), ids(cache.search(Person.class, "name", "ad")));
    }

    @Test void updatesAfterASpecificationChangeCompareAgainstTheRebuiltIndexes() {
        var cache = cache(KEYS_ONLY, true);
        cache.put(1L, new Person(1L, "Ada", "Oslo"), null);
        cache.updateSpecification(INDEXED);
        IndexedEntry<Person> first = store.peek(1L);
        cache.put(1L, new Person(1L, "Ada", "Bergen"), null);
        assertSame(first, store.peek(1L));
        assertEquals(List.of(1L), ids(cache.search(Person.class, "city", "Bergen")));
        assertTrue(cache.search(Person.class, "city", "Oslo").isEmpty());
        assertEquals(List.of(1L), ids(cache.search(Person.class, "name", "ada")));
    }

    @Test void removingAReusedEntryClearsEveryIndex() {
        var cache = cache(INDEXED, true);
        cache.put(1L, new Person(1L, "Ada", "Oslo"), null);
        cache.put(1L, new Person(1L, "Ada", "Bergen"), null);
        cache.remove(1L, Person.class);
        assertTrue(cache.search(Person.class, "city", "Bergen").isEmpty());
        assertTrue(cache.search(Person.class, "name", "ada").isEmpty());
        assertNull(cache.get(1L, Person.class));
    }
}
