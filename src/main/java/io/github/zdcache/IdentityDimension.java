package io.github.zdcache;

import io.github.zdcache.internal.Getters;
import java.util.Objects;
import java.util.function.Function;

/**
 * Stable entity identity. Keep and reuse the SAME instance when evolving a search specification;
 * this deliberately prevents accidentally swapping a key extractor with different semantics.
 */
public final class IdentityDimension<T, ID> {
    private final Class<T> type;
    private final String name;
    private final Function<T, ID> extractor;

    private IdentityDimension(Class<T> type, String name, Function<T, ID> extractor) {
        this.type = Objects.requireNonNull(type);
        this.name = Objects.requireNonNull(name);
        this.extractor = extractor;
    }

    public static <T, ID> IdentityDimension<T, ID> of(
            Class<T> type, String name, Function<T, ID> extractor) {
        return new IdentityDimension<>(type, name, Objects.requireNonNull(extractor));
    }

    public static <T, ID> IdentityDimension<T, ID> of(Getter<T, ID> getter) {
        return new IdentityDimension<>(Getters.type(getter), Getters.name(getter), getter);
    }

    /** Identity supplied to cache.put(Class, ID, T), instead of extracted from T. */
    public static <T, ID> IdentityDimension<T, ID> explicit(Class<T> type) {
        return new IdentityDimension<>(type, "$explicit", null);
    }

    public Class<T> type() { return type; }
    public String name() { return name; }
    public boolean isExplicit() { return extractor == null; }

    public ID extract(T entity) {
        if (isExplicit()) {
            throw new IllegalStateException("Identity is explicit; use put(Class, ID, T)");
        }
        return Objects.requireNonNull(extractor.apply(entity), "Entity identity must not be null");
    }

    /** Intentionally uses reference identity; do not override equals/hashCode. */
}
