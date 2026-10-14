package io.github.zdcache.bench;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SubjectsTest {
    private static final int SIZE = 2_000;
    private static final Person[] PEOPLE = Dataset.people(SIZE, 42);
    private static final Queries QUERIES = new Queries(PEOPLE, 7);
    private static final Map<String, CacheSubject> FILLED = new LinkedHashMap<>();

    private static CacheSubject filled(String name) {
        return FILLED.computeIfAbsent(name, subject -> {
            CacheSubject cache = Subjects.create(subject, SIZE);
            for (Person person : PEOPLE) cache.put(person);
            return cache;
        });
    }

    @AfterAll
    static void closeSubjects() {
        FILLED.values().forEach(CacheSubject::close);
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
            for (Person person : PEOPLE) assertEquals(person, subject.get(person.id()), name);
        }
    }

    @Test void replacingAPersonIsVisibleInEverySubject() {
        Person[] changed = Dataset.changed(PEOPLE, 99);
        for (String name : Subjects.ALL) {
            CacheSubject subject = Subjects.create(name, SIZE);
            try {
                for (Person person : PEOPLE) subject.put(person);
                subject.put(changed[5]);
                assertEquals(changed[5], subject.get(changed[5].id()), name);
            } finally {
                subject.close();
            }
        }
    }

    @Test void zdcacheConfigurationsPutAtomically() {
        for (String name : Subjects.CONFIGURATIONS) {
            CacheSubject subject = Subjects.create(name, SIZE);
            subject.putAtomically(PEOPLE[3]);
            assertEquals(PEOPLE[3], subject.get(PEOPLE[3].id()), name);
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

    @Test void retaggedPeopleKeepEveryIndexedValueAndChangeTheirTags() {
        Person[] retagged = Dataset.retagged(PEOPLE, 45);
        int changedTags = 0;
        for (int i = 0; i < SIZE; i++) {
            Person person = PEOPLE[i], next = retagged[i];
            assertEquals(List.of(person.id(), person.name(), person.city(), person.age()),
                List.of(next.id(), next.name(), next.city(), next.age()));
            if (!person.tags().equals(next.tags())) changedTags++;
        }
        assertTrue(changedTags > SIZE / 2);
        assertArrayEquals(Dataset.people(SIZE, 42), PEOPLE);
    }

    @Test void keyOnlySubjectsCannotSearch() {
        assertThrows(io.github.zdcache.MissingSearchSpecificationStrategyException.class,
            () -> filled(Subjects.ZDCACHE_LHM_KEYS).byCity("city-01"));
    }

    @Test void subjectListsCoverEveryConfigurationAndLibrary() {
        assertEquals(9, Subjects.CONFIGURATIONS.size());
        assertTrue(Subjects.SEARCHABLE.containsAll(Subjects.INDEXED_CONFIGURATIONS));
        assertTrue(Subjects.ALL.containsAll(List.of(Subjects.EHCACHE, Subjects.CONCURRENT_HASH_MAP, Subjects.HAZELCAST)));
    }
}
