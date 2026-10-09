package io.github.zdcache.store;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdentityStoreCaffeineTest {

    @Test void peekReturnsStoredEntriesAndNullForMissingIds() {
        var store = new IdentityStoreCaffeine<Integer, String>(10);
        store.put(1, new IndexedEntry<>("v1", Map.of()));

        assertEquals("v1", store.peek(1).entity());
        assertNull(store.peek(2));
        assertEquals("v1", store.get(1).entity());
    }
}
