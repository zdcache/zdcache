package io.github.zdcache;

public final class MissingIdentitySpecificationException extends IllegalStateException {
    public MissingIdentitySpecificationException(Class<?> type) {
        super("No identity specification registered for " + type.getName() +
              "; use put(entity, specification) or put(type, id, entity)");
    }
}
