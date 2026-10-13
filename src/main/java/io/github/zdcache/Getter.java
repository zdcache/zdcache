package io.github.zdcache;

import java.io.Serializable;
import java.util.function.Function;

@FunctionalInterface
public interface Getter<T, V> extends Function<T, V>, Serializable {
}
