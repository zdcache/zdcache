package io.github.zdcache;

public final class MissingSearchSpecificationStrategyException extends IllegalStateException {
    public MissingSearchSpecificationStrategyException(Class<?> type) {
        super("No searchable dimensions registered for " + type.getName());
    }
}
