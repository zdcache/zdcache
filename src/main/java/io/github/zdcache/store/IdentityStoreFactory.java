package io.github.zdcache.store;

import io.github.zdcache.Recency;
import java.util.Set;

/** Allows swapping implementations without changing the search engine. */
public interface IdentityStoreFactory {
    <ID, T> IdentityStore<ID, T> create(int maxEntriesPerType);

    default Set<Recency> recencies() {
        return Set.of(Recency.STRICT);
    }

    default <ID, T> IdentityStore<ID, T> create(int maxEntriesPerType, Recency recency) {
        if (!recencies().contains(recency)) {
            throw new IllegalArgumentException("This identity store supports " + recencies() + " recency, not " + recency);
        }
        return create(maxEntriesPerType);
    }

    static IdentityStoreFactory linkedHashMap() {
        return new IdentityStoreFactory() {
            @Override public <ID, T> IdentityStore<ID, T> create(int maximumSize) {
                return new IdentityStoreLinkedHashMap<>(maximumSize);
            }
        };
    }

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
