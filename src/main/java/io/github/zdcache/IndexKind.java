package io.github.zdcache;

/** The kind of index behind a {@link SearchDimension}. */
public enum IndexKind {
    /** Text searched from its start, ignoring case, in a trie; words, fuzzy and contains searches build on it. */
    PREFIX,
    /** Values equal to the search value. */
    EXACT,
    /** Comparable values inside a {@link ValueRange}. */
    RANGE,
    /** Several parts searched together; only the last part can be a prefix or a range. */
    COMPOUND
}
