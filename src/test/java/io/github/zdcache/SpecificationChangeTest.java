package io.github.zdcache;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpecificationChangeTest {
    record Student(long id, String name, int age, Object score) {
        Student(long id, String name) { this(id, name, 20, 1); }
    }

    static final IdentityDimension<Student, Long> ID = IdentityDimension.of(Student::id);

    private static ZDCache students(SearchSpecification<Student, Long> spec, Student... students) {
        var cache = ZDCache.builder().build();
        cache.put(students[0], spec);
        for (int i = 1; i < students.length; i++) cache.put(students[i]);
        return cache;
    }

    private static List<Long> ids(List<Student> found) {
        return found.stream().map(Student::id).sorted().toList();
    }

    @SuppressWarnings("unchecked")
    private static SearchSpecification<Student, Long> spec(ZDCache cache) {
        return (SearchSpecification<Student, Long>) cache.getSpecification(Student.class);
    }

    @Test void strategiesAddedToACachedDimensionFindTheValuesAlreadyStored() {
        var cache = students(SearchSpecification.builder(ID).prefix(Student::name).build(),
            new Student(1, "Anna Maria Olsen"), new Student(2, "Per Andersen"));
        cache.updateSpecification(Student.class, spec(cache).withStrategies(Student::name, TextStrategy.split(), TextStrategy.contains()));
        assertEquals(List.of(1L), ids(cache.search(Student.class, Student::name, Match.words("olsen anna"))));
        assertEquals(List.of(2L), ids(cache.search(Student.class, Student::name, Match.contains("ders"))));
        assertEquals(List.of(1L), ids(cache.search(Student.class, Student::name, "anna m")));
    }

    @Test void aDimensionCanBeAddedAndRemoved() {
        var cache = students(SearchSpecification.builder(ID).prefix(Student::name).build(),
            new Student(1, "Anna", 21, 1), new Student(2, "Per", 35, 1));
        cache.updateSpecification(Student.class, SearchSpecification.builder(spec(cache)).range(Student::age).build());
        assertEquals(List.of(1L), ids(cache.search(Student.class, Student::age, ValueRange.between(20, 30))));
        cache.updateSpecification(Student.class, spec(cache).without(Student::name));
        assertThrows(UnknownSearchDimensionException.class, () -> cache.search(Student.class, Student::name, "an"));
        assertEquals(List.of(2L), ids(cache.search(Student.class, Student::age, ValueRange.between(30, 40))));
    }

    @Test void whileAChangeIsPreparedTheCacheKeepsWorkingAndCommitIncludesWhatChanged() {
        var cache = students(SearchSpecification.builder(ID).prefix(Student::name).build(),
            new Student(1, "Anna Olsen"), new Student(2, "Per Olsen"), new Student(3, "Kari Olsen"));
        PreparedSpecification prepared = cache.prepareSpecification(Student.class, spec(cache).withStrategies(Student::name, TextStrategy.split()));
        assertThrows(SearchDimensionNotReadyException.class, () -> cache.search(Student.class, Student::name, Match.words("olsen")));
        assertEquals(List.of(1L), ids(cache.search(Student.class, Student::name, "anna")));
        cache.put(new Student(4, "Ola Olsen"));
        cache.put(new Student(2, "Per Berg"));
        cache.remove(Student.class, 3L);
        prepared.commit();
        assertEquals(List.of(1L, 4L), ids(cache.search(Student.class, Student::name, Match.words("olsen"))));
        assertEquals(List.of(2L), ids(cache.search(Student.class, Student::name, Match.words("berg"))));
    }

    @Test void anAbandonedChangeLeavesTheSpecificationAsItWas() {
        var cache = students(SearchSpecification.builder(ID).prefix(Student::name).build(), new Student(1, "Anna Olsen"));
        var before = spec(cache);
        PreparedSpecification prepared = cache.prepareSpecification(Student.class, before.withStrategies(Student::name, TextStrategy.split()));
        assertThrows(IllegalStateException.class, () -> cache.prepareSpecification(Student.class, before.without(Student::name)));
        prepared.abandon();
        assertSame(before, spec(cache));
        assertThrows(IllegalStateException.class, prepared::commit);
        assertThrows(UndeclaredSearchStrategyException.class, () -> cache.search(Student.class, Student::name, Match.words("olsen")));
        cache.updateSpecification(Student.class, before.withStrategies(Student::name, TextStrategy.split()));
        assertEquals(List.of(1L), ids(cache.search(Student.class, Student::name, Match.words("olsen"))));
    }

    @Test void aChangeIsCommittedOnce() {
        var cache = students(SearchSpecification.builder(ID).prefix(Student::name).build(), new Student(1, "Anna"));
        PreparedSpecification prepared = cache.prepareSpecification(Student.class, spec(cache).withStrategies(Student::name, TextStrategy.contains()));
        prepared.commit();
        assertThrows(IllegalStateException.class, prepared::commit);
    }

    @Test void clearingTheCacheAbandonsAPreparedChange() {
        var cache = students(SearchSpecification.builder(ID).prefix(Student::name).build(), new Student(1, "Anna"));
        PreparedSpecification prepared = cache.prepareSpecification(Student.class, spec(cache).withStrategies(Student::name, TextStrategy.split()));
        cache.clear(Student.class);
        var refused = assertThrows(IllegalStateException.class, prepared::commit);
        assertEquals("This specification change was abandoned because the cache was cleared", refused.getMessage());
    }

    @Test void aCommitThatCannotIndexAValueChangesNothing() {
        var cache = students(SearchSpecification.builder(ID).prefix(Student::name).build(), new Student(1, "Anna", 20, 5));
        var before = spec(cache);
        @SuppressWarnings({"unchecked", "rawtypes"})
        var scored = SearchSpecification.builder(before).range("score", student -> (Comparable) student.score()).build();
        PreparedSpecification prepared = cache.prepareSpecification(Student.class, scored);
        cache.put(new Student(2, "Per", 20, "high"));
        assertThrows(ClassCastException.class, prepared::commit);
        assertSame(before, spec(cache));
        assertThrows(UnknownSearchDimensionException.class, () -> cache.search(Student.class, "score", 5));
        assertEquals(List.of(2L), ids(cache.search(Student.class, Student::name, "per")));
    }

    @Test void severalStrategiesCanBeDeclaredUpFront() {
        var cache = students(SearchSpecification.builder(ID).prefix(Student::name, TextStrategy.split(Split.NON_ALPHANUMERIC), TextStrategy.contains()).build(),
            new Student(1, "Kari Hansen-Olsen"));
        assertEquals(List.of(1L), ids(cache.search(Student.class, Student::name, Match.words("olsen"))));
        assertEquals(List.of(1L), ids(cache.search(Student.class, Student::name, Match.contains("sen-ol"))));
    }

    @Test void onlyTextDimensionsTakeStrategiesAndASplitCannotChange() {
        var spec = SearchSpecification.builder(ID).prefixSplit(Student::name).range(Student::age).build();
        assertThrows(IllegalArgumentException.class, () -> spec.withStrategies(Student::age, TextStrategy.contains()));
        assertThrows(IllegalArgumentException.class, () -> spec.withStrategies(Student::name, TextStrategy.split(Split.NON_ALPHANUMERIC)));
        assertSame(Split.WHITESPACE, spec.withStrategies(Student::name, TextStrategy.split()).dimension("name").split());
        assertThrows(UnknownSearchDimensionException.class, () -> spec.withStrategies("nickname", TextStrategy.contains()));
    }

    @Test void writesDuringAChangeAreAllIndexedByTheNewStrategy() throws Exception {
        for (Entries entries : Entries.values()) {
            var cache = ZDCache.builder().entries(entries).build();
            var spec = SearchSpecification.builder(ID).prefix(Student::name).build();
            cache.register(spec);
            for (long id = 0; id < 2_000; id++) cache.put(new Student(id, "name" + id % 13 + " family" + id % 7));
            var writing = new AtomicBoolean(true);
            List<Thread> writers = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                long seed = t;
                writers.add(Thread.ofPlatform().start(() -> {
                    Random random = new Random(seed);
                    for (int i = 0; writing.get() || i < 2_000; i++) {
                        long id = random.nextInt(3_000);
                        if (random.nextInt(5) == 0) cache.remove(Student.class, id);
                        else cache.put(new Student(id, "name" + random.nextInt(13) + " family" + random.nextInt(7)));
                    }
                }));
            }
            Thread.sleep(5);
            cache.updateSpecification(Student.class, spec.withStrategies(Student::name, TextStrategy.split()));
            writing.set(false);
            for (Thread writer : writers) writer.join();
            int found = 0;
            for (int family = 0; family < 7; family++) found += cache.search(Student.class, Student::name, Match.words("family" + family)).size();
            assertEquals(cache.size(Student.class), found, entries.name());
            for (Student student : cache.values(Student.class))
                assertTrue(cache.search(Student.class, Student::name, Match.words(student.name().split(" ")[1])).contains(student), student::toString);
        }
    }
}
