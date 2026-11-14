package io.github.zdcache;

import io.github.zdcache.internal.Getters;
import io.github.zdcache.internal.PropertyAccess;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Immutable, per-type identity and search schema. Specifications are compared by INSTANCE on put;
 * updateSpecification is the only way to publish a replacement schema.
 *
 * <p>Declare one dimension per way you search: {@code prefix} for text from its start, {@code exact} for
 * equal values, {@code range} for ordered values, {@code each} for values inside collections and
 * {@code compound} for a combination searched together. Text dimensions can add {@link TextStrategy}s for
 * words and text anywhere. Every index costs memory and time on each put, so declare only what you search.
 */
public final class SearchSpecification<T, ID> {
    private final IdentityDimension<T, ID> identity;
    private final List<SearchDimension<T>> dimensions;

    private SearchSpecification(IdentityDimension<T, ID> identity, List<SearchDimension<T>> dimensions) {
        this.identity = identity;
        this.dimensions = List.copyOf(dimensions);
    }

    /** A key-only specification: entities can be stored and fetched by id, not searched. */
    public static <T, ID> SearchSpecification<T, ID> basic(IdentityDimension<T, ID> identity) {
        return new SearchSpecification<>(Objects.requireNonNull(identity), List.of());
    }

    /** Starts a specification for the type the identity belongs to. */
    public static <T, ID> Builder<T, ID> builder(IdentityDimension<T, ID> identity) {
        return new Builder<>(identity);
    }

    /** Starts from an existing specification, keeping its dimensions, to add more for {@code updateSpecification}. */
    public static <T, ID> Builder<T, ID> builder(SearchSpecification<T, ID> base) {
        Builder<T, ID> builder = new Builder<>(base.identity());
        for (SearchDimension<T> dimension : base.dimensions()) builder.dimensions.put(dimension.path(), dimension);
        return builder;
    }

    /** A copy where a text dimension also has these strategies; {@code updateSpecification} keeps its existing index. */
    public SearchSpecification<T, ID> withStrategies(Getter<T, ?> dimension, TextStrategy... strategies) {
        return withStrategies(Getters.name(dimension), strategies);
    }

    /** A copy where the text dimension at this path also has these strategies; {@code updateSpecification} keeps its existing index. */
    public SearchSpecification<T, ID> withStrategies(String path, TextStrategy... strategies) {
        SearchDimension<T> upgraded = dimension(path).withStrategies(strategies);
        return new SearchSpecification<>(identity, dimensions.stream().map(d -> d.path().equals(path) ? upgraded : d).toList());
    }

    /** A copy without the dimension; {@code updateSpecification} then drops its index. */
    public SearchSpecification<T, ID> without(Getter<T, ?> dimension) {
        return without(Getters.name(dimension));
    }

    /** A copy without the dimension at this path; {@code updateSpecification} then drops its index. */
    public SearchSpecification<T, ID> without(String path) {
        dimension(path);
        return new SearchSpecification<>(identity, dimensions.stream().filter(d -> !d.path().equals(path)).toList());
    }

    /** Lets a subtype be stored and searched under this supertype's specification; register the result with {@link ZDCache#register(TypeRedirect)}. */
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

    /** Collects the dimensions of a specification; each method adds one dimension and returns the builder. */
    public static final class Builder<T, ID> {
        private final IdentityDimension<T, ID> identity;
        private final LinkedHashMap<String, SearchDimension<T>> dimensions = new LinkedHashMap<>();
        private final PathBuilder<T, T> root;

        private Builder(IdentityDimension<T, ID> identity) {
            this.identity = Objects.requireNonNull(identity);
            this.root = new PathBuilder<>("", identity.type(), t -> List.of(t), dimensions);
        }

