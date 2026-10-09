package io.github.zdcache.index;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class ExactIndex<ID> implements DimensionIndex<ID> {
    private final Map<Object, Set<ID>> idsByValue = new HashMap<>();

    @Override public void add(ID id, Object value) {
        idsByValue.computeIfAbsent(value, ignored -> new LinkedHashSet<>()).add(id);
    }

    @Override public void remove(ID id, Object value) {
        Set<ID> ids = idsByValue.get(value);
        if (ids != null) {
            ids.remove(id);
            if (ids.isEmpty()) idsByValue.remove(value);
        }
    }

    @Override public boolean isEmpty() { return idsByValue.isEmpty(); }

    @Override public Set<ID> search(Object query) {
        return new LinkedHashSet<>(idsByValue.getOrDefault(query, Set.of()));
    }
}
