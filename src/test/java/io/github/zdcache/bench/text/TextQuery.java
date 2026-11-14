package io.github.zdcache.bench.text;

import java.util.Locale;
import java.util.Random;
import io.github.zdcache.bench.Names;

public record TextQuery(Kind kind, String text) {
    public enum Kind {
        PREFIX, WORDS, WORDS_RANKED, FUZZY_WORDS_ONE_EDIT, FUZZY_WORDS, FUZZY_WHOLE_ONE_EDIT, FUZZY_WHOLE_TWO_EDITS, CONTAINS, CONTAINS_TWO,
        WORDS_BEST, FUZZY_WORDS_BEST, CONTAINS_BEST;

        public Kind searched() {
            return switch (this) {
                case WORDS_BEST -> WORDS;
                case FUZZY_WORDS_BEST -> FUZZY_WORDS;
                case CONTAINS_BEST -> CONTAINS;
                default -> this;
            };
        }

        public boolean best() { return searched() != this; }
    }

    public static final int BEST = 20;

    public static TextQuery of(Kind kind, String name, Random random) {
        String[] parts = name.split(" ");
        String last = parts[parts.length - 1];
        int from = 1 + random.nextInt(Math.max(1, last.length() - 4));
        String text = switch (kind.searched()) {
            case PREFIX -> name.substring(0, 2);
            case WORDS, WORDS_RANKED -> parts[0] + " " + last.substring(0, Math.min(3, last.length()));
            case FUZZY_WORDS_ONE_EDIT, FUZZY_WORDS -> parts[0] + " " + Names.typo(last, random);
            case FUZZY_WHOLE_ONE_EDIT, FUZZY_WHOLE_TWO_EDITS -> Names.typo(name, random);
            case CONTAINS -> last.substring(from, Math.min(last.length(), from + 4));
            case CONTAINS_TWO -> last.substring(from, Math.min(last.length(), from + 2));
            default -> throw new IllegalArgumentException(kind.toString());
        };
        return new TextQuery(kind, text.toLowerCase(Locale.ROOT));
    }

    public Integer maxEdits() {
        return switch (kind) {
            case FUZZY_WORDS_ONE_EDIT, FUZZY_WHOLE_ONE_EDIT -> 1;
            case FUZZY_WHOLE_TWO_EDITS -> 2;
            default -> null;
        };
    }

    public static int editsFor(String term) { return term.length() <= 2 ? 0 : term.length() <= 5 ? 1 : 2; }
}
