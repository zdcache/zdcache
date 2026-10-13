package io.github.zdcache.index;

import io.github.zdcache.ValueRange;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.Consumer;

/** Homogeneous comparable keys are required within a range dimension. */
public final class RangeIndex<ID> implements DimensionIndex<ID> {
    private final NavigableMap<Comparable<Object>, Object> idsByValue = new TreeMap<>();

    @SuppressWarnings("unchecked")
    private Comparable<Object> key(Object value) {
        if (!(value instanceof Comparable<?>)) {
            throw new IllegalArgumentException("Range keys must implement Comparable");
        }
        return (Comparable<Object>) value;
    }

    @Override public void validate(Object value) {
        var candidate = key(value);
        if (!idsByValue.isEmpty()) {
            // Fail fast for incompatible Comparable classes before a cache replacement starts.
            candidate.compareTo(idsByValue.firstKey());
        }
    }

    @Override public void add(ID id, Object value) {
        idsByValue.compute(key(value), (ignored, ids) -> Ids.add(ids, id));
    }

    @Override public void remove(ID id, Object value) {
        idsByValue.computeIfPresent(key(value), (ignored, ids) -> Ids.remove(ids, id));
    }

    @Override public boolean isEmpty() { return idsByValue.isEmpty(); }

    @Override public void forEachMatch(Object query, Consumer<? super ID> action) {
        if (!(query instanceof ValueRange<?> range)) {
            throw new IllegalArgumentException("Range query must be ValueRange.between(from, to)");
        }
        NavigableMap<Comparable<Object>, Object> matching;
        try {
            matching = idsByValue.subMap(key(range.from()), true, key(range.to()), true);
            for (Object ids : matching.values()) Ids.forEach(ids, action);
        } catch (ClassCastException e) {
            throw new IllegalArgumentException("Range query " + range.from().getClass().getName() +
                " cannot be compared with the indexed values", e);
        }
    }
}
