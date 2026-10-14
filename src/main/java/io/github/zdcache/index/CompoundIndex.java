package io.github.zdcache.index;

import io.github.zdcache.IndexKind;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class CompoundIndex<ID> implements DimensionIndex<ID> {
    private final IndexKind lastKind;
    private final Map<List<Object>, DimensionIndex<ID>> buckets = new HashMap<>();

    public CompoundIndex(IndexKind lastKind) {
        if (lastKind == IndexKind.COMPOUND) throw new IllegalArgumentException("Compounds cannot nest");
        this.lastKind = lastKind;
    }

    private DimensionIndex<ID> newBucket() {
        return switch (lastKind) {
            case PREFIX -> new PrefixTrieIndex<>();
            case EXACT -> new ExactIndex<>();
            case RANGE -> new RangeIndex<>();
            case COMPOUND -> throw new IllegalStateException();
        };
    }

    @SuppressWarnings("unchecked")
    private static List<Object> tuple(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("Compound values must be non-empty tuples");
        }
        return (List<Object>) list;
    }

    private static List<Object> head(List<Object> tuple) {
        return tuple.subList(0, tuple.size() - 1);
    }

    private static Object last(List<Object> tuple) {
        return tuple.get(tuple.size() - 1);
    }

    @Override public void validate(Object value) {
        List<Object> tuple = tuple(value);
        DimensionIndex<ID> bucket = buckets.get(head(tuple));
        (bucket == null ? newBucket() : bucket).validate(last(tuple));
    }

    @Override public void add(ID id, Object value) {
        List<Object> tuple = tuple(value);
        List<Object> head = head(tuple);
        DimensionIndex<ID> bucket = buckets.get(head);
        if (bucket == null) buckets.put(List.copyOf(head), bucket = newBucket());
        bucket.add(id, last(tuple));
    }

    @Override public void remove(ID id, Object value) {
        List<Object> tuple = tuple(value);
        List<Object> head = head(tuple);
        DimensionIndex<ID> bucket = buckets.get(head);
        if (bucket != null) {
            bucket.remove(id, last(tuple));
            if (bucket.isEmpty()) buckets.remove(head);
        }
    }

    @Override public void replace(ID id, ID replacement, Object value) {
        List<Object> tuple = tuple(value);
        DimensionIndex<ID> bucket = buckets.get(head(tuple));
        if (bucket == null) add(replacement, value);
        else bucket.replace(id, replacement, last(tuple));
    }

    @Override public boolean isEmpty() { return buckets.isEmpty(); }

    @Override public void forEachMatch(Object query, Consumer<? super ID> action) {
        List<Object> values = tuple(query);
        DimensionIndex<ID> bucket = buckets.get(head(values));
        if (bucket != null) bucket.forEachMatch(last(values), action);
    }
}
