package io.github.zdcache.index;

import io.github.zdcache.ValueRange;
import java.util.LinkedHashSet;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/** Homogeneous comparable keys are required within a range dimension. */
public final class RangeIndex<ID> implements DimensionIndex<ID> {
    private final NavigableMap<Comparable<Object>, Set<ID>> idsByValue = new TreeMap<>();

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
        idsByValue.computeIfAbsent(key(value), ignored -> new LinkedHashSet<>()).add(id);
    }

    @Override public void remove(ID id, Object value) {
        Set<ID> ids = idsByValue.get(key(value));
        if (ids != null) {
            ids.remove(id);
            if (ids.isEmpty()) idsByValue.remove(key(value));
        }
    }

    @Override public boolean isEmpty() { return idsByValue.isEmpty(); }

    @Override public Set<ID> search(Object query) {
        if (!(query instanceof ValueRange<?> range)) {
            throw new IllegalArgumentException("Range query must be ValueRange.between(from, to)");
        }
        LinkedHashSet<ID> result = new LinkedHashSet<>();
        try {
            for (Set<ID> bucket : idsByValue.subMap(key(range.from()), true,
                                                       key(range.to()), true).values()) {
                result.addAll(bucket);
            }
        } catch (ClassCastException e) {
            throw new IllegalArgumentException("Range query " + range.from().getClass().getName() +
                " cannot be compared with the indexed values", e);
        }
        return result;
    }
}
