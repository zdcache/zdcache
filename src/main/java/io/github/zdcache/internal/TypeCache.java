package io.github.zdcache.internal;

import io.github.zdcache.ConflictingSearchSpecificationException;
import io.github.zdcache.IdentityDimension;
import io.github.zdcache.ImmutableIdentityDimensionException;
import io.github.zdcache.MissingSearchSpecificationStrategyException;
import io.github.zdcache.SearchSpecification;
import io.github.zdcache.store.IdentityStore;
import io.github.zdcache.store.IndexedEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/** One capacity, lock, specification, store and dimension registry per root class. */
public final class TypeCache<T, ID> {
    private final Lock read;
    private final Lock write;
    private final IdentityStore<ID, T> store;
    private final Class<T> type;
    private final boolean entryPerId;
    private final boolean nonBlockingSearches;
    private volatile long version;
    private volatile SearchSpecification<T, ID> specification;
    private volatile SearchIndexes<T, ID> indexes;

    public TypeCache(SearchSpecification<T, ID> spec, IdentityStore<ID, T> store, ReentrantReadWriteLock lock,
                     boolean entryPerId, boolean nonBlockingSearches) {
        this.entryPerId = entryPerId;
        this.nonBlockingSearches = nonBlockingSearches;
        this.read = lock.readLock();
        this.write = lock.writeLock();
        this.specification = Objects.requireNonNull(spec);
        this.type = spec.type();
        this.store = Objects.requireNonNull(store);
        this.indexes = new SearchIndexes<>(spec, nonBlockingSearches);
    }

    public SearchSpecification<T, ID> specification() {
        return specification;
    }

    public void requireSameSpecification(SearchSpecification<T, ID> supplied) {
        if (supplied != specification) {
            throw new ConflictingSearchSpecificationException(specification.type());
        }
    }

    /** Can be called without supplied spec after registration; explicit IDs are checked if extractable. */
    public void put(ID id, T entity, SearchSpecification<T, ID> suppliedOrNull) {
        if (store.concurrentWrites() && !specification.searchable()) {
            read.lock();
            try {
                if (!specification.searchable()) {
                    store(id, entity, suppliedOrNull);
                    return;
                }
            } finally { read.unlock(); }
        }
        write.lock();
        try {
            store(id, entity, suppliedOrNull);
        } finally {
            publishAndUnlock();
        }
    }

    private void publishAndUnlock() {
        try {
            indexes.publish();
        } finally {
            write.unlock();
        }
    }

    private void store(ID id, T entity, SearchSpecification<T, ID> suppliedOrNull) {
        if (suppliedOrNull != null) requireSameSpecification(suppliedOrNull);
        if (!specification.type().isInstance(entity)) {
            throw new IllegalArgumentException("Entity does not match " + specification.type());
        }
        IdentityDimension<T, ID> identity = specification.identity();
        if (!identity.isExplicit() && !identity.extract(entity).equals(id)) {
            throw new IllegalArgumentException("Supplied ID differs from the immutable IdentityDimension");
        }
        // Preparation MUST happen before touching existing state; an extractor failure is safe.
        boolean searchable = specification.searchable();
        Object[] next = indexes.current().extract(entity);
        indexes.current().validate(next);
        applyEvictions();

        if (entryPerId && searchable && updateInPlace(id, entity, next)) return;
        IndexedEntry<T> entry = new IndexedEntry<>(entity, next);
        IndexedEntry<T> previous = store.put(id, entry);
        applyEvictions();
        boolean kept = store.contains(id);
        if (!searchable) return;
        if (previous != null && kept) {
            indexes.replace(previous, previous.snapshot(), entry, next);
        } else {
            if (previous != null) indexes.remove(previous, previous.snapshot());
            if (kept) indexes.add(entry, next);
        }
    }

    private boolean updateInPlace(ID id, T entity, Object[] next) {
        IndexedEntry<T> current = store.peek(id);
        if (current == null || current.evicted()) return false;
        indexes.update(current, entity, next);
        IndexedEntry<T> previous = store.put(id, current);
        if (previous != null && previous != current) indexes.remove(previous, previous.snapshot());
        applyEvictions();
        return true;
    }

    public void beginExclusive() {
        write.lock();
        version++;
    }

    public void endExclusive() {
        version++;
        write.unlock();
    }

    public T get(ID id, Class<?> view) {
        if (store.concurrent()) {
            long before = version;
            if ((before & 1) == 0) {
                T found = read(id, view);
                if (version == before) return found;
            }
            read.lock();
            try {
                return read(id, view);
            } finally { read.unlock(); }
        }
        write.lock();
        try {
            return read(id, view);
        } finally { write.unlock(); }
    }

    private T read(ID id, Class<?> view) {
        if (view == type) {
            IndexedEntry<T> entry = store.get(id);
            return entry == null ? null : entry.entity();
        }
        IndexedEntry<T> found = store.peek(id);
        if (found == null || !view.isInstance(found.entity())) return null;
        IndexedEntry<T> entry = store.get(id);
        T entity = entry == null ? null : entry.entity();
        return view.isInstance(entity) ? entity : null;
    }

    public T peek(ID id, Class<?> view) {
        read.lock();
        try {
            IndexedEntry<T> entry = store.peek(id);
            return entry == null || !view.isInstance(entry.entity()) ? null : entry.entity();
        } finally { read.unlock(); }
    }

