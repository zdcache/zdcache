package io.github.zdcache;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WordSearchTest {
    record Course(String title) {}
    record Student(long id, String name, String programme, List<Course> courses) {
        Student(long id, String name) { this(id, name, "Physics", List.of()); }
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

    private static List<Long> words(ZDCache cache, String text) {
        return ids(cache.search(Student.class, Student::name, Match.words(text)));
    }

    @Test void aSplitDimensionFindsAValueByTheStartOfAnyWordAndStillByTheStartOfTheWholeValue() {
        var cache = students(SearchSpecification.builder(ID).prefixSplit(Student::name).build(),
            new Student(1, "Anna Maria Olsen"), new Student(2, "Per Hansen"), new Student(3, "Olav Berg"));
        assertEquals(List.of(1L), words(cache, "ols"));
        assertEquals(List.of(1L, 3L), words(cache, "O"));
        assertEquals(List.of(), ids(cache.search(Student.class, Student::name, "ols")));
        assertEquals(List.of(1L), ids(cache.search(Student.class, Student::name, "anna m")));
        assertEquals(List.of(1L), ids(cache.search(Student.class, Student::name, Match.prefix("anna m"))));
    }

    @Test void everyWordOfTheQueryMustStartAWordOfTheValueInAnyOrder() {
        var cache = students(SearchSpecification.builder(ID).prefixSplit(Student::name).build(),
            new Student(1, "Anna Maria Olsen"), new Student(2, "Anna Olsen"), new Student(3, "Maria Berg"));
        assertEquals(List.of(1L), words(cache, "anna ma"));
        assertEquals(List.of(1L), words(cache, "ma  anna"));
        assertEquals(List.of(1L, 2L), words(cache, "olsen anna"));
        assertEquals(List.of(), words(cache, "anna berg"));
    }

    @Test void whitespaceKeepsHyphenatedNamesWholeAndNonAlphanumericSplitsThem() {
        Student kari = new Student(1, "Kari Hansen-Olsen");
        var byWhitespace = students(SearchSpecification.builder(ID).prefixSplit(Student::name).build(), kari);
        assertEquals(List.of(), words(byWhitespace, "olsen"));
        assertEquals(List.of(1L), words(byWhitespace, "hansen-ol"));
        var byLetters = students(SearchSpecification.builder(ID).prefixSplit(Student::name, Split.NON_ALPHANUMERIC).build(), kari);
        assertEquals(List.of(1L), words(byLetters, "olsen"));
        assertEquals(List.of(1L), words(byLetters, "hansen olsen"));
    }

    @Test void aValueIsFoundOnceThoughSeveralOfItsWordsMatch() {
        var cache = students(SearchSpecification.builder(ID).prefixSplit(Student::name).build(),
            new Student(1, "Anna Andersen"), new Student(2, "Anna Anna Berg"));
        assertEquals(List.of(1L, 2L), cache.search(Student.class, Student::name, Match.words("an")).stream().map(Student::id).sorted().toList());
    }

    @Test void aRepeatedQueryWordCountsOnce() {
        var cache = students(SearchSpecification.builder(ID).prefixSplit(Student::name).build(),
            new Student(1, "Anna Andersen"), new Student(2, "Anna Anna Berg"));
        assertEquals(words(cache, "anna"), words(cache, "anna anna"));
    }

    @Test void changingRemovingOrEvictingAValueUpdatesItsWords() {
        var cache = ZDCache.builder().capacity(Student.class, 2).build();
        var spec = SearchSpecification.builder(ID).prefixSplit(Student::name).build();
        cache.put(new Student(1, "Anna Olsen"), spec);
        cache.put(new Student(1, "Anna Berg"));
        assertEquals(List.of(), words(cache, "olsen"));
        assertEquals(List.of(1L), words(cache, "berg"));
        cache.put(new Student(2, "Per Berg"));
        cache.remove(Student.class, 1L);
        assertEquals(List.of(2L), words(cache, "berg"));
        cache.put(new Student(3, "Ola Nilsen"));
        cache.put(new Student(4, "Kari Nilsen"));
        assertNull(cache.get(Student.class, 2L));
        assertEquals(List.of(), words(cache, "per"));
        assertEquals(List.of(3L, 4L), words(cache, "nilsen"));
    }

    @Test void wordsOfNestedValuesFindTheirRootOnce() {
        var spec = SearchSpecification.builder(ID).each(Student::courses, course -> course.prefixSplit(Course::title)).build();
        var cache = students(spec,
            new Student(1, "Anna", "Maths", List.of(new Course("Intro to Algebra"), new Course("Intro to Topology"))),
            new Student(2, "Per", "Maths", List.of(new Course("Linear Algebra"))));
        assertEquals(List.of(1L, 2L), ids(cache.search(Student.class, "courses.title", Match.words("algebra"))));
        assertEquals(List.of(1L), ids(cache.search(Student.class, "courses.title", Match.words("intro"))));
        assertEquals(List.of(1L), ids(cache.search(Student.class, "courses.title", Match.words("to topo"))));
    }

    @Test void anUpdatedSpecificationIndexesTheWordsOfStoredValues() {
        var cache = students(SearchSpecification.builder(ID).prefix(Student::name).build(), new Student(1, "Anna Maria Olsen"));
        cache.updateSpecification(Student.class, SearchSpecification.builder(ID).prefixSplit(Student::name).build());
        assertEquals(List.of(1L), words(cache, "olsen"));
    }

    @Test void aSearchForAStrategyTheDimensionDoesNotDeclareIsRefused() {
        var cache = students(SearchSpecification.builder(ID).prefix(Student::name).exact(Student::programme).build(), new Student(1, "Anna"));
        var words = assertThrows(UndeclaredSearchStrategyException.class,
            () -> cache.search(Student.class, Student::name, Match.words("anna")));
        assertEquals("Search dimension 'name' of " + Student.class.getName() + " has no words strategy; declare it with prefixSplit", words.getMessage());
        var prefix = assertThrows(UndeclaredSearchStrategyException.class,
            () -> cache.search(Student.class, Student::programme, Match.prefix("phy")));
        assertEquals("Search dimension 'programme' of " + Student.class.getName() + " has no prefix strategy; declare it with prefix", prefix.getMessage());
    }

    @Test void aWordsSearchNeedsAWord() {
        var cache = students(SearchSpecification.builder(ID).prefixSplit(Student::name).build(), new Student(1, "Anna"));
        assertThrows(IllegalArgumentException.class, () -> cache.search(Student.class, Student::name, Match.words("  ")));
    }

    @Test void aSplitDimensionAlreadyHasThePrefixStrategy() {
        var spec = SearchSpecification.builder(ID).prefixSplit(Student::name, Split.NON_ALPHANUMERIC).build();
        assertEquals(IndexKind.PREFIX, spec.dimension("name").kind());
        assertEquals(Split.NON_ALPHANUMERIC, spec.dimension("name").split());
        assertNull(SearchSpecification.builder(ID).prefix(Student::name).build().dimension("name").split());
        var both = SearchSpecification.builder(ID).prefix(Student::name);
        assertThrows(IllegalArgumentException.class, () -> both.prefixSplit(Student::name));
    }

    @Test void wordsAreLowercasedWithoutEmptyOrRepeatedOnes() {
        assertEquals(Set.of("anna", "maria"), Split.WHITESPACE.words("  Anna\\tMARIA anna ".replace("\\t", "\t")));
        assertEquals(List.of("søren", "hansen", "olsen", "42"), List.copyOf(Split.NON_ALPHANUMERIC.words("Søren Hansen-Olsen, 42.")));
    }
}
