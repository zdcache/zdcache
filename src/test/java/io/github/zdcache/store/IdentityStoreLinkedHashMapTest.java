package io.github.zdcache.store;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdentityStoreLinkedHashMapTest {

    private static IndexedEntry<String> entry(String value) {
        return new IndexedEntry<>(value, new Object[0]);
    }

    private static IdentityStoreLinkedHashMap<Integer, String> filled(int maximumSize, int... ids) {
        var store = new IdentityStoreLinkedHashMap<Integer, String>(maximumSize);
        for (int id : ids) store.put(id, entry("v" + id));
        return store;
    }

    private static List<Integer> evicted(IdentityStoreLinkedHashMap<Integer, String> store) {
        return store.drainEvictions().stream().map(EvictedEntry::id).toList();
    }

    @Test void evictsLeastRecentlyInsertedWhenNothingIsRead() {
        var store = filled(2, 1, 2);
        store.put(3, entry("v3"));
        assertEquals(List.of(1), evicted(store));
    }

    @Test void getCountsAsUse() {
        var store = filled(2, 1, 2);
        assertEquals("v1", store.get(1).entity());
        store.put(3, entry("v3"));
        assertEquals(List.of(2), evicted(store));
    }

    @Test void peekDoesNotCountAsUse() {
        var store = filled(2, 1, 2);
        assertEquals("v1", store.peek(1).entity());
        store.put(3, entry("v3"));
        assertEquals(List.of(1), evicted(store));
    }

    @Test void replacingAnEntryCountsAsUse() {
        var store = filled(2, 1, 2);
        assertEquals("v1", store.put(1, entry("new")).entity());
        store.put(3, entry("v3"));
        assertEquals(List.of(2), evicted(store));
        assertEquals("new", store.peek(1).entity());
    }

    @Test void missingIdsReturnNullWithoutSideEffects() {
        var store = filled(2, 1, 2);
        assertNull(store.get(9));
        assertNull(store.peek(9));
        assertEquals(2, store.size());
        assertEquals(List.of(1, 2), List.copyOf(store.snapshot().keySet()));
    }

    @Test void snapshotAndContainsDoNotCountAsUse() {
        var store = filled(2, 1, 2);
        store.snapshot();
        assertTrue(store.contains(1));
        store.put(3, entry("v3"));
        assertEquals(List.of(1), evicted(store));
    }

    @Test void clearRemovesEntriesAndPendingEvictions() {
        var store = filled(2, 1, 2, 3);
        store.clear();
        assertEquals(0, store.size());
        assertNull(store.peek(2));
        assertTrue(store.drainEvictions().isEmpty());
    }

    @Test void leastRecentlyAccessedIsNextToBeEvicted() {
        var store = filled(3, 1, 2, 3);
        store.get(1);
        assertEquals(1, store.mostRecentlyAccessed(value -> true));
        assertEquals(2, store.leastRecentlyAccessed(value -> true));
        store.put(4, entry("v4"));
        assertEquals(List.of(2), evicted(store));
    }

    @Test void mostAndLeastRecentlyAccessedSkipEntitiesThatDoNotMatch() {
        var store = filled(3, 1, 2, 3);
        assertEquals(2, store.mostRecentlyAccessed(value -> !value.equals("v3")));
        assertEquals(2, store.leastRecentlyAccessed(value -> !value.equals("v1")));
        assertNull(store.mostRecentlyAccessed(value -> false));
        assertNull(store.leastRecentlyAccessed(value -> false));
    }

    @Test void mostAndLeastRecentlyAccessedDoNotCountAsUse() {
        var store = filled(2, 1, 2);
        store.leastRecentlyAccessed(value -> true);
        store.mostRecentlyAccessed(value -> true);
        store.put(3, entry("v3"));
        assertEquals(List.of(1), evicted(store));
    }

    @Test void evictedEntriesAreMarkedEvicted() {
        var store = filled(2, 1, 2);
        IndexedEntry<String> first = store.peek(1);
        store.put(3, entry("v3"));

        assertTrue(first.evicted());
        assertFalse(store.peek(2).evicted());
        assertFalse(store.peek(3).evicted());
    }

    @Test void removedAndReplacedEntriesAreNotMarkedEvicted() {
        var store = filled(3, 1, 2);
        IndexedEntry<String> replaced = store.put(1, entry("new"));
        IndexedEntry<String> removed = store.remove(2);

        assertFalse(replaced.evicted());
        assertFalse(removed.evicted());
    }
}
