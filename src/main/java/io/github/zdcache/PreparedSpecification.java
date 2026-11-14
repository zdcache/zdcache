package io.github.zdcache;

/**
 * A specification change that is built but not yet switched on, from {@link ZDCache#prepareSpecification}.
 * Writes keep going and are included; searching what is still being built throws
 * {@link SearchDimensionNotReadyException}. Commit or abandon it; one change per type can be prepared at a time.
 */
public interface PreparedSpecification {
    /** Switches the change on in one short step, including the writes made since it was prepared. */
    void commit();
    /** Drops the change; the specification stays as it was. */
    void abandon();
}
