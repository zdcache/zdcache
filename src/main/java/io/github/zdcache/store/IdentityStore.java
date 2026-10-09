package io.github.zdcache.store;

import java.util.List;
import java.util.Map;

/**
 * Per-type identity store SPI. All calls are serialized by TypeCache's external lock.
 * snapshot() must NOT promote entries in the eviction policy.
 * settle()/drainEvictions() allow coordinated, synchronous secondary-index cleanup.
 */
public interface IdentityStore<ID, T> {
    IndexedEntry<T> get(ID id);
    IndexedEntry<T> peek(ID id);
    IndexedEntry<T> put(ID id, IndexedEntry<T> entry);
    IndexedEntry<T> remove(ID id);
    Map<ID, IndexedEntry<T>> snapshot();
    boolean contains(ID id);
    int size();
    void settle();
    List<EvictedEntry<ID, T>> drainEvictions();
}
