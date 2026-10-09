package io.github.zdcache;

public final class UnknownPropertyException extends IllegalArgumentException {
    public UnknownPropertyException(Class<?> type, String property) {
        super(type.getName() + " has no readable property '" + property +
              "' (record component, " + property + "(), getter, or field)");
    }
}
