package io.github.zdcache;

import io.github.zdcache.internal.PropertyAccess;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Immutable, per-type identity and search schema. Specifications are compared by INSTANCE on put;
 * updateSpecification is the only way to publish a replacement schema.
 */
public final class SearchSpecification<T, ID> {
    private final IdentityDimension<T, ID> identity;
    private final List<SearchDimension<T>> dimensions;

    private SearchSpecification(IdentityDimension<T, ID> identity, List<SearchDimension<T>> dimensions) {
        this.identity = identity;
        this.dimensions = List.copyOf(dimensions);
    }

    public static <T, ID> SearchSpecification<T, ID> basic(IdentityDimension<T, ID> identity) {
        return new SearchSpecification<>(Objects.requireNonNull(identity), List.of());
    }

    public static <T, ID> Builder<T, ID> builder(IdentityDimension<T, ID> identity) {
        return new Builder<>(identity);
    }

    public static <S> TypeRedirect<S> lookupAs(Class<S> type, SearchSpecification<? super S, ?> target) {
        return new TypeRedirect<>(type, target);
    }

    public IdentityDimension<T, ID> identity() { return identity; }
    public Class<T> type() { return identity.type(); }
    public List<SearchDimension<T>> dimensions() { return dimensions; }
    public boolean searchable() { return !dimensions.isEmpty(); }

    public SearchDimension<T> dimension(String path) {
        for (SearchDimension<T> dimension : dimensions) {
            if (dimension.path().equals(path)) return dimension;
        }
        throw new UnknownSearchDimensionException(type(), path);
    }

    public static final class Builder<T, ID> {
        private final IdentityDimension<T, ID> identity;
        private final LinkedHashMap<String, SearchDimension<T>> dimensions = new LinkedHashMap<>();
        private final PathBuilder<T, T> root;

        private Builder(IdentityDimension<T, ID> identity) {
            this.identity = Objects.requireNonNull(identity);
            this.root = new PathBuilder<>("", identity.type(), t -> List.of(t), dimensions);
        }

        public Builder<T, ID> prefix(String property) {
            root.prefix(property); return this;
        }
        public Builder<T, ID> exact(String property) {
            root.exact(property); return this;
        }
        public Builder<T, ID> range(String property) {
            root.range(property); return this;
        }
        public Builder<T, ID> each(String property, Consumer<PathBuilder<T, Object>> nested) {
            root.each(property, nested); return this;
        }
        public Builder<T, ID> exactEach(String property) {
            root.exactEach(property); return this;
        }
        public Builder<T, ID> prefixEach(String property) {
            root.prefixEach(property); return this;
        }

        public Builder<T, ID> prefix(String name, Function<T, String> extractor) {
            root.prefix(name, extractor); return this;
        }
        public <V> Builder<T, ID> exact(String name, Function<T, V> extractor) {
            root.exact(name, extractor); return this;
        }
        public <V extends Comparable<? super V>> Builder<T, ID> range(
                String name, Function<T, V> extractor) {
            root.range(name, extractor); return this;
        }
        public <E> Builder<T, ID> each(
                String name, Function<T, ? extends Iterable<E>> extractor,
                Consumer<PathBuilder<T, E>> nested) {
            root.each(name, extractor, nested); return this;
        }
        public <V> Builder<T, ID> exactEach(
                String name, Function<T, ? extends Iterable<V>> extractor) {
            root.exactEach(name, extractor); return this;
        }
        public Builder<T, ID> prefixEach(
                String name, Function<T, ? extends Iterable<String>> extractor) {
            root.prefixEach(name, extractor); return this;
        }
        public Builder<T, ID> compound(String name, Consumer<CompoundBuilder<T>> parts) {
            root.compound(name, parts); return this;
        }

        public SearchSpecification<T, ID> build() {
            return new SearchSpecification<>(identity, new ArrayList<>(dimensions.values()));
        }
    }

    /**
     * Traversal builder: each() expands nested collections, indexing ROOT IDs at leaf paths.
     * Paths are literal dot-separated names; without an extractor each name is also the reflected property.
     */
    public static final class PathBuilder<ROOT, CURRENT> {
        private final String path;
        private final Class<?> type;
        private final Function<ROOT, List<CURRENT>> parents;
        private final Map<String, SearchDimension<ROOT>> dimensions;

        private PathBuilder(String path, Class<?> type, Function<ROOT, List<CURRENT>> parents,
                            Map<String, SearchDimension<ROOT>> dimensions) {
            this.path = path;
            this.type = type;
            this.parents = parents;
            this.dimensions = dimensions;
        }

        private String property(String name) {
            if (type != null && name != null && !name.isBlank() && !name.contains(".")) {
                PropertyAccess.reader(type, name);
            }
            return name;
        }

