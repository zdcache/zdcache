package io.github.zdcache.internal;

import io.github.zdcache.SearchDimension;
import io.github.zdcache.IndexKind;
import io.github.zdcache.ValueRange;
import java.util.ArrayList;
import java.util.List;
import io.github.zdcache.SearchSpecification;
import io.github.zdcache.index.CompoundIndex;
import io.github.zdcache.index.DimensionIndex;
import io.github.zdcache.index.ExactIndex;
import io.github.zdcache.index.PrefixTrieIndex;
import io.github.zdcache.index.RangeIndex;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class DimensionRegistry<T, ID> {
    private final Map<String, DimensionIndex<ID>> indexes;
    private final Map<String, SearchDimension<T>> dimensions = new LinkedHashMap<>();
    private final SearchSpecification<T, ID> specification;

    public DimensionRegistry(SearchSpecification<T, ID> specification) {
        this.specification = specification;
        Map<String, DimensionIndex<ID>> result = new LinkedHashMap<>();
        for (SearchDimension<T> dimension : specification.dimensions()) {
            DimensionIndex<ID> index = switch (dimension.kind()) {
                case PREFIX -> new PrefixTrieIndex<>();
                case EXACT -> new ExactIndex<>();
                case RANGE -> new RangeIndex<>();
                case COMPOUND -> new CompoundIndex<>(dimension.parts().getLast().kind());
            };
            result.put(dimension.path(), index);
            dimensions.put(dimension.path(), dimension);
        }
        this.indexes = Collections.unmodifiableMap(result);
    }

    public Map<String, Set<Object>> extract(T entity) {
        Map<String, Set<Object>> values = new LinkedHashMap<>();
        for (SearchDimension<T> dim : specification.dimensions()) {
            values.put(dim.path(), dim.values(entity));
        }
        return Collections.unmodifiableMap(values);
    }

    public void validate(Map<String, Set<Object>> snapshot) {
        for (var dimension : snapshot.entrySet()) {
            DimensionIndex<ID> index = indexes.get(dimension.getKey());
            Object first = null;
            for (Object value : dimension.getValue()) {
                index.validate(value);
                // An empty RANGE index still needs homogeneous keys within a single PUT.
                if (index instanceof RangeIndex<?> && first != null) {
                    @SuppressWarnings("unchecked")
                    Comparable<Object> next = (Comparable<Object>) value;
                    next.compareTo(first);
                }
                if (first == null) first = value;
            }
        }
    }

    public void add(ID id, Map<String, Set<Object>> snapshot) {
        for (var dimension : snapshot.entrySet()) {
            for (Object value : dimension.getValue()) {
                indexes.get(dimension.getKey()).add(id, value);
            }
        }
    }

    public void remove(ID id, Map<String, Set<Object>> snapshot) {
        for (var dimension : snapshot.entrySet()) {
            for (Object value : dimension.getValue()) {
                indexes.get(dimension.getKey()).remove(id, value);
            }
        }
    }

    public Set<ID> search(String path, Object... values) {
        DimensionIndex<ID> index = indexes.get(path);
        if (index == null) throw new io.github.zdcache.UnknownSearchDimensionException(
            specification.type(), path);
        return new LinkedHashSet<>(index.search(query(dimensions.get(path), values)));
    }

    private static Object query(SearchDimension<?> dimension, Object[] values) {
        String path = dimension.path();
        if (dimension.kind() != IndexKind.COMPOUND) {
            if (values == null || values.length != 1) {
                throw new IllegalArgumentException(path + " takes 1 search value, got " +
                    (values == null ? 0 : values.length));
            }
            return queryValue(path, dimension.kind(), values[0]);
        }
        var parts = dimension.parts();
        if (values == null || values.length != parts.size()) {
            throw new IllegalArgumentException(path + " takes " + parts.size() + " search values " +
                parts.stream().map(SearchDimension.Part::name).toList() + ", got " +
                (values == null ? 0 : values.length));
        }
        List<Object> checked = new ArrayList<>(values.length);
        for (int i = 0; i < values.length; i++) {
            checked.add(queryValue(path + "." + parts.get(i).name(), parts.get(i).kind(), values[i]));
        }
        return List.copyOf(checked);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object queryValue(String path, IndexKind kind, Object value) {
        if (value == null) throw new IllegalArgumentException("Search value for " + path + " must not be null");
        switch (kind) {
            case PREFIX -> {
                if (!(value instanceof String)) {
                    throw new IllegalArgumentException("PREFIX search value must be a String at " + path);
                }
            }
            case RANGE -> {
                if (value instanceof ValueRange<?>) return value;
                if (!(value instanceof Comparable<?> point)) {
                    throw new IllegalArgumentException("RANGE search value must be a ValueRange or Comparable at " + path);
                }
                return ValueRange.between((Comparable) point, (Comparable) point);
            }
            default -> {
                if (value instanceof ValueRange<?>) {
                    throw new IllegalArgumentException("EXACT search value cannot be a ValueRange at " + path);
                }
            }
        }
        return value;
    }
}
