package io.github.zdcache;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContainsSearchTest {
    record Course(String title) {}
    record Student(long id, String name, String programme, List<Course> courses) {
        Student(long id, String name) { this(id, name, "Physics", List.of()); }
    }

    static final IdentityDimension<Student, Long> ID = IdentityDimension.of(Student::id);
    static final SearchSpecification<Student, Long> SPEC = SearchSpecification.builder(ID)
        .prefixContains(Student::name)
        .exact(Student::programme)
        .each(Student::courses, course -> course.prefixContains(Course::title))
        .build();

    private static ZDCache students(Student... students) {
        var cache = ZDCache.builder().build();
        cache.put(students[0], SPEC);
        for (int i = 1; i < students.length; i++) cache.put(students[i]);
        return cache;
    }

    private static List<Long> contains(ZDCache cache, String text) {
        return cache.search(Student.class, Student::name, Match.contains(text)).stream().map(Student::id).sorted().toList();
    }

    @Test void aContainsSearchFindsTheTextAnywhereInTheValue() {
        var cache = students(new Student(1, "Andersen"), new Student(2, "Pedersen"), new Student(3, "Andresen"));
        assertEquals(List.of(1L, 2L), contains(cache, "ders"));
        assertEquals(List.of(1L, 2L, 3L), contains(cache, "SEN"));
        assertEquals(List.of(), contains(cache, "xyz"));
    }

    @Test void valuesWithThePiecesOfTheQueryInAnotherOrderAreNotFound() {
        var cache = students(new Student(1, "Enasen"), new Student(2, "Senator"));
        assertEquals(List.of(2L), contains(cache, "sena"));
    }

    @Test void oneOrTwoCharactersAreFoundByLookingThroughTheValues() {
        var cache = students(new Student(1, "Andersen"), new Student(2, "Berg"), new Student(3, "Ali"));
        assertEquals(List.of(1L), contains(cache, "rs"));
        assertEquals(List.of(2L), contains(cache, "G"));
        assertEquals(List.of(3L), contains(cache, "ali"));
        assertEquals(List.of(), contains(cache, "x"));
    }

    @Test void resultsComeBestFirst() {
        var cache = students(new Student(4, "Nicolsen"), new Student(3, "Anna Olsen"), new Student(2, "Olsen"), new Student(1, "Ols"));
        assertEquals(List.of(RankLevel.EXACT, RankLevel.STARTS_WITH, RankLevel.WORDS_IN_ORDER, RankLevel.CONTAINS),
            cache.searchRanked(Student::name, Match.contains("ols")).stream().map(Ranked::rankLevel).toList());
        assertEquals(List.of(1L, 2L, 3L, 4L), cache.search(Student.class, Student::name, Match.contains("ols")).stream().map(Student::id).toList());
    }

    @Test void changedAndRemovedValuesAreNoLongerFound() {
        var cache = students(new Student(1, "Andersen"), new Student(2, "Pedersen"));
        cache.put(new Student(1, "Hansen"));
        cache.remove(Student.class, 2L);
        assertEquals(List.of(), contains(cache, "ders"));
        assertEquals(List.of(1L), contains(cache, "anse"));
    }

    @Test void aRootWithSeveralMatchingValuesIsFoundOnce() {
        var cache = students(new Student(1, "Anna", "Maths", List.of(new Course("Linear Algebra"), new Course("Algebraic Topology"))),
            new Student(2, "Per", "Maths", List.of(new Course("Geometry"))));
        assertEquals(List.of(1L), cache.search(Student::courses, Course::title, Match.contains("gebra")).stream().map(Student::id).toList());
    }

    @Test void aContainsDimensionKeepsThePrefixStrategy() {
        var cache = students(new Student(1, "Andersen"), new Student(2, "Pedersen"));
        assertEquals(List.of(1L), cache.search(Student.class, Student::name, "and").stream().map(Student::id).toList());
        assertTrue(SPEC.dimension("name").contains());
        assertNull(SPEC.dimension("name").split());
        var both = SearchSpecification.builder(ID).prefixSplit(Student::name);
        assertThrows(IllegalArgumentException.class, () -> both.prefixContains(Student::name));
    }

    @Test void aContainsSearchNeedsAContainsStrategyAndAtLeastOneCharacter() {
        var cache = students(new Student(1, "Andersen"));
        var refused = assertThrows(UndeclaredSearchStrategyException.class,
            () -> cache.search(Student.class, Student::programme, Match.contains("hys")));
        assertEquals("Search dimension 'programme' of " + Student.class.getName() + " has no contains strategy; declare it with prefixContains",
            refused.getMessage());
        assertThrows(IllegalArgumentException.class, () -> cache.search(Student.class, Student::name, Match.contains("")));
    }
}
