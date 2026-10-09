package io.github.zdcache.store;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdentityStoreLinkedHashMapTest {

    private static IndexedEntry<String> entry(String value) {
        return new IndexedEntry<>(value, Map.of());
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
}
