package io.github.zdcache.index;

import io.github.zdcache.ValueRange;
import io.github.zdcache.store.IndexedEntry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExactAndRangeIndexTest {

    private static List<Integer> matches(DimensionIndex<Integer> index, Object query) {
        List<Integer> ids = new ArrayList<>();
        index.forEachMatch(query, ids::add);
        return ids.stream().sorted().toList();
    }

    @Test void exactBucketsGrowFromOneIdToManyAndShrinkBack() {
        var index = new ExactIndex<Integer>();
        index.add(1, "oslo");
        assertEquals(List.of(1), matches(index, "oslo"));
        index.add(2, "oslo");
        index.add(3, "oslo");
        index.add(2, "oslo");
        assertEquals(List.of(1, 2, 3), matches(index, "oslo"));
        index.remove(1, "oslo");
        index.remove(3, "oslo");
        assertEquals(List.of(2), matches(index, "oslo"));
        index.remove(9, "oslo");
        assertEquals(List.of(2), matches(index, "oslo"));
        index.remove(2, "oslo");
        assertTrue(index.isEmpty());
        assertEquals(List.of(), matches(index, "oslo"));
    }

    @Test void rangeBucketsReturnEveryIdInsideTheInclusiveBounds() {
        var index = new RangeIndex<Integer>();
        index.add(1, 20);
        index.add(2, 25);
        index.add(3, 25);
        index.add(4, 30);
        index.add(5, 31);

        assertEquals(List.of(1, 2, 3, 4), matches(index, ValueRange.between(20, 30)));
        index.remove(2, 25);
        assertEquals(List.of(3), matches(index, ValueRange.between(25, 25)));
        for (int id : List.of(1, 3, 4, 5)) index.remove(id, switch (id) { case 1 -> 20; case 3 -> 25; case 4 -> 30; default -> 31; });
        assertTrue(index.isEmpty());
    }

    @Test void rangeQueriesOfAnotherTypeFailWithIllegalArgument() {
        var index = new RangeIndex<Integer>();
        index.add(1, 20);
        assertThrows(IllegalArgumentException.class, () -> matches(index, ValueRange.between(1L, 2L)));
        assertThrows(IllegalArgumentException.class, () -> matches(index, 20));
    }

    @Test void equalLookingEntriesAreTrackedByIdentity() {
        var index = new ExactIndex<IndexedEntry<String>>();
        var old = new IndexedEntry<>("simon", new Object[0]);
        var replacement = new IndexedEntry<>("simon", new Object[0]);
        index.add(old, "oslo");
        index.add(replacement, "oslo");
        List<IndexedEntry<String>> found = new ArrayList<>();
        index.forEachMatch("oslo", found::add);
        assertEquals(2, found.size());

        index.remove(old, "oslo");

        found.clear();
        index.forEachMatch("oslo", found::add);
        assertEquals(1, found.size());
        assertSame(replacement, found.getFirst());
    }

    @Test void addingTheSameEntryTwiceKeepsOneReference() {
        Integer entry = 1;
        assertSame(entry, Ids.add(entry, entry));
    }

    @Test void rangeIndexesRejectKeysThatAreNotComparable() {
        var index = new RangeIndex<Integer>();
        assertThrows(IllegalArgumentException.class, () -> index.add(1, new Object()));
        assertThrows(IllegalArgumentException.class, () -> index.validate(new Object()));
    }
}
