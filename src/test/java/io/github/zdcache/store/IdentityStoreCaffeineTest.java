package io.github.zdcache.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdentityStoreCaffeineTest {

    @Test void peekReturnsStoredEntriesAndNullForMissingIds() {
        var store = new IdentityStoreCaffeine<Integer, String>(10);
        store.put(1, new IndexedEntry<>("v1", new Object[0]));

        assertEquals("v1", store.peek(1).entity());
        assertNull(store.peek(2));
        assertEquals("v1", store.get(1).entity());
    }

    @Test void clearRemovesEntriesWithoutReportingEvictions() {
        var store = new IdentityStoreCaffeine<Integer, String>(10);
        store.put(1, new IndexedEntry<>("v1", new Object[0]));
        store.put(2, new IndexedEntry<>("v2", new Object[0]));

        store.clear();

        assertEquals(0, store.size());
        assertNull(store.peek(1));
        assertTrue(store.drainEvictions().isEmpty());
        assertNull(store.mostRecentlyAccessed(value -> true));
        assertNull(store.leastRecentlyAccessed(value -> true));
    }

    @Test void mostAndLeastRecentlyAccessedReturnStoredIdsOfMatchingEntities() {
        var store = new IdentityStoreCaffeine<Integer, String>(10);
        for (int id = 1; id <= 3; id++) store.put(id, new IndexedEntry<>("v" + id, new Object[0]));
        store.settle();

        assertEquals(2, store.mostRecentlyAccessed(value -> value.equals("v2")));
        assertEquals(2, store.leastRecentlyAccessed(value -> value.equals("v2")));
        assertTrue(Set.of(1, 2, 3).contains(store.mostRecentlyAccessed(value -> true)));
        assertTrue(Set.of(1, 2, 3).contains(store.leastRecentlyAccessed(value -> true)));
        assertNull(store.mostRecentlyAccessed(value -> false));
    }

    @Test void evictedEntriesAreMarkedEvicted() {
        var store = new IdentityStoreCaffeine<Integer, String>(10);
        List<IndexedEntry<String>> entries = new ArrayList<>();
        for (int id = 0; id < 100; id++) {
            var entry = new IndexedEntry<>("v" + id, new Object[0]);
            entries.add(entry);
            store.put(id, entry);
        }
        store.settle();

        var evicted = store.drainEvictions();

        assertEquals(90, evicted.size());
        for (var event : evicted) assertTrue(event.entry().evicted());
        for (int id = 0; id < 100; id++) assertEquals(!store.contains(id), entries.get(id).evicted());
    }
}
