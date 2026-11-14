package io.github.zdcache.store;

/** An entry an identity store evicted, handed back so its index entries can be removed. */
public record EvictedEntry<ID, T>(ID id, IndexedEntry<T> entry) {}
