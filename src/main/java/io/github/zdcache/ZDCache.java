package io.github.zdcache;

import io.github.zdcache.internal.TypeCache;
import io.github.zdcache.store.IdentityStoreFactory;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process, multi-type cache with per-type capacity/lock and specification-defined indexes.
 * Mutating a returned entity by reference is intentionally NOT tracked; use put() to reindex.
 */
public final class ZDCache {
    private final Map<Class<?>, TypeCache<?, ?>> types = new ConcurrentHashMap<>();
    private final Map<Class<?>, TypeRedirect<?>> redirects = new ConcurrentHashMap<>();
    private final Map<Class<?>, TypeCache<?, ?>> resolved = new ConcurrentHashMap<>();
    private final Object registry = new Object();
    private final int defaultMaxPerType;
    private final Map<Class<?>, Integer> capacities;
    private final IdentityStoreFactory stores;

    private ZDCache(Builder builder) {
        defaultMaxPerType = builder.defaultMaxPerType;
        capacities = Map.copyOf(builder.capacities);
        stores = builder.stores;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private int defaultMaxPerType = 10_000;
        private final Map<Class<?>, Integer> capacities = new HashMap<>();
        private IdentityStoreFactory stores = IdentityStoreFactory.linkedHashMap();

        public Builder defaultMaxPerType(int maximum) {
            positive(maximum);
            defaultMaxPerType = maximum;
            return this;
        }

        public Builder capacity(Class<?> type, int maximum) {
            capacities.put(Objects.requireNonNull(type), positive(maximum));
            return this;
        }

        public Builder identityStore(IdentityStoreFactory factory) {
            stores = Objects.requireNonNull(factory);
            return this;
        }

        public ZDCache build() { return new ZDCache(this); }
        private static int positive(int n) {
            if (n <= 0) throw new IllegalArgumentException("Capacity must be positive");
            return n;
        }
    }

    @SuppressWarnings("unchecked")
    private <T, ID> TypeCache<T, ID> typeCache(Class<T> type) {
        return (TypeCache<T, ID>) types.get(type);
    }

    @SuppressWarnings("unchecked")
    private <T, ID> TypeCache<T, ID> resolve(Class<?> type) {
        TypeCache<?, ?> cache = types.get(type);
        if (cache == null) cache = resolved.get(type);
        if (cache == null) cache = resolveAndRemember(type);
        return (TypeCache<T, ID>) cache;
    }

    private TypeCache<?, ?> resolveAndRemember(Class<?> type) {
        synchronized (registry) {
            TypeCache<?, ?> cache = specified(type);
            if (cache == null) cache = nearestSuperclass(type);
            if (cache == null) cache = nearestInterface(type);
            if (cache != null) {
                requireNoOwnCapacity(type, cache.specification().type());
                resolved.put(type, cache);
            }
            return cache;
        }
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
        synchronized (registry) {
            if (redirects.containsKey(type)) throw new ConflictingSearchSpecificationException(type);
            TypeCache<?, ?> cache = types.get(type);
            if (cache == null) {
                cache = new TypeCache<>(specification,
                    stores.create(capacities.getOrDefault(type, defaultMaxPerType)));
                types.put(type, cache);
                resolved.clear();
            }
            return (TypeCache<T, ID>) cache;
        }
    }

    public <T, ID> void register(SearchSpecification<T, ID> specification) {
        Objects.requireNonNull(specification);
        if (registerCache(specification).specification() != specification) {
            throw new ConflictingSearchSpecificationException(specification.type());
        }
    }

    public <S> void register(TypeRedirect<S> redirect) {
        Objects.requireNonNull(redirect);
        synchronized (registry) {
            Class<S> type = redirect.type();
            TypeRedirect<?> existing = redirects.get(type);
            if (types.containsKey(type) || existing != null && existing.target() != redirect.target()) {
                throw new ConflictingSearchSpecificationException(type);
            }
            requireNoOwnCapacity(type, redirect.target().type());
            register(redirect.target());
            redirects.put(type, redirect);
            resolved.clear();
        }
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

    public <T, ID> T get(Class<T> type, ID id) {
        TypeCache<?, ID> cache = resolve(type);
        if (cache == null) return null;
        return type.cast(cache.get(id, type));
    }

    public <T, ID> T remove(Class<T> type, ID id) {
        TypeCache<?, ID> cache = resolve(type);
        return cache == null ? null : type.cast(cache.remove(id, type));
    }

    public <T> List<T> search(Class<T> type, String dimension, Object... values) {
        TypeCache<?, Object> cache = resolve(type);
        if (cache == null) throw new MissingSearchSpecificationStrategyException(type);
        return cache.search(dimension, values).stream()
            .filter(type::isInstance)
            .map(type::cast)
            .toList();
    }

    public <T, ID> void updateSpecification(Class<T> type, SearchSpecification<T, ID> replacement) {
        Objects.requireNonNull(replacement);
        if (replacement.type() != type) {
            throw new IllegalArgumentException("Type and specification must match");
        }
        TypeCache<T, ID> cache = typeCache(type);
        if (cache == null) throw new MissingIdentitySpecificationException(type);
        cache.updateSpecification(replacement);
    }

    public <T> SearchSpecification<T, ?> getSpecification(Class<T> type) {
        TypeCache<T, ?> cache = typeCache(type);
        return cache == null ? null : cache.specification();
    }

    public <T> SearchSpecification<? super T, ?> searchSpecification(Class<T> type) {
        TypeCache<? super T, ?> cache = resolve(type);
        return cache == null ? null : cache.specification();
    }

    public int size(Class<?> type) {
        TypeCache<?, ?> cache = types.get(type);
        return cache == null ? 0 : cache.size();
    }
}
