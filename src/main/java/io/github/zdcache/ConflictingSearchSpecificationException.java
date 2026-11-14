package io.github.zdcache;

/** Thrown when a type is given another specification instance than the one registered; pass the same instance, or change it with {@code updateSpecification}. */
public final class ConflictingSearchSpecificationException extends IllegalArgumentException {
    public ConflictingSearchSpecificationException(Class<?> type) {
        super("Different specification instance already registered for " + type.getName() +
              "; use updateSpecification() to replace it");
    }
}
