package io.github.zdcache;

import java.util.Objects;

public sealed interface TextStrategy {
    record Words(Split split) implements TextStrategy {
        public Words { Objects.requireNonNull(split); }
    }

    record Contains() implements TextStrategy { }

    static TextStrategy split() { return new Words(Split.WHITESPACE); }
    static TextStrategy split(Split split) { return new Words(split); }
    static TextStrategy contains() { return new Contains(); }
}
