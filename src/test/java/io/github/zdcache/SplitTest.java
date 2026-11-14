package io.github.zdcache;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SplitTest {
    private static Set<String> byPattern(String pattern, String value) {
        Set<String> words = new LinkedHashSet<>();
        for (String word : Pattern.compile(pattern).split(value.toLowerCase(Locale.ROOT))) if (!word.isEmpty()) words.add(word);
        return words;
    }

    @Test void wordsAreSplitExactlyAsTheRegularExpressionsDescribe() {
        String alphabet = "abcÅæøé ÉZ019\t\n\u000B\f\r-_.,'  Ⅰ½😀𐐀";
        Random random = new Random(3);
        for (int round = 0; round < 5_000; round++) {
            StringBuilder value = new StringBuilder();
            for (int i = random.nextInt(14); i > 0; i--) {
                int at = random.nextInt(alphabet.length());
                if (Character.isHighSurrogate(alphabet.charAt(at))) value.append(alphabet, at, at + 2);
                else if (Character.isLowSurrogate(alphabet.charAt(at))) value.append(alphabet, at - 1, at + 1);
                else value.append(alphabet.charAt(at));
            }
            String text = value.toString();
            assertEquals(byPattern("\\s+", text), Split.WHITESPACE.words(text), text);
            assertEquals(byPattern("[^\\p{L}\\p{N}]+", text), Split.NON_ALPHANUMERIC.words(text), text);
        }
    }
}
