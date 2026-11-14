package io.github.zdcache;

/**
 * How exactly an identity store keeps least-recently-used order. The buffered LRU store, the
 * default, supports both; the LinkedHashMap store supports only {@link #STRICT} and the Caffeine store
 * only {@link #APPROXIMATE}. {@link ZDCache.Builder#build()} refuses a recency the store does not
 * support, and without one the store uses its own.
 */
public enum Recency {
    /** Every get and put moves the entry in exact LRU order, so the least recently used entry is evicted first. */
    STRICT,
    /** Under heavy contention the store may drop some recency updates and exceed capacity until its next maintenance, for more throughput from many threads. */
    APPROXIMATE
}
