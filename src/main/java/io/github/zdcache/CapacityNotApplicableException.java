package io.github.zdcache;

public final class CapacityNotApplicableException extends IllegalStateException {
    public CapacityNotApplicableException(Class<?> type, Class<?> servedBy) {
        super("A capacity is configured for " + type.getName() + ", but it is stored in the " +
              servedBy.getName() + " cache and shares its capacity; configure capacity(" +
              servedBy.getSimpleName() + ".class, ...) instead");
    }
}
