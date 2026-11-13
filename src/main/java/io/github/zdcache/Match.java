package io.github.zdcache;

import java.util.Objects;

public sealed interface Match {
    String text();

    record Prefix(String text) implements Match {
        public Prefix { Objects.requireNonNull(text, "Search text must not be null"); }
    }

    record Words(String text) implements Match {
        public Words { Objects.requireNonNull(text, "Search text must not be null"); }
    }

    static Match prefix(String text) { return new Prefix(text); }
    static Match words(String text) { return new Words(text); }
}
