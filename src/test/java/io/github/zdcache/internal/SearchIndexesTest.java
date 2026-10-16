package io.github.zdcache.internal;

import io.github.zdcache.IdentityDimension;
import io.github.zdcache.SearchSpecification;
import io.github.zdcache.store.IndexedEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SearchIndexesTest {
    record Person(Long id, String city) {}

    static final SearchSpecification<Person, Long> SPEC =
        SearchSpecification.builder(IdentityDimension.of(Person.class, "id", Person::id)).exact(Person::city).build();

    private static List<Person> inCity(SearchIndexes<Person, Long> indexes, String city) {
        return indexes.read((copy, side) -> {
            List<Person> found = new ArrayList<>();
            copy.forEachMatch("city", new Object[] {city}, entry -> found.add(indexes.entity(entry, side)));
            return found;
        });
    }

    private static IndexedEntry<Person> indexed(SearchIndexes<Person, Long> indexes, Person person) {
        IndexedEntry<Person> entry = new IndexedEntry<>(person, indexes.current().extract(person));
        indexes.add(entry, entry.snapshot());
        indexes.publish();
        return entry;
    }

    @Test void aWriterWaitsForSearchesOnTheOldCopyWhileNewSearchesSeeTheUpdate() throws Exception {
        var indexes = new SearchIndexes<>(SPEC, true);
        Person inOslo = new Person(1L, "Oslo");
        Person inBergen = new Person(1L, "Bergen");
        IndexedEntry<Person> entry = indexed(indexes, inOslo);

        var inside = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        var seenByOldSearch = new AtomicReference<List<Person>>();
        Thread oldSearch = new Thread(() -> seenByOldSearch.set(indexes.read((copy, side) -> {
            List<Person> found = new ArrayList<>();
            copy.forEachMatch("city", new Object[] {"Oslo"}, match -> found.add(indexes.entity(match, side)));
            inside.countDown();
            try {
                proceed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            found.replaceAll(person -> indexes.entity(entry, side));
            return found;
        })));
        oldSearch.start();
        assertTrue(inside.await(5, TimeUnit.SECONDS));

        Thread writer = new Thread(() -> {
            indexes.update(entry, inBergen, indexes.current().extract(inBergen));
            indexes.publish();
        });
        writer.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (inCity(indexes, "Bergen").isEmpty() && System.nanoTime() < deadline) Thread.onSpinWait();

        assertEquals(List.of(inBergen), inCity(indexes, "Bergen"));
        assertEquals(List.of(), inCity(indexes, "Oslo"));
        writer.join(200);
        assertTrue(writer.isAlive(), "the writer finished while a search still read the old copy");

        proceed.countDown();
        oldSearch.join();
        writer.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(writer.isAlive());
        assertEquals(List.of(inOslo), seenByOldSearch.get());
        assertSame(inBergen, indexes.entity(entry, 0));
        assertSame(inBergen, indexes.entity(entry, 1));
    }

    @Test void bothCopiesAgreeAfterEveryPublish() {
        var indexes = new SearchIndexes<>(SPEC, true);
        IndexedEntry<Person> first = indexed(indexes, new Person(1L, "Oslo"));
        IndexedEntry<Person> second = indexed(indexes, new Person(2L, "Oslo"));
        indexes.remove(first, first.snapshot());
        indexes.update(second, new Person(2L, "Bergen"), indexes.current().extract(new Person(2L, "Bergen")));
        indexes.publish();
        for (int round = 0; round < 2; round++) {
            assertEquals(List.of(), inCity(indexes, "Oslo"));
            assertEquals(List.of(new Person(2L, "Bergen")), inCity(indexes, "Bergen"));
            indexes.add(first, first.snapshot());
            indexes.remove(first, first.snapshot());
            indexes.publish();
        }
    }

    @Test void oneCopyAppliesChangesAtOnce() {
        var indexes = new SearchIndexes<>(SPEC, false);
        IndexedEntry<Person> entry = indexed(indexes, new Person(1L, "Oslo"));
        indexes.update(entry, new Person(1L, "Bergen"), indexes.current().extract(new Person(1L, "Bergen")));
        assertEquals(List.of(new Person(1L, "Bergen")), inCity(indexes, "Bergen"));
        assertSame(entry.entity(), indexes.entity(entry, 0));
    }
}
