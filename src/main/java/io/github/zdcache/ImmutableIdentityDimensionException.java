package io.github.zdcache;

/** Thrown when a replacement specification has another identity dimension instance; build it from the registered one. */
public final class ImmutableIdentityDimensionException extends IllegalArgumentException {
    public ImmutableIdentityDimensionException(Class<?> type) {
        super("IdentityDimension cannot change for registered type " + type.getName());
    }
}
