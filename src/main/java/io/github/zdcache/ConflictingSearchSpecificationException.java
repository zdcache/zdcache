package io.github.zdcache;

public final class ConflictingSearchSpecificationException extends IllegalArgumentException {
    public ConflictingSearchSpecificationException(Class<?> type) {
        super("Different specification instance already registered for " + type.getName() +
              "; use updateSpecification() to replace it");
    }
}
