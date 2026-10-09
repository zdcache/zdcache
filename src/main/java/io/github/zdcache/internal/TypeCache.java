package io.github.zdcache.internal;

import io.github.zdcache.ConflictingSearchSpecificationException;
import io.github.zdcache.IdentityDimension;
import io.github.zdcache.ImmutableIdentityDimensionException;
import io.github.zdcache.MissingSearchSpecificationStrategyException;
import io.github.zdcache.SearchSpecification;
import io.github.zdcache.store.IdentityStore;
import io.github.zdcache.store.IndexedEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/** One capacity, lock, specification, store and dimension registry per root class. */
public final class TypeCache<T, ID> {
    private final ReentrantLock lock = new ReentrantLock();
    private final IdentityStore<ID, T> store;
    private SearchSpecification<T, ID> specification;
    private DimensionRegistry<T, ID> indexes;

    public TypeCache(SearchSpecification<T, ID> spec, IdentityStore<ID, T> store) {
        this.specification = Objects.requireNonNull(spec);
        this.store = Objects.requireNonNull(store);
        this.indexes = new DimensionRegistry<>(spec);
    }

    public SearchSpecification<T, ID> specification() {
        lock.lock();
        try { return specification; }
        finally { lock.unlock(); }
    }

    public void requireSameSpecification(SearchSpecification<T, ID> supplied) {
        if (supplied != specification) {
            throw new ConflictingSearchSpecificationException(specification.type());
        }
    }

    /** Can be called without supplied spec after registration; explicit IDs are checked if extractable. */
    public void put(ID id, T entity, SearchSpecification<T, ID> suppliedOrNull) {
        lock.lock();
        try {
            if (suppliedOrNull != null) requireSameSpecification(suppliedOrNull);
            if (!specification.type().isInstance(entity)) {
                throw new IllegalArgumentException("Entity does not match " + specification.type());
            }
            IdentityDimension<T, ID> identity = specification.identity();
            if (!identity.isExplicit() && !identity.extract(entity).equals(id)) {
                throw new IllegalArgumentException("Supplied ID differs from the immutable IdentityDimension");
            }
            // Preparation MUST happen before touching existing state; an extractor failure is safe.
            Map<String, Set<Object>> next = indexes.extract(entity);
            indexes.validate(next);
            settleAndCleanup();

            IndexedEntry<T> replacement = new IndexedEntry<>(entity, next);
            IndexedEntry<T> previous = store.put(id, replacement);
            store.settle();
            boolean retained = store.contains(id);

            if (previous != null) indexes.remove(id, previous.snapshot());
            for (var evicted : store.drainEvictions()) {
                // Remove the EXACT evicted entry snapshot. In particular, don't delete a replacement.
                indexes.remove(evicted.id(), evicted.entry().snapshot());
            }
            if (retained) {
                indexes.add(id, next);
            }
        } finally {
            lock.unlock();
        }
    }

    public T get(ID id, Class<?> view) {
        lock.lock();
        try {
            settleAndCleanup();
            IndexedEntry<T> found = store.peek(id);
            IndexedEntry<T> entry = found != null && view.isInstance(found.entity()) ? store.get(id) : null;
            settleAndCleanup();
            return entry == null ? null : entry.entity();
        } finally { lock.unlock(); }
    }

    public T remove(ID id, Class<?> view) {
        lock.lock();
        try {
            settleAndCleanup();
            IndexedEntry<T> found = store.peek(id);
            if (found == null || !view.isInstance(found.entity())) return null;
            IndexedEntry<T> entry = store.remove(id);
            if (entry != null) indexes.remove(id, entry.snapshot());
            settleAndCleanup();
            return entry == null ? null : entry.entity();
        } finally { lock.unlock(); }
    }

    public List<T> search(String dimension, Object... values) {
        lock.lock();
        try {
            settleAndCleanup();
            if (!specification.searchable()) {
                throw new MissingSearchSpecificationStrategyException(specification.type());
            }
            Set<ID> ids = indexes.search(dimension, values);
            Map<ID, IndexedEntry<T>> candidates = new java.util.LinkedHashMap<>();
            for (ID id : ids) {
                IndexedEntry<T> entry = store.peek(id);
                if (entry != null) candidates.put(id, entry);
            }
            settleAndCleanup();
            // Avoid returning a candidate evicted during Caffeine's maintenance pass.
            List<T> objects = new ArrayList<>(candidates.size());
            for (var candidate : candidates.entrySet()) {
                if (store.contains(candidate.getKey())) {
                    objects.add(candidate.getValue().entity());
                }
            }
            return List.copyOf(objects);
        } finally { lock.unlock(); }
    }

    /** Rebuild all dimensions atomically. Never replace the registered identity instance. */
    public void updateSpecification(SearchSpecification<T, ID> nextSpecification) {
        lock.lock();
        try {
            if (nextSpecification.identity() != specification.identity()) {
                throw new ImmutableIdentityDimensionException(specification.type());
            }
            settleAndCleanup();
            DimensionRegistry<T, ID> rebuilt = new DimensionRegistry<>(nextSpecification);
            Map<ID, Map<String, Set<Object>>> snapshots = new HashMap<>();
            Map<ID, IndexedEntry<T>> entries = store.snapshot();
            for (var pair : entries.entrySet()) {
                Map<String, Set<Object>> newSnapshot = rebuilt.extract(pair.getValue().entity());
                rebuilt.validate(newSnapshot);
                rebuilt.add(pair.getKey(), newSnapshot);
                snapshots.put(pair.getKey(), newSnapshot);
            }
            // All potentially throwing extraction/indexing has succeeded; publish under type lock.
            entries.forEach((id, entry) -> entry.replaceSnapshot(snapshots.get(id)));
            indexes = rebuilt;
            specification = nextSpecification;
        } finally { lock.unlock(); }
    }

    public int size() {
        lock.lock();
        try {
            settleAndCleanup();
            return store.size();
        } finally { lock.unlock(); }
    }

    private void settleAndCleanup() {
        store.settle();
        for (var evicted : store.drainEvictions()) {
            indexes.remove(evicted.id(), evicted.entry().snapshot());
        }
    }
}