        /** Searches text from its start, ignoring case. Read by property name; the method reference variant keeps the name right when a property is renamed. */
        public Builder<T, ID> prefix(String property) {
            root.prefix(property); return this;
        }
        /** Finds values equal to the search value; values must not change while stored. Read by property name; the method reference variant keeps the name right when a property is renamed. */
        public Builder<T, ID> exact(String property) {
            root.exact(property); return this;
        }
        /** Finds comparable values inside a {@link ValueRange}, or equal to one value. Read by property name; the method reference variant keeps the name right when a property is renamed. */
        public Builder<T, ID> range(String property) {
            root.range(property); return this;
        }
        /** Declares dimensions on the elements of a collection; they are named after the collection and found through the root. Read by property name; the method reference variant keeps the name right when a property is renamed. */
        public Builder<T, ID> each(String property, Consumer<PathBuilder<T, Object>> nested) {
            root.each(property, nested); return this;
        }
        /** Exact search on every element of a collection; an entity is found once. Read by property name; the method reference variant keeps the name right when a property is renamed. */
        public Builder<T, ID> exactEach(String property) {
            root.exactEach(property); return this;
        }
        /** Prefix search on every string in a collection; an entity is found once. Read by property name; the method reference variant keeps the name right when a property is renamed. */
        public Builder<T, ID> prefixEach(String property) {
            root.prefixEach(property); return this;
        }

        /** Searches text from its start, ignoring case. Named {@code name} and read by the function. */
        public Builder<T, ID> prefix(String name, Function<T, String> extractor) {
            root.prefix(name, extractor); return this;
        }
        /** Prefix search on the whole value and on each of its words, split on whitespace unless a {@link Split} is given. Read by property name; the method reference variant keeps the name right when a property is renamed. */
        public Builder<T, ID> prefixSplit(String property) {
            root.prefixSplit(property); return this;
        }
        /** Prefix search on the whole value and on each of its words, split on whitespace unless a {@link Split} is given. Read by property name; the method reference variant keeps the name right when a property is renamed. */
        public Builder<T, ID> prefixSplit(String property, Split split) {
            root.prefixSplit(property, split); return this;
        }
        /** Prefix search on the whole value and on each of its words, split on whitespace unless a {@link Split} is given. Named {@code name} and read by the function. */
        public Builder<T, ID> prefixSplit(String name, Function<T, String> extractor, Split split) {
            root.prefixSplit(name, extractor, split); return this;
        }
        /** Prefix search on the whole value and on each of its words, split on whitespace unless a {@link Split} is given. Named after the method. */
        public Builder<T, ID> prefixSplit(Getter<T, String> getter) {
            root.prefixSplit(getter); return this;
        }
        /** Prefix search on the whole value and on each of its words, split on whitespace unless a {@link Split} is given. Named after the method. */
        public Builder<T, ID> prefixSplit(Getter<T, String> getter, Split split) {
            root.prefixSplit(getter, split); return this;
        }
        /** Prefix search, plus finding text anywhere in the value through an index of three-character pieces. Read by property name; the method reference variant keeps the name right when a property is renamed. */
        public Builder<T, ID> prefixContains(String property) {
            root.prefixContains(property); return this;
        }
        /** Prefix search, plus finding text anywhere in the value through an index of three-character pieces. Named {@code name} and read by the function. */
        public Builder<T, ID> prefixContains(String name, Function<T, String> extractor) {
            root.prefixContains(name, extractor); return this;
        }
        /** Prefix search, plus finding text anywhere in the value through an index of three-character pieces. Named after the method. */
        public Builder<T, ID> prefixContains(Getter<T, String> getter) {
            root.prefixContains(getter); return this;
        }
        /** Prefix search with text strategies added: words with {@link TextStrategy#split()}, text anywhere with {@link TextStrategy#contains()}. Read by property name; the method reference variant keeps the name right when a property is renamed. */
        public Builder<T, ID> prefix(String property, TextStrategy... strategies) {
            root.prefix(property, strategies); return this;
        }
        /** Prefix search with text strategies added: words with {@link TextStrategy#split()}, text anywhere with {@link TextStrategy#contains()}. Named {@code name} and read by the function. */
        public Builder<T, ID> prefix(String name, Function<T, String> extractor, TextStrategy... strategies) {
            root.prefix(name, extractor, strategies); return this;
        }
        /** Prefix search with text strategies added: words with {@link TextStrategy#split()}, text anywhere with {@link TextStrategy#contains()}. Named after the method. */
        public Builder<T, ID> prefix(Getter<T, String> getter, TextStrategy... strategies) {
            root.prefix(getter, strategies); return this;
        }
        /** Finds values equal to the search value; values must not change while stored. Named {@code name} and read by the function. */
        public <V> Builder<T, ID> exact(String name, Function<T, V> extractor) {
            root.exact(name, extractor); return this;
        }
        /** Finds comparable values inside a {@link ValueRange}, or equal to one value. Named {@code name} and read by the function. */
        public <V extends Comparable<? super V>> Builder<T, ID> range(
                String name, Function<T, V> extractor) {
            root.range(name, extractor); return this;
        }
        /** Declares dimensions on the elements of a collection; they are named after the collection and found through the root. Named {@code name} and read by the function. */
        public <E> Builder<T, ID> each(
                String name, Function<T, ? extends Iterable<E>> extractor,
                Consumer<PathBuilder<T, E>> nested) {
            root.each(name, extractor, nested); return this;
        }
        /** Exact search on every element of a collection; an entity is found once. Named {@code name} and read by the function. */
        public <V> Builder<T, ID> exactEach(
                String name, Function<T, ? extends Iterable<V>> extractor) {
            root.exactEach(name, extractor); return this;
        }
        /** Prefix search on every string in a collection; an entity is found once. Named {@code name} and read by the function. */
        public Builder<T, ID> prefixEach(
                String name, Function<T, ? extends Iterable<String>> extractor) {
            root.prefixEach(name, extractor); return this;
        }
        /** Combines dimensions searched together into one index, named {@code name}; only the last part can be a prefix or a range. */
        public Builder<T, ID> compound(String name, Consumer<CompoundBuilder<T>> parts) {
            root.compound(name, parts); return this;
        }

