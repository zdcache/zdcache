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
    private final Function<T, ? extends Iterable<?>> extractor;

    SearchDimension(String path, IndexKind kind, Function<T, ? extends Iterable<?>> extractor) {
        this(path, kind, List.of(), extractor);
    }

    SearchDimension(String path, IndexKind kind, List<Part> parts, Function<T, ? extends Iterable<?>> extractor) {
        this.path = Objects.requireNonNull(path);
        this.kind = Objects.requireNonNull(kind);
        this.parts = List.copyOf(parts);
        this.extractor = Objects.requireNonNull(extractor);
    }

    public String path() { return path; }
    public IndexKind kind() { return kind; }
    public List<Part> parts() { return parts; }

    /** Extract a stable, de-duplicated snapshot of index keys. Null values are ignored. */
    public Set<Object> values(T entity) {
        LinkedHashSet<Object> result = new LinkedHashSet<>();
        Iterable<?> values = extractor.apply(entity);
        if (values != null) {
            for (Object value : values) {
                if (value != null) {
                    if (kind == IndexKind.COMPOUND) {
                        List<?> tuple = (List<?>) value;
                        for (int i = 0; i < parts.size(); i++) {
                            requireKind(path + "." + parts.get(i).name(), parts.get(i).kind(), tuple.get(i));
                        }
                    } else {
                        requireKind(path, kind, value);
                    }
                    // Exact/range values must be immutable/equality-stable while indexed.
                    result.add(value);
                }
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static void requireKind(String path, IndexKind kind, Object value) {
        if (kind == IndexKind.PREFIX && !(value instanceof String)) {
            throw new IllegalArgumentException("PREFIX value must be a String at " + path);
        }
        if (kind == IndexKind.RANGE && !(value instanceof Comparable<?>)) {
            throw new IllegalArgumentException("RANGE value must be Comparable at " + path);
        }
    }
}
