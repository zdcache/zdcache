package io.github.zdcache;

/** Thrown when a search asks for a strategy, such as words or contains, that the dimension does not declare. */
public final class UndeclaredSearchStrategyException extends IllegalArgumentException {
    public UndeclaredSearchStrategyException(Class<?> type, String path, String strategy, String declaration) {
        super("Search dimension '" + path + "' of " + type.getName() + " has no " + strategy + " strategy; declare it with " + declaration);
    }
}