        /** Searches text from its start, ignoring case. Named after the method. */
        public Builder<T, ID> prefix(Getter<T, String> getter) {
            root.prefix(getter); return this;
        }
        /** Finds values equal to the search value; values must not change while stored. Named after the method. */
        public <V> Builder<T, ID> exact(Getter<T, V> getter) {
            root.exact(getter); return this;
        }
        /** Finds comparable values inside a {@link ValueRange}, or equal to one value. Named after the method. */
        public <V extends Comparable<? super V>> Builder<T, ID> range(Getter<T, V> getter) {
            root.range(getter); return this;
        }
        /** Declares dimensions on the elements of a collection; they are named after the collection and found through the root. Named after the method. */
        public <E> Builder<T, ID> each(Getter<T, ? extends Iterable<E>> getter, Consumer<PathBuilder<T, E>> nested) {
            root.each(getter, nested); return this;
        }
        /** Exact search on every element of a collection; an entity is found once. Named after the method. */
        public <V> Builder<T, ID> exactEach(Getter<T, ? extends Iterable<V>> getter) {
            root.exactEach(getter); return this;
        }
        /** Prefix search on every string in a collection; an entity is found once. Named after the method. */
        public Builder<T, ID> prefixEach(Getter<T, ? extends Iterable<String>> getter) {
            root.prefixEach(getter); return this;
        }

        /** The immutable specification; pass this same instance to every put of the type. */
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

