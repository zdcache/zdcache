package io.github.zdcache.store;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IndexedEntryTest {
    @Test void theOrdinalAndTheEvictedFlagDoNotOverwriteEachOther() {
        var entry = new IndexedEntry<>("Anna", new Object[0]);
        assertEquals(-1, entry.ordinal());
        assertFalse(entry.evicted());
        entry.ordinal(41);
        entry.markEvicted();
        assertEquals(41, entry.ordinal());
        assertTrue(entry.evicted());
        entry.ordinal(-1);
        assertTrue(entry.evicted());
        assertEquals(-1, entry.ordinal());
        entry.ordinal(Integer.MAX_VALUE - 1);
        assertEquals(Integer.MAX_VALUE - 1, entry.ordinal());
    }

    @Test void markingEvictedWhileTheOrdinalChangesKeepsBoth() throws Exception {
        for (int round = 0; round < 2_000; round++) {
            var entry = new IndexedEntry<>("Anna", new Object[0]);
            Thread numbering = Thread.ofPlatform().start(() -> { for (int i = 0; i < 50; i++) entry.ordinal(i); });
            entry.markEvicted();
            numbering.join();
            assertTrue(entry.evicted());
            assertEquals(49, entry.ordinal());
        }
    }
}
