package io.github.zdcache;

import java.util.Objects;

/**
 * How a search matches text on a prefix dimension. A plain string searches like {@link #prefix}.
 * {@link #words} needs {@code prefixSplit}, {@link #contains} needs {@code prefixContains}, and
 * {@link #fuzzy} works on any prefix dimension; asking for a strategy the dimension does not declare
 * throws {@link UndeclaredSearchStrategyException}. Words, fuzzy and contains searches return the best
 * matches first.
 */
public sealed interface Match {
    String text();

    /** The whole value starts with the text. */
    record Prefix(String text) implements Match {
        public Prefix { Objects.requireNonNull(text, "Search text must not be null"); }
    }

    /** Every word of the text starts a word of the value, in any order. */
    record Words(String text) implements Match {
        public Words { Objects.requireNonNull(text, "Search text must not be null"); }
    }

    /** The value, or on a split dimension each word, is within a few edits of the text. */
    record Fuzzy(String text, Integer maxEdits) implements Match {
        /** The most edits a fuzzy search allows. */
        public static final int MOST_EDITS = 2;

        public Fuzzy {
            Objects.requireNonNull(text, "Search text must not be null");
            if (maxEdits != null && (maxEdits < 0 || maxEdits > MOST_EDITS))
                throw new IllegalArgumentException("A fuzzy search allows 0 to " + MOST_EDITS + " edits, not " + maxEdits);
        }

        /** The edits allowed for one term: maxEdits when given, otherwise 0 up to two characters, 1 up to five and 2 beyond. */
        public int editsFor(String term) {
            if (maxEdits != null) return maxEdits;
            return term.length() <= 2 ? 0 : term.length() <= 5 ? 1 : MOST_EDITS;
        }
    }

    /** The text appears anywhere in the value. */
    record Contains(String text) implements Match {
        public Contains { Objects.requireNonNull(text, "Search text must not be null"); }
    }

    /** Searches the whole value from its start, as a plain string does. */
    static Match prefix(String text) { return new Prefix(text); }
    /** Finds the text anywhere in the value; needs {@code prefixContains}, and one or two characters look through every value. */
    static Match contains(String text) { return new Contains(text); }
    /** Allows typos, an edit being an insertion, deletion, substitution or swap of neighbours: 0 up to two characters, 1 up to five, 2 beyond. */
    static Match fuzzy(String text) { return new Fuzzy(text, null); }
    /** Allows up to maxEdits edits, from 0 to 2. */
    static Match fuzzy(String text, int maxEdits) { return new Fuzzy(text, maxEdits); }
    /** Finds values where every word of the text starts a word; needs {@code prefixSplit}. */
    static Match words(String text) { return new Words(text); }
}
