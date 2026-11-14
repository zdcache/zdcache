package io.github.zdcache;

/** Thrown when searching what a prepared specification change is still building; it can be searched once the change is committed. */
public final class SearchDimensionNotReadyException extends IllegalStateException {
    public SearchDimensionNotReadyException(Class<?> type, String path) {
        super("Search dimension '" + path + "' of " + type.getName() + " is still being built; it can be searched once its specification is committed");
    }
}
