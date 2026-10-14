package io.github.zdcache.index;

import io.github.zdcache.IndexKind;
import io.github.zdcache.ValueRange;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IndexReplaceTest {
    private record Kind(String name, Supplier<DimensionIndex<String>> index, Object value, Object query,
                        Object otherValue, Object otherQuery) {}

    private static final List<Kind> KINDS = List.of(
        new Kind("exact", ExactIndex::new, "oslo", "oslo", "bergen", "bergen"),
        new Kind("range", RangeIndex::new, 25, ValueRange.between(25, 25), 40, ValueRange.between(40, 40)),
        new Kind("prefix", PrefixTrieIndex::new, "Simon", "simo", "Simone", "simone"),
        new Kind("compound", () -> new CompoundIndex<>(IndexKind.EXACT),
            List.of("oslo", "x"), List.of("oslo", "x"), List.of("oslo", "y"), List.of("oslo", "y")));

    private static List<String> matches(DimensionIndex<String> index, Object query) {
        List<String> ids = new ArrayList<>();
        index.forEachMatch(query, ids::add);
        return ids.stream().sorted().toList();
    }

    @Test void replacingTheOnlyIdOfAValueLeavesOnlyTheReplacement() {
        for (Kind kind : KINDS) {
            var index = kind.index().get();
            index.add("old", kind.value());
            index.replace("old", "new", kind.value());
            assertEquals(List.of("new"), matches(index, kind.query()), kind.name());
            index.remove("new", kind.value());
            assertTrue(index.isEmpty(), kind.name());
        }
    }

    @Test void replacingOneOfSeveralIdsKeepsTheOthers() {
        for (Kind kind : KINDS) {
            var index = kind.index().get();
            index.add("old", kind.value());
            index.add("other", kind.value());
            index.replace("old", "new", kind.value());
            assertEquals(List.of("new", "other"), matches(index, kind.query()), kind.name());
        }
    }

    @Test void replacingAnIdThatIsNotIndexedAddsTheReplacement() {
        for (Kind kind : KINDS) {
            var index = kind.index().get();
            index.replace("old", "new", kind.value());
            assertEquals(List.of("new"), matches(index, kind.query()), kind.name());
            index.add("other", kind.value());
            index.replace("missing", "added", kind.value());
            assertEquals(List.of("added", "new", "other"), matches(index, kind.query()), kind.name());
        }
    }

    @Test void replacingLeavesOtherValuesAlone() {
        for (Kind kind : KINDS) {
            var index = kind.index().get();
            index.add("old", kind.value());
            index.add("neighbour", kind.otherValue());
            index.replace("old", "new", kind.value());
            assertEquals(List.of("neighbour"), matches(index, kind.otherQuery()), kind.name());
            index.remove("neighbour", kind.otherValue());
            index.remove("new", kind.value());
            assertTrue(index.isEmpty(), kind.name());
        }
    }

    @Test void prefixReplacementsMatchABruteForceModel() {
        Random random = new Random(7);
        var index = new PrefixTrieIndex<Integer>();
        Map<Integer, String> model = new HashMap<>();
        String[] words = {"a", "ab", "abc", "abd", "b", "ba", "bab", "simon", "simone", "sim", ""};
        int nextId = 0;
        for (int step = 0; step < 5_000; step++) {
            List<Integer> indexed = model.keySet().stream().sorted().toList();
            String word = words[random.nextInt(words.length)];
            int operation = indexed.isEmpty() ? 0 : random.nextInt(4);
            Integer id = indexed.isEmpty() ? null : indexed.get(random.nextInt(indexed.size()));
            switch (operation) {
                case 0 -> {
                    index.add(nextId, word);
                    model.put(nextId++, word);
                }
                case 1 -> {
                    index.remove(id, model.remove(id));
                }
                case 2 -> {
                    index.remove(id, model.get(id));
                    index.add(id, word);
                    model.put(id, word);
                }
                default -> {
                    String current = model.remove(id);
                    index.replace(id, nextId, current);
                    model.put(nextId++, current);
                }
            }
            if (step % 50 == 0) {
                for (String prefix : words) {
                    Set<Integer> expected = new HashSet<>();
                    model.forEach((key, value) -> {
                        if (value.startsWith(prefix.toLowerCase(Locale.ROOT))) expected.add(key);
                    });
                    Set<Integer> found = new HashSet<>();
                    index.forEachMatch(prefix, found::add);
                    assertEquals(expected, found, "prefix '" + prefix + "' at step " + step);
                }
            }
        }
        model.forEach((key, value) -> index.remove(key, value));
        assertTrue(index.isEmpty());
    }
}
