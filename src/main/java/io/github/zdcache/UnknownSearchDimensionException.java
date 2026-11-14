package io.github.zdcache;

/** Thrown when searching a dimension that the type's specification does not have. */
public final class UnknownSearchDimensionException extends IllegalArgumentException {
    public UnknownSearchDimensionException(Class<?> type, String path) {
        super("Unknown search dimension '" + path + "' for " + type.getName());
    }
}
