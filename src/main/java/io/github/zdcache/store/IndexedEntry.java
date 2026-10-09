package io.github.zdcache.store;

import java.util.Map;
import java.util.Set;

/** Internally stored entity plus values recorded at its most recent explicit insert/reindex. */
public final class IndexedEntry<T> {
    private final T entity;
    private Map<String, Set<Object>> snapshot;

    public IndexedEntry(T entity, Map<String, Set<Object>> snapshot) {
        this.entity = entity;
        this.snapshot = snapshot;
    }

    public T entity() { return entity; }
    public Map<String, Set<Object>> snapshot() { return snapshot; }

    /** Only called under the owning TypeCache lock after a successful full index rebuild. */
    public void replaceSnapshot(Map<String, Set<Object>> replacement) {
        this.snapshot = replacement;
    }
}
