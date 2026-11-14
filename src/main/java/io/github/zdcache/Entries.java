package io.github.zdcache;

/**
 * Whether putting an id again keeps its entry object or creates a new one. Keeping it means an
 * update only touches the indexes whose value changed.
 */
public enum Entries {
    /** Every put stores a new entry object; costs more per put on a type with indexes. */
    PER_PUT,
    /** One entry object per id, updated in place when a type with indexes is put again; the default. */
    PER_ID
}
