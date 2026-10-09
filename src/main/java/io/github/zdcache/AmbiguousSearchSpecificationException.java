package io.github.zdcache;

import java.util.Collection;
import java.util.stream.Collectors;

public final class AmbiguousSearchSpecificationException extends IllegalStateException {
    public AmbiguousSearchSpecificationException(Class<?> type, Collection<Class<?>> candidates) {
        super("Specifications for " + candidates.stream().map(Class::getName).sorted()
              .collect(Collectors.joining(", ")) + " all apply to " + type.getName() +
              "; register a specification or lookupAs() for " + type.getName());
    }
}
