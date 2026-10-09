package io.github.zdcache;

public final class UnknownSearchDimensionException extends IllegalArgumentException {
    public UnknownSearchDimensionException(Class<?> type, String path) {
        super("Unknown search dimension '" + path + "' for " + type.getName());
    }
}
