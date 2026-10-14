package io.github.zdcache.store;

/** Internally stored entity plus values recorded at its most recent explicit insert/reindex. */
public final class IndexedEntry<T> {
    private volatile T entity;
    private Object[] snapshot;
    private volatile boolean evicted;

    public IndexedEntry(T entity, Object[] snapshot) {
        this.entity = entity;
        this.snapshot = snapshot;
    }

    public T entity() { return entity; }
    public Object[] snapshot() { return snapshot; }
    public boolean evicted() { return evicted; }

    public void markEvicted() {
        evicted = true;
    }

    public void update(T replacement, Object[] replacementSnapshot) {
        this.snapshot = replacementSnapshot;
        this.entity = replacement;
    }

    /** Only called under the owning TypeCache lock after a successful full index rebuild. */
    public void replaceSnapshot(Object[] replacement) {
        this.snapshot = replacement;
    }
}
