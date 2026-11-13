package io.github.zdcache.index;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TrigramIndexTest {
    private static Set<Integer> candidates(TrigramIndex<Integer> index, String query) {
        Set<Integer> ids = new HashSet<>();
        index.forEachMatch(query, ids::add);
        return ids;
    }

    @Test void aValueIsCutIntoItsLowercasedThreeCharacterPiecesOnce() {
        assertEquals(List.of("and", "nde", "der", "ers", "rse", "sen"), List.copyOf(TrigramIndex.trigrams("Andersen")));
        assertEquals(Set.of("ana", "nan"), TrigramIndex.trigrams("anana"));
        assertEquals(Set.of(), TrigramIndex.trigrams("an"));
    }

    @Test void candidatesHaveEveryPieceOfTheQueryAndFollowChanges() {
        var index = new TrigramIndex<Integer>();
        index.add(1, "Andersen");
        index.add(2, "Pedersen");
        index.add(3, "Andresen");
        assertEquals(Set.of(1, 2), candidates(index, "ders"));
        assertEquals(Set.of(1, 2, 3), candidates(index, "SEN"));
        index.remove(2, "Pedersen");
        index.replace(1, 4, "Andersen");
        assertEquals(Set.of(4), candidates(index, "ders"));
        assertEquals(Set.of(), candidates(index, "xyz"));
        assertThrows(IllegalArgumentException.class, () -> candidates(index, "de"));
    }

    @Test void noValueContainingTheQueryIsEverLeftOut() {
        Random random = new Random(7);
        var index = new TrigramIndex<Integer>();
        Map<Integer, String> values = new HashMap<>();
        for (int id = 0; id < 400; id++) {
            String value = word(random, 2 + random.nextInt(8));
            values.put(id, value);
            index.add(id, value);
        }
        for (int round = 0; round < 300; round++) {
            String query = word(random, 3 + random.nextInt(3));
            Set<Integer> found = candidates(index, query);
            values.forEach((id, value) -> { if (value.contains(query)) assertTrue(found.contains(id), value + " contains " + query); });
        }
    }

    private static String word(Random random, int length) {
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < length; i++) word.append("abc".charAt(random.nextInt(3)));
        return word.toString();
    }
}
