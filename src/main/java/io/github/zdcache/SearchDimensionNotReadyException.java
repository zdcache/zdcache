package io.github.zdcache;

public final class SearchDimensionNotReadyException extends IllegalStateException {
    public SearchDimensionNotReadyException(Class<?> type, String path) {
        super("Search dimension '" + path + "' of " + type.getName() + " is still being built; it can be searched once its specification is committed");
    }
}
