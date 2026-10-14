package io.github.zdcache.index;

import java.util.function.Consumer;

/** Package-local indexing SPI; all calls run under a TypeCache lock. */
public interface DimensionIndex<ID> {
    default void validate(Object value) { }
    void add(ID id, Object value);
    void remove(ID id, Object value);
    default void replace(ID id, ID replacement, Object value) {
        remove(id, value);
        add(replacement, value);
    }
    void forEachMatch(Object query, Consumer<? super ID> action);
    boolean isEmpty();
}
