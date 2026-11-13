package io.github.zdcache;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public enum Split {
    WHITESPACE("\\s+"),
    NON_ALPHANUMERIC("[^\\p{L}\\p{N}]+");

    private final Pattern separator;

    Split(String separator) { this.separator = Pattern.compile(separator); }

    public Set<String> words(String value) {
        Set<String> words = new LinkedHashSet<>();
        for (String word : separator.split(value.toLowerCase(Locale.ROOT))) if (!word.isEmpty()) words.add(word);
        return words;
    }
}
