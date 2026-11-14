package io.github.zdcache.store;

/** Internally stored entity plus values recorded at its most recent explicit insert/reindex. */
public final class IndexedEntry<T> {
    private volatile T entity;
    private Object[] snapshot;
    private static final int EVICTED = 1 << 31;
    private static final int ORDINAL = ~EVICTED;
    private static final int UNNUMBERED = ORDINAL;
    private static final java.lang.invoke.VarHandle STATE;
    static {
        try { STATE = java.lang.invoke.MethodHandles.lookup().findVarHandle(IndexedEntry.class, "state", int.class); }
        catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }
    private volatile int state = UNNUMBERED;

    public IndexedEntry(T entity, Object[] snapshot) {
        this.entity = entity;
        this.snapshot = snapshot;
    }

    public T entity() { return entity; }
    public Object[] snapshot() { return snapshot; }
    public boolean evicted() { return (state & EVICTED) != 0; }
    /** A number unique among the entries a type's indexes hold, used to intersect search results; -1 when not indexed. */
    public int ordinal() {
        int ordinal = state & ORDINAL;
        return ordinal == UNNUMBERED ? -1 : ordinal;
    }
    public void ordinal(int ordinal) {
        int number = ordinal < 0 ? UNNUMBERED : ordinal;
        int current;
        do { current = state; } while (!STATE.compareAndSet(this, current, (current & EVICTED) | number));
    }

    public void markEvicted() {
        STATE.getAndBitwiseOr(this, EVICTED);
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