        public PathBuilder<ROOT, CURRENT> prefix(String property) {
            String name = property(property);
            scalar(name, IndexKind.PREFIX, current -> PropertyAccess.read(current, name));
            return this;
        }
        public PathBuilder<ROOT, CURRENT> exact(String property) {
            String name = property(property);
            return exact(name, current -> PropertyAccess.read(current, name));
        }
        public PathBuilder<ROOT, CURRENT> range(String property) {
            String name = property(property);
            scalar(name, IndexKind.RANGE, current -> PropertyAccess.read(current, name));
            return this;
        }
        public PathBuilder<ROOT, CURRENT> exactEach(String property) {
            String name = property(property);
            leaf(name, IndexKind.EXACT, current -> PropertyAccess.readAll(current, name));
            return this;
        }
        public PathBuilder<ROOT, CURRENT> prefixEach(String property) {
            String name = property(property);
            leaf(name, IndexKind.PREFIX, current -> PropertyAccess.readAll(current, name));
            return this;
        }
        @SuppressWarnings("unchecked")
        public PathBuilder<ROOT, CURRENT> each(String property, Consumer<PathBuilder<ROOT, Object>> nested) {
            String name = property(property);
            return each(name, current -> (Iterable<Object>) PropertyAccess.readAll(current, name), nested);
        }

        private String child(String name) {
            if (name == null || name.isBlank() || name.contains(".")) {
                throw new IllegalArgumentException("Dimension segment must be nonblank and cannot contain '.'");
            }
            return path.isEmpty() ? name : path + "." + name;
        }

        private <V> void leaf(String name, IndexKind kind,
                              Function<CURRENT, ? extends Iterable<V>> extractor) {
            String fullPath = child(name);
            SearchDimension<ROOT> def = new SearchDimension<>(fullPath, kind, root -> {
                List<V> values = new ArrayList<>();
                for (CURRENT current : parents.apply(root)) {
                    if (current == null) continue;
                    Iterable<V> extracted = extractor.apply(current);
                    if (extracted != null) {
                        for (V value : extracted) values.add(value);
                    }
                }
                return values;
            });
            if (dimensions.putIfAbsent(fullPath, def) != null) {
                throw new IllegalArgumentException("Duplicate dimension: " + fullPath);
            }
        }

        private <V> void scalar(String name, IndexKind kind, Function<CURRENT, V> extractor) {
            Objects.requireNonNull(extractor);
            leaf(name, kind, cur -> Collections.singletonList(extractor.apply(cur)));
        }

        public PathBuilder<ROOT, CURRENT> prefix(String name, Function<CURRENT, String> extractor) {
            scalar(name, IndexKind.PREFIX, extractor); return this;
        }
        public <V> PathBuilder<ROOT, CURRENT> exact(String name, Function<CURRENT, V> extractor) {
            scalar(name, IndexKind.EXACT, extractor); return this;
        }
        public <V extends Comparable<? super V>> PathBuilder<ROOT, CURRENT> range(
                String name, Function<CURRENT, V> extractor) {
            scalar(name, IndexKind.RANGE, extractor); return this;
        }
        public <V> PathBuilder<ROOT, CURRENT> exactEach(
                String name, Function<CURRENT, ? extends Iterable<V>> extractor) {
            leaf(name, IndexKind.EXACT, extractor); return this;
        }
        public PathBuilder<ROOT, CURRENT> prefixEach(
                String name, Function<CURRENT, ? extends Iterable<String>> extractor) {
            leaf(name, IndexKind.PREFIX, extractor); return this;
        }
        public PathBuilder<ROOT, CURRENT> compound(String name, Consumer<CompoundBuilder<CURRENT>> declaration) {
            Objects.requireNonNull(declaration);
            String fullPath = child(name);
            CompoundBuilder<CURRENT> compound = new CompoundBuilder<>(type);
            declaration.accept(compound);
            List<CompoundBuilder.PartExtractor<CURRENT>> parts = compound.validated(fullPath);
            List<SearchDimension.Part> declared = parts.stream()
                .map(part -> new SearchDimension.Part(part.name(), part.kind())).toList();
            SearchDimension<ROOT> def = new SearchDimension<>(fullPath, IndexKind.COMPOUND, declared, root -> {
                List<List<Object>> keys = new ArrayList<>();
                for (CURRENT current : parents.apply(root)) {
                    if (current != null) keys.addAll(CompoundBuilder.combinations(parts, current));
                }
                return keys;
            });
            if (dimensions.putIfAbsent(fullPath, def) != null) {
                throw new IllegalArgumentException("Duplicate dimension: " + fullPath);
            }
            return this;
        }
        public <E> PathBuilder<ROOT, CURRENT> each(
                String name, Function<CURRENT, ? extends Iterable<E>> extractor,
                Consumer<PathBuilder<ROOT, E>> nested) {
            Objects.requireNonNull(extractor);
            Objects.requireNonNull(nested);
            String nestedPath = child(name);
            Function<ROOT, List<E>> next = root -> {
                List<E> list = new ArrayList<>();
                for (CURRENT current : parents.apply(root)) {
                    if (current == null) continue;
                    Iterable<E> children = extractor.apply(current);
                    if (children == null) continue;
                    for (E item : children) if (item != null) list.add(item);
                }
                return list;
            };
            nested.accept(new PathBuilder<>(nestedPath, null, next, dimensions));
            return this;
        }
    }

