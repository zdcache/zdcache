package io.github.zdcache;

import java.util.Objects;

/** Inclusive bounds, e.g. ValueRange.between(20, 30). */
public record ValueRange<V extends Comparable<? super V>>(V from, V to) {
    public ValueRange {
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        if (from.compareTo(to) > 0) {
            throw new IllegalArgumentException("Range lower bound exceeds upper bound");
        }
    }

    public static <V extends Comparable<? super V>> ValueRange<V> between(V from, V to) {
        return new ValueRange<>(from, to);
    }
}
