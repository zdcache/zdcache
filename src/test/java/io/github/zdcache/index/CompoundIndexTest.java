package io.github.zdcache.index;

import io.github.zdcache.IndexKind;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompoundIndexTest {

    @Test void removingTheLastIdFromABucketLeavesTheIndexEmpty() {
        var index = new CompoundIndex<Long>(IndexKind.RANGE);
        index.add(1L, List.of("Oslo", 29));
        index.add(2L, List.of("Oslo", 41));
        index.add(3L, List.of("Bergen", 29));

        index.remove(1L, List.of("Oslo", 29));
        index.remove(3L, List.of("Bergen", 29));
        assertFalse(index.isEmpty());

        index.remove(2L, List.of("Oslo", 41));
        assertTrue(index.isEmpty());
    }

    @Test void removingAnUnknownTupleIsANoOp() {
        var index = new CompoundIndex<Long>(IndexKind.EXACT);
        index.add(1L, List.of("Oslo", "home"));
        index.remove(1L, List.of("Bergen", "home"));
        index.remove(2L, List.of("Oslo", "home"));
        assertFalse(index.isEmpty());
    }

    @Test void validateRejectsIncomparableRangeValueWithinTheSameBucketOnly() {
        var index = new CompoundIndex<Long>(IndexKind.RANGE);
        index.add(1L, List.of("Oslo", 29));

        assertThrows(ClassCastException.class, () -> index.validate(List.of("Oslo", "old")));
        index.validate(List.of("Bergen", "old"));
    }

    @Test void rejectsNonTupleValuesAndNesting() {
        var index = new CompoundIndex<Long>(IndexKind.EXACT);
        assertThrows(IllegalArgumentException.class, () -> index.add(1L, "Oslo"));
        assertThrows(IllegalArgumentException.class, () -> index.add(1L, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new CompoundIndex<Long>(IndexKind.COMPOUND));
    }
}