        /** Searches text from its start, ignoring case. Read by property name; the method reference variant keeps the name right when a property is renamed. */
        public PathBuilder<ROOT, CURRENT> prefix(String property) {
            String name = property(property);
            scalar(name, IndexKind.PREFIX, current -> PropertyAccess.read(current, name));
            return this;
        }
        /** Finds values equal to the search value; values must not change while stored. Read by property name; the method reference variant keeps the name right when a property is renamed. */
        public PathBuilder<ROOT, CURRENT> exact(String property) {
            String name = property(property);
            return exact(name, current -> PropertyAccess.read(current, name));
        }
        /** Finds comparable values inside a {@link ValueRange}, or equal to one value. Read by property name; the method reference variant keeps the name right when a property is renamed. */
        public PathBuilder<ROOT, CURRENT> range(String property) {
            String name = property(property);
            scalar(name, IndexKind.RANGE, current -> PropertyAccess.read(current, name));
            return this;
        }
        /** Exact search on every element of a collection; an entity is found once. Read by property name; the method reference variant keeps the name right when a property is renamed. */
        public PathBuilder<ROOT, CURRENT> exactEach(String property) {
            String name = property(property);
            leaf(name, IndexKind.EXACT, true, current -> PropertyAccess.readAll(current, name));
            return this;
        }
        /** Prefix search on every string in a collection; an entity is found once. Read by property name; the method reference variant keeps the name right when a property is renamed. */
        public PathBuilder<ROOT, CURRENT> prefixEach(String property) {
            String name = property(property);
            leaf(name, IndexKind.PREFIX, true, current -> PropertyAccess.readAll(current, name));
            return this;
        }
        /** Declares dimensions on the elements of a collection; they are named after the collection and found through the root. Read by property name; the method reference variant keeps the name right when a property is renamed. */
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

        private <V> void leaf(String name, IndexKind kind, boolean multi,
                              Function<CURRENT, ? extends Iterable<V>> extractor) {
            leaf(name, kind, multi, null, false, extractor);
        }

