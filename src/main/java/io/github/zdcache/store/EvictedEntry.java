package io.github.zdcache.store;

public record EvictedEntry<ID, T>(ID id, IndexedEntry<T> entry) {}
