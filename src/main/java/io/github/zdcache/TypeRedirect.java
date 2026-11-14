package io.github.zdcache;

import java.util.Objects;

/**
 * Stores and searches a subtype under its supertype's specification. Create it with
 * {@link SearchSpecification#lookupAs} and register it with {@link ZDCache#register(TypeRedirect)}; the
 * subtype then shares the supertype's capacity, indexes and eviction order.
 */
public record TypeRedirect<S>(Class<S> type, SearchSpecification<? super S, ?> target) {
    public TypeRedirect {
        Objects.requireNonNull(type);
        Objects.requireNonNull(target);
        if (type == target.type()) {
            throw new IllegalArgumentException(type.getName() + " cannot be looked up as itself");
        }
        if (!target.type().isAssignableFrom(type)) {
            throw new IllegalArgumentException(
                type.getName() + " is not a subtype of " + target.type().getName());
        }
    }
}
