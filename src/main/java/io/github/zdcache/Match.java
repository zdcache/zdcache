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

    record Fuzzy(String text, Integer maxEdits) implements Match {
        public static final int MOST_EDITS = 2;

        public Fuzzy {
            Objects.requireNonNull(text, "Search text must not be null");
            if (maxEdits != null && (maxEdits < 0 || maxEdits > MOST_EDITS))
                throw new IllegalArgumentException("A fuzzy search allows 0 to " + MOST_EDITS + " edits, not " + maxEdits);
        }

        public int editsFor(String term) {
            if (maxEdits != null) return maxEdits;
            return term.length() <= 2 ? 0 : term.length() <= 5 ? 1 : MOST_EDITS;
        }
    }

    static Match prefix(String text) { return new Prefix(text); }
    static Match fuzzy(String text) { return new Fuzzy(text, null); }
    static Match fuzzy(String text, int maxEdits) { return new Fuzzy(text, maxEdits); }
    static Match words(String text) { return new Words(text); }
}
