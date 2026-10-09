package io.github.zdcache;

import java.util.Objects;

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
