package io.github.zdcache.bench;

import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SubjectsTest {
    private static final int SIZE = 2_000;
    private static final Person[] PEOPLE = Dataset.people(SIZE, 42);
    private static final Queries QUERIES = new Queries(PEOPLE, 7);

    private static CacheSubject filled(String name) {
        CacheSubject subject = Subjects.create(name, SIZE);
        for (Person person : PEOPLE) subject.put(person);
        return subject;
    }

    private static List<Long> ids(List<Person> people) {
        return people.stream().map(Person::id).sorted().toList();
    }

    private static void assertSameResults(Function<CacheSubject, List<Person>> query) {
        List<Long> expected = ids(query.apply(filled(Subjects.CAFFEINE)));
        assertFalse(expected.isEmpty());
        for (String name : Subjects.SEARCHABLE) {
            assertEquals(expected, ids(query.apply(filled(name))), name);
        }
    }

    @Test void everySubjectStoresAndReturnsEveryPerson() {
        for (String name : Subjects.ALL) {
            CacheSubject subject = filled(name);
            for (Person person : PEOPLE) assertSame(person, subject.get(person.id()), name);
        }
    }

    @Test void replacingAPersonIsVisibleInEverySubject() {
        Person[] changed = Dataset.changed(PEOPLE, 99);
        for (String name : Subjects.ALL) {
            CacheSubject subject = filled(name);
            subject.put(changed[5]);
            assertSame(changed[5], subject.get(changed[5].id()), name);
        }
    }

    @Test void searchableSubjectsReturnTheSameAsAFullCaffeineScan() {
        for (int i = 0; i < Queries.COUNT; i++) {
            int q = i;
            assertSameResults(s -> s.byCity(QUERIES.cities[q]));
            assertSameResults(s -> s.byNamePrefix(QUERIES.namePrefixes[q]));
            assertSameResults(s -> s.byAge(QUERIES.ageFrom[q], QUERIES.ageTo[q]));
        }
    }

    @Test void compoundSearchMatchesAScanWhereverTheScanFindsSomething() {
        int compared = 0;
        for (int i = 0; i < Queries.COUNT; i++) {
            String city = QUERIES.cities[i];
            int from = QUERIES.ageFrom[i], to = QUERIES.ageTo[i];
            List<Long> expected = ids(filled(Subjects.CAFFEINE).byCityAndAge(city, from, to));
            for (String name : Subjects.SEARCHABLE) {
                assertEquals(expected, ids(filled(name).byCityAndAge(city, from, to)), name);
            }
            if (!expected.isEmpty()) compared++;
        }
        assertTrue(compared > 0);
    }

    @Test void datasetAndQueriesAreDeterministic() {
        assertEquals(List.of(Dataset.people(100, 42)), List.of(Dataset.people(100, 42)));
        assertArrayEquals(new Queries(PEOPLE, 7).namePrefixes, QUERIES.namePrefixes);
    }

    @Test void keyOnlySubjectsCannotSearch() {
        assertThrows(io.github.zdcache.MissingSearchSpecificationStrategyException.class,
            () -> filled(Subjects.ZDCACHE_LHM_KEYS).byCity("city-01"));
    }
}
