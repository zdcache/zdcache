package io.github.zdcache.index;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public final class ExactIndex<ID> implements DimensionIndex<ID> {
    private final Map<Object, Object> idsByValue = new HashMap<>();

    @Override public void add(ID id, Object value) {
        idsByValue.compute(value, (ignored, ids) -> Ids.add(ids, id));
    }

    @Override public void remove(ID id, Object value) {
        idsByValue.computeIfPresent(value, (ignored, ids) -> Ids.remove(ids, id));
    }

    @Override public boolean isEmpty() { return idsByValue.isEmpty(); }

    @Override public void forEachMatch(Object query, Consumer<? super ID> action) {
        Ids.forEach(idsByValue.get(query), action);
    }
}