        private <V> void leaf(String name, IndexKind kind, boolean multi, Split split, boolean contains,
                              Function<CURRENT, ? extends Iterable<V>> extractor) {
            String fullPath = child(name);
            @SuppressWarnings("unchecked")
            Function<ROOT, ? extends Iterable<?>> atRoot = root -> extractor.apply((CURRENT) root);
            SearchDimension<ROOT> def = new SearchDimension<>(fullPath, kind, multi || !path.isEmpty(), split, contains, path.isEmpty() ? atRoot : root -> {
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
            leaf(name, kind, false, cur -> Collections.singletonList(extractor.apply(cur)));
        }

        /** Searches text from its start, ignoring case. Named {@code name} and read by the function. */
        public PathBuilder<ROOT, CURRENT> prefix(String name, Function<CURRENT, String> extractor) {
            scalar(name, IndexKind.PREFIX, extractor); return this;
        }
        /** Prefix search with text strategies added: words with {@link TextStrategy#split()}, text anywhere with {@link TextStrategy#contains()}. Read by property name; the method reference variant keeps the name right when a property is renamed. */
        public PathBuilder<ROOT, CURRENT> prefix(String property, TextStrategy... strategies) {
            String name = property(property);
            return text(name, current -> PropertyAccess.read(current, name), strategies);
        }
        /** Prefix search with text strategies added: words with {@link TextStrategy#split()}, text anywhere with {@link TextStrategy#contains()}. Named {@code name} and read by the function. */
        public PathBuilder<ROOT, CURRENT> prefix(String name, Function<CURRENT, String> extractor, TextStrategy... strategies) {
            return text(name, extractor, strategies);
        }
        /** Prefix search with text strategies added: words with {@link TextStrategy#split()}, text anywhere with {@link TextStrategy#contains()}. Named after the method. */
        public PathBuilder<ROOT, CURRENT> prefix(Getter<CURRENT, String> getter, TextStrategy... strategies) {
            return text(Getters.name(getter), getter, strategies);
        }
        private PathBuilder<ROOT, CURRENT> text(String name, Function<CURRENT, ?> extractor, TextStrategy... strategies) {
            Objects.requireNonNull(extractor);
            leaf(name, IndexKind.PREFIX, false, null, false, current -> Collections.singletonList(extractor.apply(current)));
            String fullPath = child(name);
            dimensions.put(fullPath, dimensions.get(fullPath).withStrategies(strategies));
            return this;
        }
        /** Prefix search on the whole value and on each of its words, split on whitespace unless a {@link Split} is given. Read by property name; the method reference variant keeps the name right when a property is renamed. */
        public PathBuilder<ROOT, CURRENT> prefixSplit(String property) {
            return prefixSplit(property, Split.WHITESPACE);
        }
        /** Prefix search on the whole value and on each of its words, split on whitespace unless a {@link Split} is given. Read by property name; the method reference variant keeps the name right when a property is renamed. */
        public PathBuilder<ROOT, CURRENT> prefixSplit(String property, Split split) {
            String name = property(property);
            return split(name, current -> PropertyAccess.read(current, name), split);
        }
        /** Prefix search on the whole value and on each of its words, split on whitespace unless a {@link Split} is given. Named {@code name} and read by the function. */
        public PathBuilder<ROOT, CURRENT> prefixSplit(String name, Function<CURRENT, String> extractor, Split split) {
            return split(name, extractor, split);
        }
        /** Prefix search on the whole value and on each of its words, split on whitespace unless a {@link Split} is given. Named after the method. */
        public PathBuilder<ROOT, CURRENT> prefixSplit(Getter<CURRENT, String> getter) {
            return prefixSplit(getter, Split.WHITESPACE);
        }
        /** Prefix search on the whole value and on each of its words, split on whitespace unless a {@link Split} is given. Named after the method. */
        public PathBuilder<ROOT, CURRENT> prefixSplit(Getter<CURRENT, String> getter, Split split) {
            return split(Getters.name(getter), getter, split);
        }
        private PathBuilder<ROOT, CURRENT> split(String name, Function<CURRENT, ?> extractor, Split split) {
            return text(name, extractor, TextStrategy.split(Objects.requireNonNull(split)));
        }
        /** Prefix search, plus finding text anywhere in the value through an index of three-character pieces. Read by property name; the method reference variant keeps the name right when a property is renamed. */
        public PathBuilder<ROOT, CURRENT> prefixContains(String property) {
            String name = property(property);
            return contains(name, current -> PropertyAccess.read(current, name));
        }
        /** Prefix search, plus finding text anywhere in the value through an index of three-character pieces. Named {@code name} and read by the function. */
        public PathBuilder<ROOT, CURRENT> prefixContains(String name, Function<CURRENT, String> extractor) {
            return contains(name, extractor);
        }
        /** Prefix search, plus finding text anywhere in the value through an index of three-character pieces. Named after the method. */
        public PathBuilder<ROOT, CURRENT> prefixContains(Getter<CURRENT, String> getter) {
            return contains(Getters.name(getter), getter);
        }
        private PathBuilder<ROOT, CURRENT> contains(String name, Function<CURRENT, ?> extractor) {
            return text(name, extractor, TextStrategy.contains());
        }
        /** Finds values equal to the search value; values must not change while stored. Named {@code name} and read by the function. */
        public <V> PathBuilder<ROOT, CURRENT> exact(String name, Function<CURRENT, V> extractor) {
            scalar(name, IndexKind.EXACT, extractor); return this;
        }
        /** Finds comparable values inside a {@link ValueRange}, or equal to one value. Named {@code name} and read by the function. */
        public <V extends Comparable<? super V>> PathBuilder<ROOT, CURRENT> range(
                String name, Function<CURRENT, V> extractor) {
            scalar(name, IndexKind.RANGE, extractor); return this;
        }
        /** Exact search on every element of a collection; an entity is found once. Named {@code name} and read by the function. */
        public <V> PathBuilder<ROOT, CURRENT> exactEach(
                String name, Function<CURRENT, ? extends Iterable<V>> extractor) {
            leaf(name, IndexKind.EXACT, true, extractor); return this;
        }
        /** Prefix search on every string in a collection; an entity is found once. Named {@code name} and read by the function. */
        public PathBuilder<ROOT, CURRENT> prefixEach(
                String name, Function<CURRENT, ? extends Iterable<String>> extractor) {
            leaf(name, IndexKind.PREFIX, true, extractor); return this;
        }

        /** Searches text from its start, ignoring case. Named after the method. */
        public PathBuilder<ROOT, CURRENT> prefix(Getter<CURRENT, String> getter) {
            return prefix(Getters.name(getter), getter);
        }
        /** Finds values equal to the search value; values must not change while stored. Named after the method. */
        public <V> PathBuilder<ROOT, CURRENT> exact(Getter<CURRENT, V> getter) {
            return exact(Getters.name(getter), getter);
        }
        /** Finds comparable values inside a {@link ValueRange}, or equal to one value. Named after the method. */
        public <V extends Comparable<? super V>> PathBuilder<ROOT, CURRENT> range(Getter<CURRENT, V> getter) {
            return range(Getters.name(getter), getter);
        }
        /** Exact search on every element of a collection; an entity is found once. Named after the method. */
        public <V> PathBuilder<ROOT, CURRENT> exactEach(Getter<CURRENT, ? extends Iterable<V>> getter) {
            return exactEach(Getters.name(getter), getter);
        }
        /** Prefix search on every string in a collection; an entity is found once. Named after the method. */
        public PathBuilder<ROOT, CURRENT> prefixEach(Getter<CURRENT, ? extends Iterable<String>> getter) {
            return prefixEach(Getters.name(getter), getter);
        }
        /** Declares dimensions on the elements of a collection; they are named after the collection and found through the root. */
        public <E> PathBuilder<ROOT, CURRENT> each(
                Getter<CURRENT, ? extends Iterable<E>> getter, Consumer<PathBuilder<ROOT, E>> nested) {
            return each(Getters.name(getter), getter, nested);
        }

        /** Combines dimensions searched together into one index, named {@code name}; only the last part can be a prefix or a range. */
        public PathBuilder<ROOT, CURRENT> compound(String name, Consumer<CompoundBuilder<CURRENT>> declaration) {
            Objects.requireNonNull(declaration);
            String fullPath = child(name);
            CompoundBuilder<CURRENT> compound = new CompoundBuilder<>(type);
            declaration.accept(compound);
            List<CompoundBuilder.PartExtractor<CURRENT>> parts = compound.validated(fullPath);
            List<SearchDimension.Part> declared = parts.stream()
                .map(part -> new SearchDimension.Part(part.name(), part.kind())).toList();
            boolean multi = !path.isEmpty() || parts.stream().anyMatch(CompoundBuilder.PartExtractor::multi);
            @SuppressWarnings("unchecked")
            Function<ROOT, ? extends Iterable<?>> atRoot = root -> CompoundBuilder.combinations(parts, (CURRENT) root);
            SearchDimension<ROOT> def = new SearchDimension<>(fullPath, IndexKind.COMPOUND, declared, multi, path.isEmpty() ? atRoot : root -> {
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
        /** Declares dimensions on the elements of a collection; they are named after the collection and found through the root. */
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

    /** Declares the parts of a compound dimension, in the order a search gives their values. */
    public static final class CompoundBuilder<E> {
        record PartExtractor<E>(String name, IndexKind kind, boolean multi, Function<E, ? extends Iterable<?>> extractor) {}

        private final Class<?> type;
        private final List<PartExtractor<E>> parts = new ArrayList<>();

        private CompoundBuilder(Class<?> type) { this.type = type; }

        private String property(String name) {
            if (type != null && name != null && !name.isBlank() && !name.contains(".")) {
                PropertyAccess.reader(type, name);
            }
            return name;
        }

        /** A part equal to its search value. Read by property name; the method reference variant keeps the name right when a property is renamed. */
        public CompoundBuilder<E> exact(String property) {
            String name = property(property);
            return exact(name, e -> PropertyAccess.read(e, name));
        }
        /** A part matching any element of a collection. Read by property name; the method reference variant keeps the name right when a property is renamed. */
        public CompoundBuilder<E> exactEach(String property) {
            String name = property(property);
            return part(name, IndexKind.EXACT, true, e -> PropertyAccess.readAll(e, name));
        }
        /** A part inside a range; only allowed as the last part. Read by property name; the method reference variant keeps the name right when a property is renamed. */
        public CompoundBuilder<E> range(String property) {
            String name = property(property);
            return scalar(name, IndexKind.RANGE, e -> PropertyAccess.read(e, name));
        }
        /** A part searched from its start; only allowed as the last part. Read by property name; the method reference variant keeps the name right when a property is renamed. */
        public CompoundBuilder<E> prefix(String property) {
            String name = property(property);
            return scalar(name, IndexKind.PREFIX, e -> PropertyAccess.read(e, name));
        }

        /** A part equal to its search value. Named {@code name} and read by the function. */
        public <V> CompoundBuilder<E> exact(String name, Function<E, V> extractor) {
            return scalar(name, IndexKind.EXACT, extractor);
        }
        /** A part matching any element of a collection. Named {@code name} and read by the function. */
        public <V> CompoundBuilder<E> exactEach(String name, Function<E, ? extends Iterable<V>> extractor) {
            return part(name, IndexKind.EXACT, true, extractor);
        }
        /** A part inside a range; only allowed as the last part. Named {@code name} and read by the function. */
        public <V extends Comparable<? super V>> CompoundBuilder<E> range(String name, Function<E, V> extractor) {
            return scalar(name, IndexKind.RANGE, extractor);
        }
        /** A part searched from its start; only allowed as the last part. Named {@code name} and read by the function. */
        public CompoundBuilder<E> prefix(String name, Function<E, String> extractor) {
            return scalar(name, IndexKind.PREFIX, extractor);
        }

        /** A part equal to its search value. Named after the method. */
        public <V> CompoundBuilder<E> exact(Getter<E, V> getter) {
            return exact(Getters.name(getter), getter);
        }
        /** A part matching any element of a collection. Named after the method. */
        public <V> CompoundBuilder<E> exactEach(Getter<E, ? extends Iterable<V>> getter) {
            return exactEach(Getters.name(getter), getter);
        }
        /** A part inside a range; only allowed as the last part. Named after the method. */
        public <V extends Comparable<? super V>> CompoundBuilder<E> range(Getter<E, V> getter) {
            return range(Getters.name(getter), getter);
        }
        /** A part searched from its start; only allowed as the last part. Named after the method. */
        public CompoundBuilder<E> prefix(Getter<E, String> getter) {
            return prefix(Getters.name(getter), getter);
        }

        private <V> CompoundBuilder<E> scalar(String name, IndexKind kind, Function<E, V> extractor) {
            Objects.requireNonNull(extractor);
            return part(name, kind, false, e -> Collections.singletonList(extractor.apply(e)));
        }

        private CompoundBuilder<E> part(String name, IndexKind kind, boolean multi, Function<E, ? extends Iterable<?>> extractor) {
            if (name == null || name.isBlank() || name.contains(".")) {
                throw new IllegalArgumentException("Compound part must be nonblank and cannot contain '.'");
            }
            parts.add(new PartExtractor<>(name, kind, multi, Objects.requireNonNull(extractor)));
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
            Object[] firsts = new Object[parts.size()];
            List<Set<Object>> several = null;
            boolean empty = false;
            for (int p = 0; p < parts.size(); p++) {
                Iterable<?> extracted = parts.get(p).extractor().apply(element);
                if (extracted == null) return List.of();
                LinkedHashSet<Object> values = null;
                for (Object value : extracted) {
                    if (value == null) continue;
                    if (values != null) {
                        values.add(value);
                    } else if (firsts[p] == null) {
                        firsts[p] = value;
                    } else if (!firsts[p].equals(value)) {
                        values = new LinkedHashSet<>();
                        values.add(firsts[p]);
                        values.add(value);
                    }
                }
                if (firsts[p] == null) empty = true;
                if (values != null) {
                    if (several == null) several = new ArrayList<>(Collections.nCopies(parts.size(), null));
                    several.set(p, values);
                }
            }
            if (empty) return List.of();
            if (several == null) return List.of(List.of(firsts));
            List<List<Object>> keys = List.of(List.of());
            for (int p = 0; p < parts.size(); p++) {
                Collection<Object> values = several.get(p) != null ? several.get(p) : List.of(firsts[p]);
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
