package io.github.zdcache;

public final class ImmutableIdentityDimensionException extends IllegalArgumentException {
    public ImmutableIdentityDimensionException(Class<?> type) {
        super("IdentityDimension cannot change for registered type " + type.getName());
    }
}
