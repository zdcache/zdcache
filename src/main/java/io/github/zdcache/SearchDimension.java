package io.github.zdcache;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** An indexed leaf path, whose values are extracted from a root object. */
public final class SearchDimension<T> {
    public record Part(String name, IndexKind kind) {}

    private final String path;
    private final IndexKind kind;
    private final List<Part> parts;
    private final boolean multiValued;
    private final Split split;
    private final boolean contains;
    private final Function<T, ? extends Iterable<?>> extractor;

    SearchDimension(String path, IndexKind kind, boolean multiValued, Function<T, ? extends Iterable<?>> extractor) {
        this(path, kind, List.of(), multiValued, null, false, extractor);
    }

    SearchDimension(String path, IndexKind kind, boolean multiValued, Split split, boolean contains,
                    Function<T, ? extends Iterable<?>> extractor) {
        this(path, kind, List.of(), multiValued, split, contains, extractor);
    }

    SearchDimension(String path, IndexKind kind, List<Part> parts, boolean multiValued,
                    Function<T, ? extends Iterable<?>> extractor) {
        this(path, kind, parts, multiValued, null, false, extractor);
    }

    private SearchDimension(String path, IndexKind kind, List<Part> parts, boolean multiValued, Split split, boolean contains,
                            Function<T, ? extends Iterable<?>> extractor) {
        this.path = Objects.requireNonNull(path);
        this.kind = Objects.requireNonNull(kind);
        this.parts = List.copyOf(parts);
        this.multiValued = multiValued;
        if (split != null && kind != IndexKind.PREFIX) throw new IllegalArgumentException("Only a prefix dimension can be split into words");
        this.split = split;
        if (contains && kind != IndexKind.PREFIX) throw new IllegalArgumentException("Only a prefix dimension can be searched inside");
        this.contains = contains;
        this.extractor = Objects.requireNonNull(extractor);
    }

    public String path() { return path; }
    public IndexKind kind() { return kind; }
    public List<Part> parts() { return parts; }
    public boolean multiValued() { return multiValued; }
    public Split split() { return split; }
    public boolean contains() { return contains; }

    SearchDimension<T> withStrategies(TextStrategy... strategies) {
        if (kind != IndexKind.PREFIX) throw new IllegalArgumentException("Only a prefix dimension takes text strategies, and '" + path + "' is " + kind);
        Split nextSplit = split;
        boolean nextContains = contains;
        for (TextStrategy strategy : strategies) {
            switch (Objects.requireNonNull(strategy)) {
                case TextStrategy.Words words -> {
                    if (nextSplit != null && nextSplit != words.split())
                        throw new IllegalArgumentException("'" + path + "' is already split by " + nextSplit + ", not " + words.split());
                    nextSplit = words.split();
                }
                case TextStrategy.Contains ignored -> nextContains = true;
            }
        }
        return new SearchDimension<>(path, kind, parts, multiValued, nextSplit, nextContains, extractor);
    }

    public boolean extractsLike(SearchDimension<?> other) {
        return extractor == other.extractor && path.equals(other.path) && kind == other.kind && parts.equals(other.parts)
            && multiValued == other.multiValued;
    }

    /** Extract a stable, de-duplicated snapshot of index keys. Null values are ignored. */
    public Set<Object> values(T entity) {
        Iterable<?> values = extractor.apply(entity);
        if (values == null) return Collections.emptySet();
        Object first = null;
        LinkedHashSet<Object> result = null;
        for (Object value : values) {
            if (value == null) continue;
            if (kind == IndexKind.COMPOUND) {
                List<?> tuple = (List<?>) value;
                for (int i = 0; i < parts.size(); i++) {
                    requireKind(path, parts.get(i).name(), parts.get(i).kind(), tuple.get(i));
                }
            } else {
                requireKind(path, null, kind, value);
            }
            // Exact/range values must be immutable/equality-stable while indexed.
            if (result != null) {
                result.add(value);
            } else if (first == null) {
                first = value;
            } else if (!first.equals(value)) {
                result = new LinkedHashSet<>();
                result.add(first);
                result.add(value);
            }
        }
        if (result != null) return Collections.unmodifiableSet(result);
        return first == null ? Collections.emptySet() : Collections.singleton(first);
    }

    private static void requireKind(String path, String part, IndexKind kind, Object value) {
        if (kind == IndexKind.PREFIX && !(value instanceof String)) {
            throw new IllegalArgumentException("PREFIX value must be a String at " + at(path, part));
        }
        if (kind == IndexKind.RANGE && !(value instanceof Comparable<?>)) {
            throw new IllegalArgumentException("RANGE value must be Comparable at " + at(path, part));
        }
    }

    private static String at(String path, String part) {
        return part == null ? path : path + "." + part;
    }
}
