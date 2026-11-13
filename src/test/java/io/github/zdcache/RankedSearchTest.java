package io.github.zdcache;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RankedSearchTest {
    record Course(String title) {}
    record Student(long id, String name, String programme, List<Course> courses) {
        Student(long id, String name) { this(id, name, "Physics", List.of()); }
    }

    static final IdentityDimension<Student, Long> ID = IdentityDimension.of(Student::id);
    static final SearchSpecification<Student, Long> SPEC = SearchSpecification.builder(ID)
        .prefixSplit(Student::name)
        .exact(Student::programme)
        .each(Student::courses, course -> course.prefixSplit(Course::title))
        .build();

    private static ZDCache students(Student... students) {
        var cache = ZDCache.builder().build();
        cache.put(students[0], SPEC);
        for (int i = 1; i < students.length; i++) cache.put(students[i]);
        return cache;
    }

    private static final Student[] OLSENS = {
        new Student(4, "Olsen Anna"), new Student(3, "Anna Maria Olsen"), new Student(1, "Anna Olsen"),
        new Student(2, "Anna Olsenius"), new Student(5, "Per Olsen")
    };

    @Test void aWordsSearchReturnsEveryMatchBestFirst() {
        var cache = students(OLSENS);
        assertEquals(List.of(1L, 2L, 3L, 4L),
            cache.search(Student.class, Student::name, Match.words("anna olsen")).stream().map(Student::id).toList());
    }

    @Test void searchRankedSaysHowWellEachResultMatched() {
        var cache = students(OLSENS);
        assertEquals(List.of(
                new Ranked<>(OLSENS[2], RankLevel.EXACT),
                new Ranked<>(OLSENS[3], RankLevel.STARTS_WITH),
                new Ranked<>(OLSENS[1], RankLevel.WORDS_IN_ORDER),
                new Ranked<>(OLSENS[0], RankLevel.WORDS_IN_ANY_ORDER)),
            cache.searchRanked(Student::name, Match.words("anna olsen")));
    }

    @Test void caseAndSpacingDoNotStopAnExactMatch() {
        var cache = students(new Student(1, "Anna  Olsen"));
        assertEquals(RankLevel.EXACT, cache.searchRanked(Student::name, Match.words("  ANNA olsen ")).getFirst().rankLevel());
    }

    @Test void aPrefixSearchIsRankedAsExactOrStartsWith() {
        var cache = students(new Student(1, "Anna Olsen"), new Student(2, "Anna"), new Student(3, "Per"));
        assertEquals(List.of(new Ranked<>(new Student(2, "Anna"), RankLevel.EXACT), new Ranked<>(new Student(1, "Anna Olsen"), RankLevel.STARTS_WITH)),
            cache.searchRanked(Student.class, Student::name, "anna"));
        assertEquals(cache.searchRanked(Student.class, Student::name, "anna"), cache.searchRanked(Student.class, "name", Match.prefix("anna")));
    }

    @Test void aRootWithSeveralValuesIsRankedByItsBestOne() {
        var cache = students(
            new Student(1, "Anna", "Maths", List.of(new Course("Linear Algebra"), new Course("Algebra"))),
            new Student(2, "Per", "Maths", List.of(new Course("Algebra and Geometry"))));
        assertEquals(List.of(1L, 2L), cache.searchRanked(Student::courses, Course::title, Match.words("algebra")).stream()
            .map(ranked -> ranked.result().id()).toList());
        assertEquals(List.of(RankLevel.EXACT, RankLevel.STARTS_WITH), cache.searchRanked(Student::courses, Course::title, Match.words("algebra")).stream()
            .map(Ranked::rankLevel).toList());
    }

    @Test void onlyTextDimensionsAreRanked() {
        var cache = students(new Student(1, "Anna"));
        var refused = assertThrows(IllegalArgumentException.class, () -> cache.searchRanked(Student::programme, "Physics"));
        assertEquals("Only text dimensions are ranked, and 'programme' of " + Student.class.getName() + " is EXACT", refused.getMessage());
    }
}
