package io.github.zdcache;

/** Thrown when a specification names a property the type has no readable accessor for. */
public final class UnknownPropertyException extends IllegalArgumentException {
    public UnknownPropertyException(Class<?> type, String property) {
        super(type.getName() + " has no readable property '" + property +
              "' (record component, " + property + "(), getter, or field)");
    }
}
