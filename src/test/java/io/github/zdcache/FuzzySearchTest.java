package io.github.zdcache;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FuzzySearchTest {
    record Student(long id, String name, String programme) {
        Student(long id, String name) { this(id, name, "Physics"); }
    }

    static final IdentityDimension<Student, Long> ID = IdentityDimension.of(Student::id);
    static final SearchSpecification<Student, Long> WHOLE = SearchSpecification.builder(ID).prefix(Student::name).exact(Student::programme).build();
    static final SearchSpecification<Student, Long> WORDS = SearchSpecification.builder(ID).prefixSplit(Student::name).build();

    private static ZDCache students(SearchSpecification<Student, Long> spec, Student... students) {
        var cache = ZDCache.builder().build();
        cache.put(students[0], spec);
        for (int i = 1; i < students.length; i++) cache.put(students[i]);
        return cache;
    }

    private static List<Long> ids(List<Student> found) {
        return found.stream().map(Student::id).toList();
    }

    @Test void aFuzzySearchFindsTheWholeValueDespiteTyposBestFirst() {
        var cache = students(WHOLE, new Student(4, "Hansen"), new Student(3, "Anderson"), new Student(2, "Andresen"), new Student(1, "Andersen"));
        assertEquals(1L, ids(cache.search(Student.class, Student::name, Match.fuzzy("andersen"))).getFirst());
        assertEquals(List.of(1L, 2L, 3L), ids(cache.search(Student.class, Student::name, Match.fuzzy("andersen"))).stream().sorted().toList());
        assertEquals(List.of(1L), ids(cache.search(Student.class, Student::name, Match.fuzzy("andersen", 0))));
    }

    @Test void theAllowedEditsGrowWithTheLengthOfTheSearchTerm() {
        var cache = students(WHOLE, new Student(1, "Anna"), new Student(2, "Ann"));
        assertEquals(List.of(), ids(cache.search(Student.class, Student::name, Match.fuzzy("an"))));
        assertEquals(List.of(1L, 2L), ids(cache.search(Student.class, Student::name, Match.fuzzy("ana"))).stream().sorted().toList());
        assertEquals(List.of(2L, 1L), ids(cache.search(Student.class, Student::name, Match.fuzzy("an", 2))));
    }

    @Test void onASplitDimensionEveryQueryWordMustBeCloseToAWordOfTheValue() {
        var cache = students(WORDS, new Student(1, "Anna Maria Olsen"), new Student(2, "Per Olsen"), new Student(3, "Anna Berg"));
        assertEquals(List.of(1L), ids(cache.search(Student.class, Student::name, Match.fuzzy("ana olsne"))));
        assertEquals(List.of(), ids(cache.search(Student.class, Student::name, Match.fuzzy("ana xyzzy"))));
        assertEquals(List.of(1L, 2L), ids(cache.search(Student.class, Student::name, Match.fuzzy("olsne"))).stream().sorted().toList());
    }

    @Test void searchRankedPutsMatchesWithoutTyposBeforeOneAndTwoEdits() {
        var cache = students(WHOLE, new Student(3, "Beek"), new Student(2, "Berk"), new Student(1, "Berg"));
        assertEquals(List.of(RankLevel.EXACT, RankLevel.ONE_EDIT, RankLevel.TWO_EDITS),
            cache.searchRanked(Student::name, Match.fuzzy("berg", 2)).stream().map(Ranked::rankLevel).toList());
        assertEquals(List.of(1L, 2L, 3L), cache.searchRanked(Student::name, Match.fuzzy("berg", 2)).stream().map(ranked -> ranked.result().id()).toList());
        var words = students(WORDS, new Student(1, "Anna Olsen"), new Student(2, "Olsen Anna"), new Student(3, "Ana Olsen"));
        assertEquals(List.of(RankLevel.EXACT, RankLevel.WORDS_IN_ANY_ORDER, RankLevel.ONE_EDIT),
            words.searchRanked(Student::name, Match.fuzzy("anna olsen")).stream().map(Ranked::rankLevel).toList());
    }

    @Test void aChangedValueIsNoLongerFoundByItsOldSpelling() {
        var cache = students(WHOLE, new Student(1, "Andersen"));
        cache.put(new Student(1, "Hansen"));
        assertEquals(List.of(), ids(cache.search(Student.class, Student::name, Match.fuzzy("andersen"))));
        assertEquals(List.of(1L), ids(cache.search(Student.class, Student::name, Match.fuzzy("hanssen"))));
    }

    @Test void atMostTwoEditsAreAllowed() {
        assertThrows(IllegalArgumentException.class, () -> Match.fuzzy("andersen", 3));
        assertThrows(IllegalArgumentException.class, () -> Match.fuzzy("andersen", -1));
    }

    @Test void aFuzzySearchNeedsAPrefixStrategy() {
        var cache = students(WHOLE, new Student(1, "Anna"));
        var refused = assertThrows(UndeclaredSearchStrategyException.class,
            () -> cache.search(Student.class, Student::programme, Match.fuzzy("physcs")));
        assertEquals("Search dimension 'programme' of " + Student.class.getName() + " has no fuzzy strategy; declare it with prefix or prefixSplit",
            refused.getMessage());
    }
}
