package io.github.zdcache;

import io.github.zdcache.store.IdentityStoreFactory;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecencyConfigurationTest {
    record Book(long id, String title) {}

    static final SearchSpecification<Book, Long> SPEC =
        SearchSpecification.builder(IdentityDimension.of(Book.class, "id", Book::id)).prefix(Book::title).build();

    private static ZDCache threeBooks(ZDCache.Builder builder) {
        var cache = builder.defaultMaxPerType(3).build();
        cache.register(SPEC);
        for (long id = 1; id <= 3; id++) cache.put(new Book(id, "Book " + id));
        return cache;
    }

    @Test void factoriesDeclareTheRecencyTheySupport() {
        assertEquals(Set.of(Recency.STRICT, Recency.APPROXIMATE), IdentityStoreFactory.bufferedLru().recencies());
        assertEquals(Set.of(Recency.STRICT), IdentityStoreFactory.linkedHashMap().recencies());
        assertEquals(Set.of(Recency.APPROXIMATE), IdentityStoreFactory.caffeine().recencies());
    }

    @Test void unsupportedRecencyFailsWhenTheCacheIsBuilt() {
        assertThrows(IllegalArgumentException.class,
            () -> ZDCache.builder().identityStore(IdentityStoreFactory.linkedHashMap()).recency(Recency.APPROXIMATE).build());
        assertThrows(IllegalArgumentException.class,
            () -> ZDCache.builder().recency(Recency.STRICT).identityStore(IdentityStoreFactory.caffeine()).build());
        assertThrows(IllegalArgumentException.class, () -> IdentityStoreFactory.linkedHashMap().create(3, Recency.APPROXIMATE));
        assertThrows(NullPointerException.class, () -> ZDCache.builder().recency(null));
    }

    @Test void withoutARecencyEachStoreUsesItsOwn() {
        for (var stores : List.of(IdentityStoreFactory.bufferedLru(), IdentityStoreFactory.linkedHashMap(), IdentityStoreFactory.caffeine())) {
            var cache = threeBooks(ZDCache.builder().identityStore(stores));
            assertEquals(3, cache.size(Book.class));
        }
    }

    @Test void supportedRecenciesCreateWorkingCaches() {
        var strict = threeBooks(ZDCache.builder().recency(Recency.STRICT));
        var approximate = threeBooks(ZDCache.builder().recency(Recency.APPROXIMATE));
        var caffeine = threeBooks(ZDCache.builder().identityStore(IdentityStoreFactory.caffeine()).recency(Recency.APPROXIMATE));

        for (var cache : List.of(strict, approximate, caffeine)) {
            assertEquals("Book 2", cache.get(Book.class, 2L).title());
            assertEquals(3, cache.search(Book::title, "book").size());
        }
    }

    @Test void approximateRecencyKeepsWhatIsReadAndSettlesWithinCapacity() {
        var cache = threeBooks(ZDCache.builder().recency(Recency.APPROXIMATE));
        for (int i = 0; i < 1_000; i++) {
            cache.get(Book.class, 2L);
            cache.get(Book.class, 1L);
        }

        cache.put(new Book(4, "Book 4"));

        assertEquals(3, cache.size(Book.class));
        assertFalse(cache.contains(Book.class, 3L));
        assertTrue(cache.contains(Book.class, 1L));
        assertTrue(cache.contains(Book.class, 2L));
    }
}
