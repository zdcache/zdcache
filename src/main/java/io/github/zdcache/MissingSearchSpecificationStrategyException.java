package io.github.zdcache;

/** Thrown when searching a type that is not registered or has no searchable dimensions. */
public final class MissingSearchSpecificationStrategyException extends IllegalStateException {
    public MissingSearchSpecificationStrategyException(Class<?> type) {
        super("No searchable dimensions registered for " + type.getName());
    }
}
