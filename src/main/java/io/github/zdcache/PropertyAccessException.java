package io.github.zdcache;

/** Thrown when a property named in a specification exists but cannot be read, for example because its accessor throws. */
public final class PropertyAccessException extends IllegalStateException {
    public PropertyAccessException(Class<?> type, String property, Throwable cause) {
        super("Cannot read property '" + property + "' of " + type.getName(), cause);
    }
}
