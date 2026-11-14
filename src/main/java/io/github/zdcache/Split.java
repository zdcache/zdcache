package io.github.zdcache;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** How {@code prefixSplit} cuts a value into words; words are lowercased and each is indexed once. */
public enum Split {
    /** Splits on spaces, tabs and line breaks; the default. */
    WHITESPACE,
    /** Splits on everything but letters and digits, so hyphens and punctuation separate words too. */
    NON_ALPHANUMERIC;

    /** Whether this code point ends a word. */
    public boolean separates(int codePoint) {
        return switch (this) {
            case WHITESPACE -> codePoint == ' ' || codePoint == '\t' || codePoint == '\n' || codePoint == 0x0B
                || codePoint == '\f' || codePoint == '\r';
            case NON_ALPHANUMERIC -> {
                if (Character.isLetter(codePoint)) yield false;
                int type = Character.getType(codePoint);
                yield type != Character.DECIMAL_DIGIT_NUMBER && type != Character.LETTER_NUMBER && type != Character.OTHER_NUMBER;
            }
        };
    }

    /** The distinct, lowercased words of the value, in order. */
    public Set<String> words(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        Set<String> words = new LinkedHashSet<>();
        int start = -1;
        for (int i = 0; i < lower.length(); ) {
            int codePoint = lower.codePointAt(i);
            if (separates(codePoint)) {
                if (start >= 0) words.add(lower.substring(start, i));
                start = -1;
            } else if (start < 0) start = i;
            i += Character.charCount(codePoint);
        }
        if (start >= 0) words.add(lower.substring(start));
        return words;
    }
}
