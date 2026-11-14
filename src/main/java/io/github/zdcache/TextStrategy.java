package io.github.zdcache;

import java.util.Objects;

/**
 * A search strategy added to a prefix dimension, each with its own index: {@link #split()} for words
 * and {@link #contains()} for text anywhere. Declare them with {@code prefix(getter, strategies)}, or
 * add them to a cached type with {@link SearchSpecification#withStrategies}. Fuzzy search needs none.
 */
public sealed interface TextStrategy {
    /** Words, split by the given rule. */
    record Words(Split split) implements TextStrategy {
        public Words { Objects.requireNonNull(split); }
    }

    /** Text anywhere, found through an index of three-character pieces. */
    record Contains() implements TextStrategy { }

    /** Words split on whitespace. */
    static TextStrategy split() { return new Words(Split.WHITESPACE); }
    /** Words split by the given rule; a dimension's split cannot change once declared. */
    static TextStrategy split(Split split) { return new Words(split); }
    /** Text anywhere in the value. */
    static TextStrategy contains() { return new Contains(); }
}
