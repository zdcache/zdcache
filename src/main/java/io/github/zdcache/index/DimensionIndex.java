package io.github.zdcache.index;

import java.util.Set;

/** Package-local indexing SPI; all calls run under a TypeCache lock. */
public interface DimensionIndex<ID> {
    default void validate(Object value) { }
    void add(ID id, Object value);
    void remove(ID id, Object value);
    Set<ID> search(Object query);
    boolean isEmpty();
}
