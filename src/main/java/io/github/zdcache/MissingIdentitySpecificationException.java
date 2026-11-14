package io.github.zdcache;

/** Thrown when a type has no registered specification to read or change; register one, or put with an explicit id. */
public final class MissingIdentitySpecificationException extends IllegalStateException {
    public MissingIdentitySpecificationException(Class<?> type) {
        super("No identity specification registered for " + type.getName() +
              "; use put(entity, specification) or put(type, id, entity)");
    }
}
