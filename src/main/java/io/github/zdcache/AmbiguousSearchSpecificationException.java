package io.github.zdcache;

import java.util.Collection;
import java.util.stream.Collectors;

/** Thrown when a type implements several interfaces with their own specifications at the same distance; register one for the type, or a {@link TypeRedirect}. */
public final class AmbiguousSearchSpecificationException extends IllegalStateException {
    public AmbiguousSearchSpecificationException(Class<?> type, Collection<Class<?>> candidates) {
        super("Specifications for " + candidates.stream().map(Class::getName).sorted()
              .collect(Collectors.joining(", ")) + " all apply to " + type.getName() +
              "; register a specification or lookupAs() for " + type.getName());
    }
}