    public static final class CompoundBuilder<E> {
        record PartExtractor<E>(String name, IndexKind kind, Function<E, ? extends Iterable<?>> extractor) {}

        private final Class<?> type;
        private final List<PartExtractor<E>> parts = new ArrayList<>();

        private CompoundBuilder(Class<?> type) { this.type = type; }

        private String property(String name) {
            if (type != null && name != null && !name.isBlank() && !name.contains(".")) {
                PropertyAccess.reader(type, name);
            }
            return name;
        }

        public CompoundBuilder<E> exact(String property) {
            String name = property(property);
            return exact(name, e -> PropertyAccess.read(e, name));
        }
        public CompoundBuilder<E> exactEach(String property) {
            String name = property(property);
            return part(name, IndexKind.EXACT, e -> PropertyAccess.readAll(e, name));
        }
        public CompoundBuilder<E> range(String property) {
            String name = property(property);
            return scalar(name, IndexKind.RANGE, e -> PropertyAccess.read(e, name));
        }
        public CompoundBuilder<E> prefix(String property) {
            String name = property(property);
            return scalar(name, IndexKind.PREFIX, e -> PropertyAccess.read(e, name));
        }

        public <V> CompoundBuilder<E> exact(String name, Function<E, V> extractor) {
            return scalar(name, IndexKind.EXACT, extractor);
        }
        public <V> CompoundBuilder<E> exactEach(String name, Function<E, ? extends Iterable<V>> extractor) {
            return part(name, IndexKind.EXACT, extractor);
        }
        public <V extends Comparable<? super V>> CompoundBuilder<E> range(String name, Function<E, V> extractor) {
            return scalar(name, IndexKind.RANGE, extractor);
        }
        public CompoundBuilder<E> prefix(String name, Function<E, String> extractor) {
            return scalar(name, IndexKind.PREFIX, extractor);
        }

        private <V> CompoundBuilder<E> scalar(String name, IndexKind kind, Function<E, V> extractor) {
            Objects.requireNonNull(extractor);
            return part(name, kind, e -> Collections.singletonList(extractor.apply(e)));
        }

        private CompoundBuilder<E> part(String name, IndexKind kind, Function<E, ? extends Iterable<?>> extractor) {
            if (name == null || name.isBlank() || name.contains(".")) {
                throw new IllegalArgumentException("Compound part must be nonblank and cannot contain '.'");
            }
            parts.add(new PartExtractor<>(name, kind, Objects.requireNonNull(extractor)));
            return this;
        }

        private List<PartExtractor<E>> validated(String path) {
            if (parts.size() < 2) {
                throw new IllegalArgumentException("Compound " + path + " needs at least 2 parts");
            }
            if (parts.stream().map(PartExtractor::name).distinct().count() != parts.size()) {
                throw new IllegalArgumentException("Duplicate part in compound " + path);
            }
            for (int i = 0; i < parts.size() - 1; i++) {
                if (parts.get(i).kind() != IndexKind.EXACT) {
                    throw new IllegalArgumentException("Only the last part of compound " + path +
                        " can be RANGE or PREFIX, but " + parts.get(i).name() + " is " + parts.get(i).kind());
                }
            }
            return List.copyOf(parts);
        }

        private static <E> List<List<Object>> combinations(List<PartExtractor<E>> parts, E element) {
            List<List<Object>> keys = List.of(List.of());
            for (PartExtractor<E> part : parts) {
                Iterable<?> extracted = part.extractor().apply(element);
                if (extracted == null) return List.of();
                LinkedHashSet<Object> values = new LinkedHashSet<>();
                for (Object value : extracted) if (value != null) values.add(value);
                List<List<Object>> extended = new ArrayList<>(keys.size() * values.size());
                for (List<Object> key : keys) {
                    for (Object value : values) {
                        List<Object> next = new ArrayList<>(key);
                        next.add(value);
                        extended.add(next);
                    }
                }
                keys = extended;
            }
            return keys.stream().map(List::copyOf).toList();
        }
    }
}
