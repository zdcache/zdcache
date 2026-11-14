package io.github.zdcache;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** How {@code prefixSplit} cuts a value into words; words are lowercased and each is indexed once. */
public enum Split {
    /** Splits on spaces, tabs and line breaks; the default. */
    WHITESPACE("\\s+"),
    /** Splits on everything but letters and digits, so hyphens and punctuation separate words too. */
    NON_ALPHANUMERIC("[^\\p{L}\\p{N}]+");

    private final Pattern separator;

    Split(String separator) { this.separator = Pattern.compile(separator); }

    /** The distinct, lowercased words of the value, in order. */
    public Set<String> words(String value) {
        Set<String> words = new LinkedHashSet<>();
        for (String word : separator.split(value.toLowerCase(Locale.ROOT))) if (!word.isEmpty()) words.add(word);
        return words;
    }
}
