package io.github.zdcache.internal;

import io.github.zdcache.IndexKind;
import io.github.zdcache.SearchDimension;
import io.github.zdcache.SearchSpecification;
import io.github.zdcache.ValueRange;
import io.github.zdcache.index.CompoundIndex;
import io.github.zdcache.index.DimensionIndex;
import io.github.zdcache.index.ExactIndex;
import io.github.zdcache.index.PrefixTrieIndex;
import io.github.zdcache.index.RangeIndex;
import io.github.zdcache.store.IndexedEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

public final class DimensionRegistry<T, ID> {
    private static final Object[] NO_VALUES = new Object[0];

    private record Many(Object[] values) {}

    private final SearchSpecification<T, ID> specification;
    private final List<SearchDimension<T>> dimensions;
    private final List<DimensionIndex<IndexedEntry<T>>> indexes = new ArrayList<>();
    private final Map<String, Integer> positions = new HashMap<>();

    public DimensionRegistry(SearchSpecification<T, ID> specification) {
        this.specification = specification;
        this.dimensions = specification.dimensions();
        for (SearchDimension<T> dimension : dimensions) {
            positions.put(dimension.path(), indexes.size());
            indexes.add(switch (dimension.kind()) {
                case PREFIX -> new PrefixTrieIndex<>();
                case EXACT -> new ExactIndex<>();
                case RANGE -> new RangeIndex<>();
                case COMPOUND -> new CompoundIndex<>(dimension.parts().getLast().kind());
            });
        }
    }

    public Object[] extract(T entity) {
        if (dimensions.isEmpty()) return NO_VALUES;
        Object[] snapshot = new Object[dimensions.size()];
        for (int i = 0; i < snapshot.length; i++) {
            Set<Object> values = dimensions.get(i).values(entity);
            snapshot[i] = switch (values.size()) {
                case 0 -> null;
                case 1 -> values.iterator().next();
                default -> new Many(values.toArray());
            };
        }
        return snapshot;
    }

    private static Object[] values(Object slot) {
        if (slot == null) return NO_VALUES;
        return slot instanceof Many many ? many.values() : new Object[] {slot};
    }

    public void validate(Object[] snapshot) {
        for (int i = 0; i < snapshot.length; i++) {
            DimensionIndex<IndexedEntry<T>> index = indexes.get(i);
            Object first = null;
            for (Object value : values(snapshot[i])) {
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

    public void add(IndexedEntry<T> entry, Object[] snapshot) {
        for (int i = 0; i < snapshot.length; i++) {
            Object slot = snapshot[i];
            if (slot instanceof Many many) {
                for (Object value : many.values()) indexes.get(i).add(entry, value);
            } else if (slot != null) {
                indexes.get(i).add(entry, slot);
            }
        }
    }

    public void remove(IndexedEntry<T> entry, Object[] snapshot) {
        for (int i = 0; i < snapshot.length; i++) {
            Object slot = snapshot[i];
            if (slot instanceof Many many) {
                for (Object value : many.values()) indexes.get(i).remove(entry, value);
            } else if (slot != null) {
                indexes.get(i).remove(entry, slot);
            }
        }
    }

    public void replace(IndexedEntry<T> previous, Object[] before, IndexedEntry<T> entry, Object[] after) {
        if (before.length != after.length) {
            remove(previous, before);
            add(entry, after);
            return;
        }
        for (int i = 0; i < after.length; i++) {
            DimensionIndex<IndexedEntry<T>> index = indexes.get(i);
            if (sameValues(before[i], after[i])) {
                if (previous == entry) continue;
                if (after[i] instanceof Many many) {
                    for (Object value : many.values()) index.replace(previous, entry, value);
                } else if (after[i] != null) {
                    index.replace(previous, entry, after[i]);
                }
            } else {
                if (before[i] instanceof Many many) {
                    for (Object value : many.values()) index.remove(previous, value);
                } else if (before[i] != null) {
                    index.remove(previous, before[i]);
                }
                if (after[i] instanceof Many many) {
                    for (Object value : many.values()) index.add(entry, value);
                } else if (after[i] != null) {
                    index.add(entry, after[i]);
                }
            }
        }
    }

    private static boolean sameValues(Object before, Object after) {
        if (before instanceof Many a && after instanceof Many b) return Arrays.equals(a.values(), b.values());
        return Objects.equals(before, after);
    }

    public boolean multiValued(String path) {
        return dimensions.get(position(path)).multiValued();
    }

    public void forEachMatch(String path, Object[] values, Consumer<? super IndexedEntry<T>> action) {
        int position = position(path);
        indexes.get(position).forEachMatch(query(dimensions.get(position), values), action);
    }

    private int position(String path) {
        Integer position = positions.get(path);
        if (position == null) throw new io.github.zdcache.UnknownSearchDimensionException(specification.type(), path);
        return position;
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
