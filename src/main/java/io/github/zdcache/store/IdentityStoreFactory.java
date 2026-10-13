package io.github.zdcache.store;

/** Allows swapping implementations without changing the search engine. */
public interface IdentityStoreFactory {
    <ID, T> IdentityStore<ID, T> create(int maxEntriesPerType);

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
        };
    }

    static IdentityStoreFactory caffeine() {
        requireCaffeineOnClasspath();
        return new IdentityStoreFactory() {
            @Override public <ID, T> IdentityStore<ID, T> create(int maximumSize) {
                return new IdentityStoreCaffeine<>(maximumSize);
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
