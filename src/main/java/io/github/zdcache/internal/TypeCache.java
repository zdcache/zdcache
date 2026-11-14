package io.github.zdcache.internal;

import io.github.zdcache.ConflictingSearchSpecificationException;
import io.github.zdcache.IdentityDimension;
import io.github.zdcache.ImmutableIdentityDimensionException;
import io.github.zdcache.Match;
import io.github.zdcache.MissingSearchSpecificationStrategyException;
import io.github.zdcache.PreparedSpecification;
import io.github.zdcache.RankLevel;
import io.github.zdcache.Ranked;
import io.github.zdcache.RankedPage;
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
    private volatile long version;
    private volatile SearchSpecification<T, ID> specification;
    private volatile DimensionRegistry<T, ID> indexes;
    private Preparation pending;

    public TypeCache(SearchSpecification<T, ID> spec, IdentityStore<ID, T> store, ReentrantReadWriteLock lock,
                     boolean entryPerId) {
        this.entryPerId = entryPerId;
        this.read = lock.readLock();
        this.write = lock.writeLock();
        this.specification = Objects.requireNonNull(spec);
        this.type = spec.type();
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
        Object[] next = indexes.extract(entity);
        indexes.validate(next);
        applyEvictions();

        if (entryPerId && specification.searchable() && updateInPlace(id, entity, next)) return;
        IndexedEntry<T> entry = new IndexedEntry<>(entity, next);
        IndexedEntry<T> previous = store.put(id, entry);
        applyEvictions();
        boolean kept = store.contains(id);
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
        indexes.replace(current, current.snapshot(), current, next);
        current.update(entity, next);
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
                if (pending != null) {
                    pending.abandoned = "This specification change was abandoned because the cache was cleared";
                    pending = null;
                }
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
            return searching(view, dimension, values);
        } catch (io.github.zdcache.UnknownSearchDimensionException | io.github.zdcache.UndeclaredSearchStrategyException e) {
            throw notReadyOr(e, dimension, values);
        } finally { read.unlock(); }
    }

    private RuntimeException notReadyOr(RuntimeException e, String dimension, Object[] values) {
        Preparation preparing = pending;
        return preparing != null && preparing.next.builds(dimension, values)
            ? new io.github.zdcache.SearchDimensionNotReadyException(specification.type(), dimension) : e;
    }

    private <S> List<S> searching(Class<S> view, String dimension, Object... values) {
            if (!specification.searchable()) {
                throw new MissingSearchSpecificationStrategyException(specification.type());
            }
            DimensionRegistry<T, ID> registry = indexes;
            if (values != null && values.length == 1 && (values[0] instanceof Match.Words || values[0] instanceof Match.Fuzzy || values[0] instanceof Match.Contains)) {
                List<S> found = new ArrayList<>();
                for (Ranked<S> ranked : ranked(registry, view, dimension, Integer.MAX_VALUE, values).results()) found.add(ranked.result());
                return Collections.unmodifiableList(found);
            }
            java.util.BitSet seen = registry.multiValued(dimension) ? new java.util.BitSet(registry.ordinalBound()) : null;
            List<S> found = new ArrayList<>();
            registry.forEachMatch(dimension, values, entry -> {
                if (entry.evicted() || seen != null && seenBefore(seen, entry)) return;
                T entity = entry.entity();
                if (view.isInstance(entity)) found.add(view.cast(entity));
            });
            return Collections.unmodifiableList(found);
    }

    public <S> List<Ranked<S>> searchRanked(Class<S> view, String dimension, Object... values) {
        read.lock();
        try {
            if (!specification.searchable()) {
                throw new MissingSearchSpecificationStrategyException(specification.type());
            }
            return ranked(indexes, view, dimension, Integer.MAX_VALUE, values).results();
        } catch (io.github.zdcache.UnknownSearchDimensionException | io.github.zdcache.UndeclaredSearchStrategyException e) {
            throw notReadyOr(e, dimension, values);
        } finally { read.unlock(); }
    }

    public <S> RankedPage<S> searchBest(Class<S> view, String dimension, int limit, Object... values) {
        if (limit < 1) throw new IllegalArgumentException("A search for the best results needs a limit of at least 1, not " + limit);
        read.lock();
        try {
            if (!specification.searchable()) {
                throw new MissingSearchSpecificationStrategyException(specification.type());
            }
            return ranked(indexes, view, dimension, limit, values);
        } catch (io.github.zdcache.UnknownSearchDimensionException | io.github.zdcache.UndeclaredSearchStrategyException e) {
            throw notReadyOr(e, dimension, values);
        } finally { read.unlock(); }
    }

    private static boolean seenBefore(java.util.BitSet seen, IndexedEntry<?> entry) {
        int ordinal = entry.ordinal();
        if (seen.get(ordinal)) return true;
        seen.set(ordinal);
        return false;
    }

    @SuppressWarnings("unchecked")
    private <S> RankedPage<S> ranked(DimensionRegistry<T, ID> registry, Class<S> view, String dimension, int limit, Object... values) {
        boolean checksItsDimension = values != null && values.length == 1 && (values[0] instanceof Match.Words || values[0] instanceof Match.Fuzzy || values[0] instanceof Match.Contains);
        if (!checksItsDimension) registry.requireRankable(dimension);
        java.util.BitSet seen = new java.util.BitSet(registry.ordinalBound());
        RankLevel[] levels = RankLevel.values();
        List<Ranked<S>>[] byLevel = new List[levels.length];
        int[] total = { 0 };
        int[] kept = new int[levels.length];
        List<Ranker<T>> ranker = new ArrayList<>(1);
        registry.forEachMatch(dimension, values, entry -> {
            if (entry.evicted() || seenBefore(seen, entry)) return;
            T entity = entry.entity();
            if (!view.isInstance(entity)) return;
            if (ranker.isEmpty()) ranker.add(registry.ranker(dimension, values));
            RankLevel level = ranker.getFirst().rank(entity);
            total[0]++;
            int better = 0;
            for (int i = 0; i <= level.ordinal(); i++) better += kept[i];
            if (better >= limit) return;
            if (byLevel[level.ordinal()] == null) byLevel[level.ordinal()] = new ArrayList<>();
            byLevel[level.ordinal()].add(new Ranked<>(view.cast(entity), level));
            kept[level.ordinal()]++;
        });
        List<Ranked<S>> best = new ArrayList<>(Math.min(limit, total[0]));
        for (List<Ranked<S>> level : byLevel) {
            if (level == null) continue;
            for (Ranked<S> ranked : level) {
                if (best.size() == limit) break;
                best.add(ranked);
            }
        }
        return new RankedPage<>(best, total[0]);
    }

    /** Rebuild all dimensions atomically. Never replace the registered identity instance. */
    public void updateSpecification(SearchSpecification<T, ID> nextSpecification) {
        prepare(nextSpecification).commit();
    }

    public PreparedSpecification prepare(SearchSpecification<T, ID> nextSpecification) {
        Preparation preparation;
        Map<ID, IndexedEntry<T>> entries;
        write.lock();
        try {
            if (nextSpecification.identity() != specification.identity()) {
                throw new ImmutableIdentityDimensionException(specification.type());
            }
            if (pending != null) throw new IllegalStateException("A specification change for " + specification.type().getName() + " is already being prepared");
            store.settle();
            applyEvictions();
            preparation = new Preparation(new DimensionRegistry<>(indexes, nextSpecification), nextSpecification);
            entries = store.snapshot();
            pending = preparation;
            indexes.record(preparation.changes::add);
        } finally { write.unlock(); }
        try {
            for (IndexedEntry<T> entry : entries.values()) preparation.build(entry, entry.snapshot());
        } catch (RuntimeException e) {
            preparation.abandon();
            throw e;
        }
        write.lock();
        try { preparation.built = true; } finally { write.unlock(); }
        return preparation;
    }

    private final class Preparation implements PreparedSpecification {
        private final DimensionRegistry<T, ID> next;
        private final SearchSpecification<T, ID> nextSpecification;
        private final Map<IndexedEntry<T>, Object[]> snapshots = new IdentityHashMap<>();
        private final java.util.Queue<DimensionRegistry.Change<T>> changes = new java.util.concurrent.ConcurrentLinkedQueue<>();
        private boolean built;
        private String abandoned;

        private Preparation(DimensionRegistry<T, ID> next, SearchSpecification<T, ID> nextSpecification) {
            this.next = next;
            this.nextSpecification = nextSpecification;
        }

        private void build(IndexedEntry<T> entry, Object[] previous) {
            Object[] snapshot = next.carry(entry, previous);
            next.addFresh(entry, snapshot);
            snapshots.put(entry, snapshot);
        }

        @Override public void commit() {
            write.lock();
            try {
                if (abandoned != null) throw new IllegalStateException(abandoned);
                if (pending != this || !built) throw new IllegalStateException("This specification change is not being prepared");
                store.settle();
                applyEvictions();
                indexes.record(null);
                pending = null;
                abandoned = "This specification change is already committed";
                for (DimensionRegistry.Change<T> change : changes) {
                    if (change.added()) {
                        build(change.entry(), change.snapshot());
                    } else {
                        Object[] snapshot = snapshots.remove(change.entry());
                        next.removeFresh(change.entry(), snapshot != null ? snapshot : next.carry(change.entry(), change.snapshot()));
                    }
                }
                Set<IndexedEntry<T>> live = Collections.newSetFromMap(new IdentityHashMap<>());
                live.addAll(store.snapshot().values());
                for (var stale = snapshots.entrySet().iterator(); stale.hasNext(); ) {
                    var built = stale.next();
                    if (live.contains(built.getKey())) continue;
                    next.removeFresh(built.getKey(), built.getValue());
                    stale.remove();
                }
                for (IndexedEntry<T> entry : live) if (!snapshots.containsKey(entry)) build(entry, entry.snapshot());
                for (IndexedEntry<T> entry : live) entry.replaceSnapshot(snapshots.get(entry));
                indexes = next;
                specification = nextSpecification;
            } catch (RuntimeException e) {
                abandoned = "This specification change failed: " + e.getMessage();
                throw e;
            } finally { write.unlock(); }
        }

        @Override public void abandon() {
            write.lock();
            try {
                if (pending == this) {
                    indexes.record(null);
                    pending = null;
                }
                if (abandoned == null) abandoned = "This specification change was abandoned";
            } finally { write.unlock(); }
        }
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
