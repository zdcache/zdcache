package io.github.zdcache.store;

import io.github.zdcache.Recency;
import java.util.Set;

/** Allows swapping implementations without changing the search engine. */
public interface IdentityStoreFactory {
    /** Creates the store for one type, holding at most the given number of entries. */
    <ID, T> IdentityStore<ID, T> create(int maxEntriesPerType);

    /** The recencies this store supports. */
    default Set<Recency> recencies() {
        return Set.of(Recency.STRICT);
    }

    /** Creates the store with the given recency, refusing one it does not support. */
    default <ID, T> IdentityStore<ID, T> create(int maxEntriesPerType, Recency recency) {
        if (!recencies().contains(recency)) {
            throw new IllegalArgumentException("This identity store supports " + recencies() + " recency, not " + recency);
        }
        return create(maxEntriesPerType);
    }

    /** Strict LRU with the least memory per entry, for a cache used by one thread. */
    static IdentityStoreFactory linkedHashMap() {
        return new IdentityStoreFactory() {
            @Override public <ID, T> IdentityStore<ID, T> create(int maximumSize) {
                return new IdentityStoreLinkedHashMap<>(maximumSize);
            }
        };
    }

    /** Strict or approximate LRU with a lock-free {@code get}; the default. */
    static IdentityStoreFactory bufferedLru() {
        return new IdentityStoreFactory() {
            @Override public <ID, T> IdentityStore<ID, T> create(int maximumSize) {
                return new IdentityStoreBufferedLru<>(maximumSize);
            }

            @Override public Set<Recency> recencies() {
                return Set.of(Recency.STRICT, Recency.APPROXIMATE);
            }

            @Override public <ID, T> IdentityStore<ID, T> create(int maximumSize, Recency recency) {
                return new IdentityStoreBufferedLru<>(maximumSize, recency);
            }
        };
    }

    /** Caffeine's frequency-aware eviction instead of LRU, approximate recency only; needs Caffeine on the classpath. */
    static IdentityStoreFactory caffeine() {
        requireCaffeineOnClasspath();
        return new IdentityStoreFactory() {
            @Override public <ID, T> IdentityStore<ID, T> create(int maximumSize) {
                return new IdentityStoreCaffeine<>(maximumSize);
            }

            @Override public Set<Recency> recencies() {
                return Set.of(Recency.APPROXIMATE);
            }
        };
    }

    private static void requireCaffeineOnClasspath() {
        try {
            Class.forName("com.github.benmanes.caffeine.cache.Caffeine", false,
                IdentityStoreFactory.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                "IdentityStoreFactory.caffeine() requires com.github.ben-manes.caffeine:caffeine on the classpath", e);
        }
    }
}
