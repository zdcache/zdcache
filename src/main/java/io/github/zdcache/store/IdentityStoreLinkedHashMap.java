package io.github.zdcache.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Strict, access-order LRU; external synchronization is mandatory. */
public final class IdentityStoreLinkedHashMap<ID, T> implements IdentityStore<ID, T> {
    private final int maximumSize;
    private final LinkedHashMap<ID, IndexedEntry<T>> entries = new LinkedHashMap<>();
    private final List<EvictedEntry<ID, T>> evictions = new ArrayList<>();

    public IdentityStoreLinkedHashMap(int maximumSize) {
        if (maximumSize < 1) throw new IllegalArgumentException("maximumSize must be positive");
        this.maximumSize = maximumSize;
    }

    @Override public IndexedEntry<T> get(ID id) {
        IndexedEntry<T> entry = entries.get(id);
        if (entry != null) entries.putLast(id, entry);
        return entry;
    }

    @Override public IndexedEntry<T> peek(ID id) { return entries.get(id); }

    @Override public IndexedEntry<T> put(ID id, IndexedEntry<T> entry) {
        IndexedEntry<T> previous = entries.putLast(id, entry);
        while (entries.size() > maximumSize) {
            var eldest = entries.entrySet().iterator();
            var victim = eldest.next();
            victim.getValue().markEvicted();
            evictions.add(new EvictedEntry<>(victim.getKey(), victim.getValue()));
            eldest.remove();
        }
        return previous;
    }

    @Override public IndexedEntry<T> remove(ID id) { return entries.remove(id); }

    @Override public Map<ID, IndexedEntry<T>> snapshot() {
        // Iteration does NOT change recency.
        return new LinkedHashMap<>(entries);
    }

    @Override public boolean contains(ID id) { return entries.containsKey(id); }

    @Override public int size() { return entries.size(); }

    @Override public void clear() {
        entries.clear();
        evictions.clear();
    }

    @Override public ID mostRecentlyAccessed(Predicate<? super T> matching) {
        return first(entries.sequencedEntrySet().reversed(), matching);
    }

    @Override public ID leastRecentlyAccessed(Predicate<? super T> matching) {
        return first(entries.sequencedEntrySet(), matching);
    }

    private static <ID, T> ID first(Iterable<Map.Entry<ID, IndexedEntry<T>>> ordered, Predicate<? super T> matching) {
        for (var entry : ordered) {
            if (matching.test(entry.getValue().entity())) return entry.getKey();
        }
        return null;
    }

    @Override public void settle() { /* synchronous eviction */ }

    @Override public List<EvictedEntry<ID, T>> drainEvictions() {
        List<EvictedEntry<ID, T>> events = List.copyOf(evictions);
        evictions.clear();
        return events;
    }
}
