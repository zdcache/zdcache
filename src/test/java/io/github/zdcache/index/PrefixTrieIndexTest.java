package io.github.zdcache.index;

import io.github.zdcache.store.IndexedEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrefixTrieIndexTest {

    private static List<Integer> matches(PrefixTrieIndex<Integer> index, String prefix) {
        List<Integer> ids = new ArrayList<>();
        index.forEachMatch(prefix, ids::add);
        return ids.stream().sorted().toList();
    }

    @Test void findsValuesByEveryPrefixIncludingWordsThatArePrefixesOfOthers() {
        var index = new PrefixTrieIndex<Integer>();
        index.add(1, "Simon");
        index.add(2, "Simone");
        index.add(3, "Si");
        index.add(4, "Siri");
        index.add(5, "Peter");

        assertEquals(List.of(1, 2, 3, 4, 5), matches(index, ""));
        assertEquals(List.of(1, 2, 3, 4), matches(index, "SI"));
        assertEquals(List.of(1, 2), matches(index, "sim"));
        assertEquals(List.of(1, 2), matches(index, "simon"));
        assertEquals(List.of(2), matches(index, "simone"));
        assertEquals(List.of(), matches(index, "simonex"));
        assertEquals(List.of(), matches(index, "sx"));
        assertEquals(List.of(5), matches(index, "p"));
    }

    @Test void severalIdsCanShareOneValue() {
        var index = new PrefixTrieIndex<Integer>();
        index.add(1, "oslo");
        index.add(2, "oslo");
        index.add(3, "oslo");
        index.remove(2, "oslo");

        assertEquals(List.of(1, 3), matches(index, "os"));
    }

    @Test void removingEverythingLeavesAnEmptyIndex() {
        var index = new PrefixTrieIndex<Integer>();
        index.add(1, "simon");
        index.add(2, "simone");
        index.add(3, "si");
        index.remove(3, "si");
        index.remove(1, "simon");
        assertEquals(List.of(2), matches(index, "s"));
        index.remove(2, "simone");

        assertTrue(index.isEmpty());
        assertEquals(List.of(), matches(index, ""));
    }

    @Test void removingUnknownValuesOrIdsIsANoOp() {
        var index = new PrefixTrieIndex<Integer>();
        index.add(1, "simon");
        index.remove(1, "sim");
        index.remove(1, "simonx");
        index.remove(2, "simon");
        index.remove(1, "peter");

        assertEquals(List.of(1), matches(index, "simon"));
    }

    @Test void emptyStringsAreIndexedAtTheRoot() {
        var index = new PrefixTrieIndex<Integer>();
        index.add(1, "");
        index.add(2, "a");
        assertEquals(List.of(1, 2), matches(index, ""));
        index.remove(1, "");
        assertEquals(List.of(2), matches(index, ""));
    }

    @Test void matchesABruteForceModelUnderRandomAddsAndRemoves() {
        var random = new Random(1234);
        var index = new PrefixTrieIndex<Integer>();
        Map<Integer, Set<String>> model = new HashMap<>();
        String alphabet = "abc";
        for (int step = 0; step < 20_000; step++) {
            int id = random.nextInt(40);
            String word = word(random, alphabet);
            if (random.nextInt(3) == 0) {
                index.remove(id, word);
                model.getOrDefault(id, new HashSet<>()).remove(word);
            } else if (model.computeIfAbsent(id, ignored -> new HashSet<>()).add(word)) {
                index.add(id, word);
            }
            if (step % 50 == 0) {
                String sample = word(random, alphabet);
                String prefix = sample.substring(0, Math.min(sample.length(), random.nextInt(3)));
                assertEquals(expected(model, prefix), new HashSet<>(matches(index, prefix)), "prefix " + prefix);
            }
        }
        for (var entry : model.entrySet()) {
            for (String word : entry.getValue()) index.remove(entry.getKey(), word);
        }
        assertTrue(index.isEmpty());
    }

    private static String word(Random random, String alphabet) {
        char[] chars = new char[1 + random.nextInt(5)];
        for (int i = 0; i < chars.length; i++) chars[i] = alphabet.charAt(random.nextInt(alphabet.length()));
        return new String(chars);
    }

    private static Set<Integer> expected(Map<Integer, Set<String>> model, String prefix) {
        Set<Integer> ids = new HashSet<>();
        model.forEach((id, words) -> {
            if (words.stream().anyMatch(w -> w.toLowerCase(Locale.ROOT).startsWith(prefix))) ids.add(id);
        });
        return ids;
    }

    @Test void equalLookingEntriesSharingAWordAreTrackedByIdentity() {
        var index = new PrefixTrieIndex<IndexedEntry<String>>();
        var old = new IndexedEntry<>("simon", new Object[0]);
        var replacement = new IndexedEntry<>("simon", new Object[0]);
        index.add(old, "Simon");
        index.add(replacement, "Simon");

        index.remove(old, "Simon");

        List<IndexedEntry<String>> found = new ArrayList<>();
        index.forEachMatch("si", found::add);
        assertEquals(1, found.size());
        assertSame(replacement, found.getFirst());
    }

    @Test void prefixIndexesRejectValuesThatAreNotStrings() {
        var index = new PrefixTrieIndex<Integer>();
        assertThrows(IllegalArgumentException.class, () -> index.add(1, 42));
    }

    @Test void anEmptyStringIsIndexedAtTheRoot() {
        var index = new PrefixTrieIndex<Integer>();
        index.add(1, "");
        assertFalse(index.isEmpty());
        assertEquals(List.of(1), matches(index, ""));
        index.remove(1, "");
        assertTrue(index.isEmpty());
    }
}
