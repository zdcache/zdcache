package io.github.zdcache.store;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Caffeine adapter with only size-based eviction. No scheduler, loader, expiry or refresh.
 * The direct executor and cleanUp() keep maintenance on caller operations. The callback
 * only enqueues evictions; it never touches a search index or acquires a TypeCache lock.
 * Caffeine uses a frequency-aware size policy, NOT strict LRU.
 */
public final class IdentityStoreCaffeine<ID, T> implements IdentityStore<ID, T> {
    private final Cache<ID, IndexedEntry<T>> cache;
    private final Queue<EvictedEntry<ID, T>> evictions = new ConcurrentLinkedQueue<>();

    public IdentityStoreCaffeine(int maximumSize) {
        if (maximumSize < 1) throw new IllegalArgumentException("maximumSize must be positive");
        this.cache = Caffeine.newBuilder()
            .maximumSize(maximumSize)
            .executor(Runnable::run)
            .<ID, IndexedEntry<T>>evictionListener((id, entry, cause) -> {
                if (id != null && entry != null) {
                    evictions.add(new EvictedEntry<>(id, entry));
                }
            })
            .build();
    }

    @Override public IndexedEntry<T> get(ID id) { return cache.getIfPresent(id); }

    @Override public IndexedEntry<T> peek(ID id) { return cache.policy().getIfPresentQuietly(id); }

    @Override public IndexedEntry<T> put(ID id, IndexedEntry<T> entry) {
        return cache.asMap().put(id, entry);
    }

    @Override public IndexedEntry<T> remove(ID id) {
        return cache.asMap().remove(id);
    }

    @Override public Map<ID, IndexedEntry<T>> snapshot() {
        // asMap() iteration is not access-order promoting.
        return new LinkedHashMap<>(cache.asMap());
    }

    @Override public boolean contains(ID id) { return cache.asMap().containsKey(id); }

    @Override public int size() { return cache.asMap().size(); }

    @Override public void settle() { cache.cleanUp(); }

    @Override public List<EvictedEntry<ID, T>> drainEvictions() {
        List<EvictedEntry<ID, T>> result = new ArrayList<>();
        EvictedEntry<ID, T> event;
        while ((event = evictions.poll()) != null) result.add(event);
        return result;
    }
}