    public <S> List<S> values(Class<S> view) {
        read.lock();
        try {
            List<S> found = new ArrayList<>();
            for (IndexedEntry<T> entry : store.snapshot().values()) {
                if (view.isInstance(entry.entity())) found.add(view.cast(entry.entity()));
            }
            return Collections.unmodifiableList(found);
        } finally { read.unlock(); }
    }

    public void clear() {
        clear(specification.type());
    }

    public void clear(Class<?> view) {
        write.lock();
        try {
            if (view.isAssignableFrom(specification.type())) {
                store.clear();
                indexes = new SearchIndexes<>(specification, nonBlockingSearches);
                return;
            }
            applyEvictions();
            for (var pair : store.snapshot().entrySet()) {
                if (view.isInstance(pair.getValue().entity())) {
                    store.remove(pair.getKey());
                    indexes.remove(pair.getValue(), pair.getValue().snapshot());
                }
            }
        } finally { publishAndUnlock(); }
    }

    public ID mostRecentlyAccessed(Class<?> view) {
        return settled(() -> store.mostRecentlyAccessed(view::isInstance));
    }

    public ID leastRecentlyAccessed(Class<?> view) {
        return settled(() -> store.leastRecentlyAccessed(view::isInstance));
    }

    private ID settled(Supplier<ID> query) {
        write.lock();
        try {
            store.settle();
            applyEvictions();
            return query.get();
        } finally { publishAndUnlock(); }
    }

    public T remove(ID id, Class<?> view) {
        write.lock();
        try {
            applyEvictions();
            IndexedEntry<T> found = store.peek(id);
            if (found == null || !view.isInstance(found.entity())) return null;
            IndexedEntry<T> entry = store.remove(id);
            if (entry != null) indexes.remove(entry, entry.snapshot());
            applyEvictions();
            return entry == null ? null : entry.entity();
        } finally { publishAndUnlock(); }
    }

    public <S> List<S> search(Class<S> view, String dimension, Object... values) {
        if (nonBlockingSearches) {
            long before = version;
            if ((before & 1) == 0) {
                requireSearchable();
                SearchIndexes<T, ID> current = indexes;
                List<S> found = current.read((copy, side) -> collect(current, copy, side, view, dimension, values));
                if (version == before) return found;
            }
        }
        read.lock();
        try {
            requireSearchable();
            SearchIndexes<T, ID> current = indexes;
            return collect(current, current.current(), current.activeSide(), view, dimension, values);
        } finally { read.unlock(); }
    }

    private void requireSearchable() {
        if (!specification.searchable()) {
            throw new MissingSearchSpecificationStrategyException(specification.type());
        }
    }

    private <S> List<S> collect(SearchIndexes<T, ID> copies, DimensionRegistry<T, ID> registry, int side, Class<S> view,
                                String dimension, Object[] values) {
        Set<IndexedEntry<T>> seen = registry.multiValued(dimension)
            ? Collections.newSetFromMap(new IdentityHashMap<>()) : null;
        List<S> found = new ArrayList<>();
        registry.forEachMatch(dimension, values, entry -> {
            if (entry.evicted() || seen != null && !seen.add(entry)) return;
            T entity = copies.entity(entry, side);
            if (view.isInstance(entity)) found.add(view.cast(entity));
        });
        return Collections.unmodifiableList(found);
    }

    /** Rebuild all dimensions atomically. Never replace the registered identity instance. */
    public void updateSpecification(SearchSpecification<T, ID> nextSpecification) {
        write.lock();
        try {
            if (nextSpecification.identity() != specification.identity()) {
                throw new ImmutableIdentityDimensionException(specification.type());
            }
            store.settle();
            applyEvictions();
            DimensionRegistry<T, ID> rebuilt = new DimensionRegistry<>(nextSpecification);
            DimensionRegistry<T, ID> copy = nonBlockingSearches ? new DimensionRegistry<>(nextSpecification) : null;
            Map<ID, Object[]> snapshots = new HashMap<>();
            Map<ID, IndexedEntry<T>> entries = store.snapshot();
            for (var pair : entries.entrySet()) {
                Object[] newSnapshot = rebuilt.extract(pair.getValue().entity());
                rebuilt.validate(newSnapshot);
                rebuilt.add(pair.getValue(), newSnapshot);
                snapshots.put(pair.getKey(), newSnapshot);
            }
            // All potentially throwing extraction/indexing has succeeded; publish under type lock.
            entries.forEach((id, entry) -> entry.replaceSnapshot(snapshots.get(id)));
            if (copy != null) entries.forEach((id, entry) -> copy.add(entry, snapshots.get(id)));
            indexes = new SearchIndexes<>(rebuilt, copy);
            specification = nextSpecification;
        } finally { write.unlock(); }
    }

    public int size() {
        write.lock();
        try {
            store.settle();
            applyEvictions();
            return store.size();
        } finally { publishAndUnlock(); }
    }

    private void applyEvictions() {
        if (!specification.searchable()) {
            store.drainEvictions();
            return;
        }
        for (var evicted : store.drainEvictions()) {
            indexes.remove(evicted.entry(), evicted.entry().snapshot());
        }
    }
}
