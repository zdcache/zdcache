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
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Lock read = lock.readLock();
    private final Lock write = lock.writeLock();
    private final IdentityStore<ID, T> store;
    private volatile SearchSpecification<T, ID> specification;
    private volatile DimensionRegistry<T, ID> indexes;

    public TypeCache(SearchSpecification<T, ID> spec, IdentityStore<ID, T> store) {
        this.specification = Objects.requireNonNull(spec);
        this.store = Objects.requireNonNull(store);
        this.indexes = new DimensionRegistry<>(spec);
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
        write.lock();
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
            Object[] next = indexes.extract(entity);
            indexes.validate(next);
            applyEvictions();

            IndexedEntry<T> entry = new IndexedEntry<>(entity, next);
            IndexedEntry<T> previous = store.put(id, entry);
            if (previous != null) indexes.remove(previous, previous.snapshot());
            applyEvictions();
            if (store.contains(id)) {
                indexes.add(entry, next);
            }
        } finally {
            write.unlock();
        }
    }

    public T get(ID id, Class<?> view) {
        if (store.concurrent()) return read(id, view);
        write.lock();
        try {
            return read(id, view);
        } finally { write.unlock(); }
    }

    private T read(ID id, Class<?> view) {
        if (view != specification.type()) {
            IndexedEntry<T> found = store.peek(id);
            if (found == null || !view.isInstance(found.entity())) return null;
        }
        IndexedEntry<T> entry = store.get(id);
        return entry == null || !view.isInstance(entry.entity()) ? null : entry.entity();
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
                indexes = new DimensionRegistry<>(specification);
                return;
            }
            applyEvictions();
            for (var pair : store.snapshot().entrySet()) {
                if (view.isInstance(pair.getValue().entity())) {
                    store.remove(pair.getKey());
                    indexes.remove(pair.getValue(), pair.getValue().snapshot());
                }
            }
        } finally { write.unlock(); }
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
        } finally { write.unlock(); }
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
        } finally { write.unlock(); }
    }

    public <S> List<S> search(Class<S> view, String dimension, Object... values) {
        read.lock();
        try {
            if (!specification.searchable()) {
                throw new MissingSearchSpecificationStrategyException(specification.type());
            }
            DimensionRegistry<T, ID> registry = indexes;
            Set<IndexedEntry<T>> seen = registry.multiValued(dimension)
                ? Collections.newSetFromMap(new IdentityHashMap<>()) : null;
            List<S> found = new ArrayList<>();
            registry.forEachMatch(dimension, values, entry -> {
                if (entry.evicted() || seen != null && !seen.add(entry)) return;
                T entity = entry.entity();
                if (view.isInstance(entity)) found.add(view.cast(entity));
            });
            return Collections.unmodifiableList(found);
        } finally { read.unlock(); }
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
            indexes = rebuilt;
            specification = nextSpecification;
        } finally { write.unlock(); }
    }

    public int size() {
        write.lock();
        try {
            store.settle();
            applyEvictions();
            return store.size();
        } finally { write.unlock(); }
    }

    private void applyEvictions() {
        for (var evicted : store.drainEvictions()) {
            indexes.remove(evicted.entry(), evicted.entry().snapshot());
        }
    }
}
