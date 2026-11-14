package io.github.zdcache;

/**
 * How the cache locks its types. Every single call is atomic on its own; this decides whether
 * writes to different types can run at the same time. Searches, {@code peek} and {@code values}
 * run in parallel either way, and {@link ZDCache#atomically(Runnable)} works with both.
 */
public enum Locking {
    /** One read-write lock per type, so writes to different types run in parallel; the default. */
    PER_TYPE,
    /** One lock shared by every type: writes wait for each other, and an atomically block takes one lock instead of one per type. */
    SINGLE
}
