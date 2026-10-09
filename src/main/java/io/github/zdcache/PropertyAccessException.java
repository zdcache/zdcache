package io.github.zdcache;

public final class PropertyAccessException extends IllegalStateException {
    public PropertyAccessException(Class<?> type, String property, Throwable cause) {
        super("Cannot read property '" + property + "' of " + type.getName(), cause);
    }
}
