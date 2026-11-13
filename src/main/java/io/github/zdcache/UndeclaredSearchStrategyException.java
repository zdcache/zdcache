package io.github.zdcache;

public final class UndeclaredSearchStrategyException extends IllegalArgumentException {
    public UndeclaredSearchStrategyException(Class<?> type, String path, String strategy, String declaration) {
        super("Search dimension '" + path + "' of " + type.getName() + " has no " + strategy + " strategy; declare it with " + declaration);
    }
}
