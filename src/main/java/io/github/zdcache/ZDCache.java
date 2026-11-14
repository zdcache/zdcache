package io.github.zdcache;

import io.github.zdcache.internal.Getters;
import io.github.zdcache.internal.TypeCache;
import io.github.zdcache.store.IdentityStoreFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * In-process, multi-type cache with per-type capacity/lock and specification-defined indexes.
 * Mutating a returned entity by reference is intentionally NOT tracked; use put() to reindex.
 *
 * <p>Build one with {@link #builder()}, register a {@link SearchSpecification} per type, then {@code put},
 * {@code get} and {@code search}. Each type is a bounded LRU: past its capacity the least recently used
 * entry is evicted, and only {@code get} counts as use. A {@link Match} chooses how text is searched, and
 * {@code searchRanked} also says how well each result matched.
 */
public final class ZDCache {
    private final Map<Class<?>, TypeCache<?, ?>> types = new ConcurrentHashMap<>();
    private final Map<Class<?>, TypeRedirect<?>> redirects = new ConcurrentHashMap<>();
    private final Map<Class<?>, TypeCache<?, ?>> resolved = new ConcurrentHashMap<>();
    private volatile Specified lastSpecified;
    private final ReentrantLock registry = new ReentrantLock();
    private volatile List<TypeCache<?, ?>> inTypeOrder = List.of();
    private final int defaultMaxPerType;
    private final Map<Class<?>, Integer> capacities;
    private final IdentityStoreFactory stores;
    private final Recency recency;
    private final Locking locking;
    private final Entries entries;
    private final ReentrantReadWriteLock sharedLock = new ReentrantReadWriteLock();
    private volatile Thread atomicOwner;

    private ZDCache(Builder builder) {
        defaultMaxPerType = builder.defaultMaxPerType;
        capacities = Map.copyOf(builder.capacities);
        stores = builder.stores;
        recency = builder.recency;
        locking = builder.locking;
        entries = builder.entries;
    }

    /** Starts a cache with the defaults: 10,000 entries per type, the buffered LRU store, strict recency, {@link Locking#PER_TYPE} and {@link Entries#PER_ID}. */
    public static Builder builder() { return new Builder(); }

    /** Configures a cache; every setting has a default suited to searched data. */
    public static final class Builder {
        private int defaultMaxPerType = 10_000;
        private final Map<Class<?>, Integer> capacities = new HashMap<>();
        private IdentityStoreFactory stores = IdentityStoreFactory.bufferedLru();
        private Recency recency;
        private Locking locking = Locking.PER_TYPE;
        private Entries entries = Entries.PER_ID;

        /** The capacity of every type without its own; past it the least recently used entry is evicted. */
        public Builder defaultMaxPerType(int maximum) {
            positive(maximum);
            defaultMaxPerType = maximum;
            return this;
        }

        /** A capacity for one type instead of the default; a type stored under another type's specification cannot have one. */
        public Builder capacity(Class<?> type, int maximum) {
            capacities.put(Objects.requireNonNull(type), positive(maximum));
            return this;
        }

        /** Where entries are kept: the buffered LRU store by default, see {@link IdentityStoreFactory} for the others. */
        public Builder identityStore(IdentityStoreFactory factory) {
            stores = Objects.requireNonNull(factory);
            return this;
        }

        /** How exactly the store keeps LRU order; {@link #build()} refuses one the store does not support. */
        public Builder recency(Recency recency) {
            this.recency = Objects.requireNonNull(recency);
            return this;
        }

        /** One lock per type, the default, or one lock shared by every type. */
        public Builder locking(Locking locking) {
            this.locking = Objects.requireNonNull(locking);
            return this;
        }

        /** One entry object per id, updated in place, the default, or a new entry for every put. */
        public Builder entries(Entries entries) {
            this.entries = Objects.requireNonNull(entries);
            return this;
        }

        /** The cache, checking that the identity store supports the chosen recency. */
        public ZDCache build() {
            if (recency != null && !stores.recencies().contains(recency)) {
                throw new IllegalArgumentException("The identity store supports " + stores.recencies()
                    + " recency, not " + recency);
            }
            return new ZDCache(this);
        }
        private static int positive(int n) {
            if (n <= 0) throw new IllegalArgumentException("Capacity must be positive");
            return n;
        }
    }

    @SuppressWarnings("unchecked")
    private <T, ID> TypeCache<T, ID> typeCache(Class<T> type) {
        return (TypeCache<T, ID>) types.get(type);
    }

    private record Specified(Class<?> type, TypeCache<?, ?> cache) {}

    @SuppressWarnings("unchecked")
    private <T, ID> TypeCache<T, ID> resolve(Class<?> type) {
        Specified last = lastSpecified;
        if (last != null && last.type() == type) return (TypeCache<T, ID>) last.cache();
        TypeCache<?, ?> cache = types.get(type);
        if (cache != null) {
            lastSpecified = new Specified(type, cache);
            return (TypeCache<T, ID>) cache;
        }
        cache = resolved.get(type);
        if (cache == null) cache = resolveAndRemember(type);
        return (TypeCache<T, ID>) cache;
    }

    private TypeCache<?, ?> resolveAndRemember(Class<?> type) {
        registry.lock();
        try {
            TypeCache<?, ?> cache = specified(type);
            if (cache == null) cache = nearestSuperclass(type);
            if (cache == null) cache = nearestInterface(type);
            if (cache != null) {
                requireNoOwnCapacity(type, cache.specification().type());
                resolved.put(type, cache);
            }
            return cache;
        } finally { registry.unlock(); }
    }

    private void requireNoOwnCapacity(Class<?> type, Class<?> servedBy) {
        if (capacities.containsKey(type)) throw new CapacityNotApplicableException(type, servedBy);
    }

    private TypeCache<?, ?> specified(Class<?> type) {
        TypeCache<?, ?> cache = types.get(type);
        if (cache != null) return cache;
        TypeRedirect<?> redirect = redirects.get(type);
        return redirect == null ? null : types.get(redirect.target().type());
    }

    private TypeCache<?, ?> nearestSuperclass(Class<?> type) {
        for (Class<?> c = type.getSuperclass(); c != null; c = c.getSuperclass()) {
            TypeCache<?, ?> cache = specified(c);
            if (cache != null) return cache;
        }
        return null;
    }

    private TypeCache<?, ?> nearestInterface(Class<?> type) {
        Set<Class<?>> level = new LinkedHashSet<>();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) level.addAll(List.of(c.getInterfaces()));
        Set<Class<?>> visited = new HashSet<>();
        while (!level.isEmpty()) {
            Map<Class<?>, TypeCache<?, ?>> found = new LinkedHashMap<>();
            Set<Class<?>> next = new LinkedHashSet<>();
            for (Class<?> candidate : level) {
                if (!visited.add(candidate)) continue;
                TypeCache<?, ?> cache = specified(candidate);
                if (cache != null) found.put(candidate, cache);
                else next.addAll(List.of(candidate.getInterfaces()));
            }
            if (new HashSet<>(found.values()).size() > 1) {
                throw new AmbiguousSearchSpecificationException(type, found.keySet());
            }
            if (!found.isEmpty()) return found.values().iterator().next();
            level = next;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private <T, ID> TypeCache<T, ID> registerCache(SearchSpecification<T, ID> specification) {
        Class<T> type = specification.type();
        TypeCache<T, ID> existing = typeCache(type);
        if (existing != null) return existing;
        registry.lock();
        try {
            if (redirects.containsKey(type)) throw new ConflictingSearchSpecificationException(type);
            TypeCache<?, ?> cache = types.get(type);
            if (cache == null) {
                int capacity = capacities.getOrDefault(type, defaultMaxPerType);
                cache = new TypeCache<>(specification,
                    recency == null ? stores.create(capacity) : stores.create(capacity, recency),
                    locking == Locking.SINGLE ? sharedLock : new ReentrantReadWriteLock(), entries == Entries.PER_ID);
                types.put(type, cache);
                List<TypeCache<?, ?>> ordered = new ArrayList<>(inTypeOrder);
                ordered.add(cache);
                inTypeOrder = List.copyOf(ordered);
                resolved.clear();
                if (atomicOwner == Thread.currentThread()) cache.beginExclusive();
            }
            return (TypeCache<T, ID>) cache;
        } finally { registry.unlock(); }
    }

    /** Runs several calls, across types, as one step: other threads see all of their changes or none. */
    public void atomically(Runnable work) {
        Objects.requireNonNull(work);
        if (atomicOwner == Thread.currentThread()) {
            work.run();
            return;
        }
        beginAtomically();
        try {
            work.run();
        } finally {
            endAtomically();
        }
    }

    /** Runs several calls, across types, as one step and returns what the work returns. */
    public <R> R atomically(Supplier<R> work) {
        Objects.requireNonNull(work);
        if (atomicOwner == Thread.currentThread()) return work.get();
        beginAtomically();
        try {
            return work.get();
        } finally {
            endAtomically();
        }
    }

    private void beginAtomically() {
        registry.lock();
        List<TypeCache<?, ?>> caches = inTypeOrder;
        int locked = 0;
        try {
            for (; locked < caches.size(); locked++) caches.get(locked).beginExclusive();
            atomicOwner = Thread.currentThread();
        } catch (Throwable failure) {
            while (locked > 0) caches.get(--locked).endExclusive();
            registry.unlock();
            throw failure;
        }
    }

    private void endAtomically() {
        atomicOwner = null;
        List<TypeCache<?, ?>> caches = inTypeOrder;
        for (int i = caches.size() - 1; i >= 0; i--) caches.get(i).endExclusive();
        registry.unlock();
    }

    /** Registers a type's specification before its first put; another instance for the same type throws. */
    public <T, ID> void register(SearchSpecification<T, ID> specification) {
        Objects.requireNonNull(specification);
        if (registerCache(specification).specification() != specification) {
            throw new ConflictingSearchSpecificationException(specification.type());
        }
    }

    /** Lets a subtype be stored and searched under its supertype's specification. */
    public <S> void register(TypeRedirect<S> redirect) {
        Objects.requireNonNull(redirect);
        registry.lock();
        try {
            Class<S> type = redirect.type();
            TypeRedirect<?> existing = redirects.get(type);
            if (types.containsKey(type) || existing != null && existing.target() != redirect.target()) {
                throw new ConflictingSearchSpecificationException(type);
            }
            requireNoOwnCapacity(type, redirect.target().type());
            register(redirect.target());
            redirects.put(type, redirect);
            resolved.clear();
        } finally { registry.unlock(); }
    }

    /** Register the supplied specification on first insert; subsequent puts must reuse its instance. */
    public <T, ID> void put(T entity, SearchSpecification<T, ID> specification) {
        Objects.requireNonNull(entity);
        Objects.requireNonNull(specification);
        if (!specification.type().isInstance(entity)) {
            throw new IllegalArgumentException("Object type differs from its specification type");
        }
        TypeCache<T, ID> cache = registerCache(specification);
        ID id = cache.specification().identity().isExplicit()
            ? null : specification.identity().extract(entity);
        if (id == null) {
            throw new IllegalArgumentException("Explicit identity requires put(Class, ID, T, specification)");
        }
        cache.put(id, entity, specification);
    }

    /** Reuse an already registered extracting identity. */
    public <T> void put(T entity) {
        Objects.requireNonNull(entity);
        TypeCache<T, Object> cache = resolve(entity.getClass());
        if (cache == null) throw new MissingIdentitySpecificationException(entity.getClass());
        cache.put(cache.specification().identity().extract(entity), entity, null);
    }

    /** Creates a default, basic key-only specification on the first insert of this type. */
    public <T, ID> void put(Class<T> type, ID id, T entity) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(id);
        Objects.requireNonNull(entity);
        if (!type.isInstance(entity)) {
            throw new IllegalArgumentException("Entity is not a " + type.getName());
        }
        TypeCache<T, ID> cache = resolve(type);
        if (cache == null) {
            cache = registerCache(SearchSpecification.basic(IdentityDimension.<T, ID>explicit(type)));
        }
        cache.put(id, entity, null);
    }

    /** Explicit-key variant that can register and enforce a caller-provided specification. */
    public <T, ID> void put(Class<T> type, ID id, T entity, SearchSpecification<T, ID> specification) {
        if (specification.type() != type) {
            throw new IllegalArgumentException("Type and specification must match");
        }
        Objects.requireNonNull(id);
        registerCache(specification).put(id, entity, specification);
    }

    /** The entity with this id, or null; counts as use for eviction. */
    public <T, ID> T get(Class<T> type, ID id) {
        TypeCache<?, ID> cache = resolve(type);
        if (cache == null) return null;
        return type.cast(cache.get(id, type));
    }

    /** Removes the entity and its index entries, returning it, or null. */
    public <T, ID> T remove(Class<T> type, ID id) {
        TypeCache<?, ID> cache = resolve(type);
        return cache == null ? null : type.cast(cache.remove(id, type));
    }

    /** The entity with this id, or null, without counting as use. */
    public <T, ID> T peek(Class<T> type, ID id) {
        TypeCache<?, ID> cache = resolve(type);
        return cache == null ? null : type.cast(cache.peek(id, type));
    }

    /** Whether an entity with this id is stored, without counting as use. */
    public <ID> boolean contains(Class<?> type, ID id) {
        return peek(type, id) != null;
    }

    /** Every stored entity of the type, without counting as use. */
    public <T> List<T> values(Class<T> type) {
        TypeCache<?, ?> cache = resolve(type);
        return cache == null ? List.of() : cache.values(type);
    }

    /** Removes every entity of the type. */
    public void clear(Class<?> type) {
        TypeCache<?, ?> cache = resolve(type);
        if (cache != null) cache.clear(type);
    }

    /** Removes every entity of every type. */
    public void clear() {
        types.values().forEach(TypeCache::clear);
    }

    /** The id that would be evicted last, or null. */
    public <ID> ID mostRecentlyAccessedId(Class<?> type) {
        TypeCache<?, ID> cache = resolve(type);
        return cache == null ? null : cache.mostRecentlyAccessed(type);
    }

    /** The id that would be evicted next, or null. */
    public <ID> ID leastRecentlyAccessedId(Class<?> type) {
        TypeCache<?, ID> cache = resolve(type);
        return cache == null ? null : cache.leastRecentlyAccessed(type);
    }

    /** The entities matching a value, a {@link ValueRange} or a {@link Match} on the dimension the method names. */
    public <T> List<T> search(Getter<T, ?> dimension, Object... values) {
        return search(Getters.<T>type(dimension), Getters.name(dimension), values);
    }

    /** The same for a dimension on the elements of a collection, named by the method references in turn. */
    public <T, A> List<T> search(Getter<T, ? extends Iterable<A>> first, Getter<A, ?> second, Object... values) {
        return search(Getters.<T>type(first), path(first, second), values);
    }

    /** The same for a dimension two collections down. */
    public <T, A, B> List<T> search(Getter<T, ? extends Iterable<A>> first, Getter<A, ? extends Iterable<B>> second,
                                    Getter<B, ?> third, Object... values) {
        return search(Getters.<T>type(first), path(first, second, third), values);
    }

    /** The same for a dimension three collections down. */
    public <T, A, B, C> List<T> search(Getter<T, ? extends Iterable<A>> first, Getter<A, ? extends Iterable<B>> second,
                                       Getter<B, ? extends Iterable<C>> third, Getter<C, ?> fourth, Object... values) {
        return search(Getters.<T>type(first), path(first, second, third, fourth), values);
    }

    /** The same for a dimension four collections down. */
    public <T, A, B, C, D> List<T> search(Getter<T, ? extends Iterable<A>> first, Getter<A, ? extends Iterable<B>> second,
                                          Getter<B, ? extends Iterable<C>> third, Getter<C, ? extends Iterable<D>> fourth,
                                          Getter<D, ?> fifth, Object... values) {
        return search(Getters.<T>type(first), path(first, second, third, fourth, fifth), values);
    }

    private static String path(Getter<?, ?>... steps) {
        StringJoiner path = new StringJoiner(".");
        for (Getter<?, ?> step : steps) path.add(Getters.name(step));
        return path.toString();
    }

    /** The entities of this type, or a subtype, matching a value, range or {@link Match}. */
    public <T> List<T> search(Class<T> type, Getter<? super T, ?> dimension, Object... values) {
        return search(type, Getters.name(dimension), values);
    }

    /** The entities matching a value, range or {@link Match} on the dimension with this path. */
    public <T> List<T> search(Class<T> type, String dimension, Object... values) {
        TypeCache<?, Object> cache = resolve(type);
        if (cache == null) throw new MissingSearchSpecificationStrategyException(type);
        return cache.search(type, dimension, values);
    }

    /** Like search on a text dimension, best first, with each result's {@link RankLevel}. */
    public <T> List<Ranked<T>> searchRanked(Getter<T, ?> dimension, Object... values) {
        return searchRanked(Getters.<T>type(dimension), Getters.name(dimension), values);
    }

    /** The same for a text dimension on the elements of a collection. */
    public <T, A> List<Ranked<T>> searchRanked(Getter<T, ? extends Iterable<A>> first, Getter<A, ?> second, Object... values) {
        return searchRanked(Getters.<T>type(first), path(first, second), values);
    }

    /** The same for a text dimension two collections down. */
    public <T, A, B> List<Ranked<T>> searchRanked(Getter<T, ? extends Iterable<A>> first, Getter<A, ? extends Iterable<B>> second,
                                                  Getter<B, ?> third, Object... values) {
        return searchRanked(Getters.<T>type(first), path(first, second, third), values);
    }

    /** The same for a text dimension three collections down. */
    public <T, A, B, C> List<Ranked<T>> searchRanked(Getter<T, ? extends Iterable<A>> first, Getter<A, ? extends Iterable<B>> second,
                                                     Getter<B, ? extends Iterable<C>> third, Getter<C, ?> fourth, Object... values) {
        return searchRanked(Getters.<T>type(first), path(first, second, third, fourth), values);
    }

    /** The same for a text dimension four collections down. */
    public <T, A, B, C, D> List<Ranked<T>> searchRanked(Getter<T, ? extends Iterable<A>> first, Getter<A, ? extends Iterable<B>> second,
                                                        Getter<B, ? extends Iterable<C>> third, Getter<C, ? extends Iterable<D>> fourth,
                                                        Getter<D, ?> fifth, Object... values) {
        return searchRanked(Getters.<T>type(first), path(first, second, third, fourth, fifth), values);
    }

    /** Ranked search over this type or a subtype. */
    public <T> List<Ranked<T>> searchRanked(Class<T> type, Getter<? super T, ?> dimension, Object... values) {
        return searchRanked(type, Getters.name(dimension), values);
    }

    /** Ranked search on the text dimension with this path. */
    public <T> List<Ranked<T>> searchRanked(Class<T> type, String dimension, Object... values) {
        TypeCache<?, Object> cache = resolve(type);
        if (cache == null) throw new MissingSearchSpecificationStrategyException(type);
        return cache.searchRanked(type, dimension, values);
    }

    /** Builds what the replacement adds while writes continue, then switches it on in one short step; the identity must be the same instance. */
    public <T, ID> void updateSpecification(Class<T> type, SearchSpecification<T, ID> replacement) {
        Objects.requireNonNull(replacement);
        if (replacement.type() != type) {
            throw new IllegalArgumentException("Type and specification must match");
        }
        TypeCache<T, ID> cache = typeCache(type);
        if (cache == null) throw new MissingIdentitySpecificationException(type);
        cache.updateSpecification(replacement);
    }

    /** Builds the replacement now and switches it on when the returned change is committed. */
    public <T, ID> PreparedSpecification prepareSpecification(Class<T> type, SearchSpecification<T, ID> replacement) {
        Objects.requireNonNull(replacement);
        if (replacement.type() != type) {
            throw new IllegalArgumentException("Type and specification must match");
        }
        TypeCache<T, ID> cache = typeCache(type);
        if (cache == null) throw new MissingIdentitySpecificationException(type);
        return cache.prepare(replacement);
    }

    /** The specification registered for exactly this type, or null. */
    public <T> SearchSpecification<T, ?> getSpecification(Class<T> type) {
        TypeCache<T, ?> cache = typeCache(type);
        return cache == null ? null : cache.specification();
    }

    /** The specification that serves this type, possibly a supertype's, or null. */
    public <T> SearchSpecification<? super T, ?> searchSpecification(Class<T> type) {
        TypeCache<? super T, ?> cache = resolve(type);
        return cache == null ? null : cache.specification();
    }

    /** How many entities the type holds. */
    public int size(Class<?> type) {
        TypeCache<?, ?> cache = types.get(type);
        return cache == null ? 0 : cache.size();
    }
}
