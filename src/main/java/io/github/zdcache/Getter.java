package io.github.zdcache;

import java.io.Serializable;
import java.util.function.Function;

/**
 * A method reference, such as {@code Person::name}, that reads a value and names a dimension or an
 * identity after the method, so a dimension follows its property when the property is renamed.
 * It is {@link java.io.Serializable} so that zdcache can find out which method it refers to.
 */
@FunctionalInterface
public interface Getter<T, V> extends Function<T, V>, Serializable {
}
