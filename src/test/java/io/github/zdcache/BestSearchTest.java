package io.github.zdcache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BestSearchTest {
    record Student(long id, String name, String programme) {
        Student(long id, String name) { this(id, name, "Physics"); }
    }

    static final IdentityDimension<Student, Long> ID = IdentityDimension.of(Student::id);
    static final SearchSpecification<Student, Long> SPEC = SearchSpecification.builder(ID)
        .prefix(Student::name, TextStrategy.split(), TextStrategy.contains())
        .exact(Student::programme)
        .build();

    private static ZDCache students(Student... students) {
        var cache = ZDCache.builder().build();
        cache.put(students[0], SPEC);
        for (int i = 1; i < students.length; i++) cache.put(students[i]);
        return cache;
    }

    @Test void theBestResultsAreTheFirstOfTheRankedListAndTheTotalCountsEveryResult() {
        var cache = students(new Student(4, "Olsen Anna"), new Student(3, "Anna Maria Olsen"), new Student(1, "Anna Olsen"),
            new Student(2, "Anna Olsenius"), new Student(5, "Per Olsen"), new Student(6, "Anna Berg Olsen"));
        List<Ranked<Student>> all = cache.searchRanked(Student::name, Match.words("anna olsen"));
        for (int limit = 1; limit <= all.size() + 2; limit++) {
            RankedPage<Student> page = cache.searchBest(Student::name, limit, Match.words("anna olsen"));
            assertEquals(all.subList(0, Math.min(limit, all.size())), page.results(), "limit " + limit);
            assertEquals(all.size(), page.total());
        }
        RankedPage<Student> top = cache.searchBest(Student::name, 2, Match.words("anna olsen"));
        assertEquals(List.of(RankLevel.EXACT, RankLevel.STARTS_WITH), top.results().stream().map(Ranked::rankLevel).toList());
    }

    @Test void everyTextSearchCanAskForItsBestResults() {
        var cache = students(new Student(1, "Andersen"), new Student(2, "Andresen"), new Student(3, "Pedersen"));
        assertEquals(3, cache.searchBest(Student.class, Student::name, 1, Match.contains("sen")).total());
        assertEquals(3, cache.searchBest(Student.class, "name", 5, Match.fuzzy("andersen")).total());
        assertEquals(1L, cache.searchBest(Student.class, "name", 1, Match.fuzzy("andersen")).results().getFirst().result().id());
        assertEquals(2, cache.searchBest(Student::name, 5, "and").total());
    }

    @Test void aLimitBelowOneAndANonTextDimensionAreRefused() {
        var cache = students(new Student(1, "Anna"));
        assertThrows(IllegalArgumentException.class, () -> cache.searchBest(Student::name, 0, Match.words("anna")));
        assertThrows(IllegalArgumentException.class, () -> cache.searchBest(Student::programme, 3, "Physics"));
        assertEquals(new RankedPage<>(List.of(), 0), cache.searchBest(Student::name, 3, Match.words("zed")));
    }

    @Test void containsFindsOneAndTwoLetterTextInValuesOfEveryLengthAndForgetsRemovedOnes() {
        var cache = students(new Student(1, "Al"), new Student(2, "Ali Berg"), new Student(3, "Bo"), new Student(4, "Lars"), new Student(5, "x"));
        assertEquals(List.of(1L, 2L, 4L), ids(cache.search(Student.class, Student::name, Match.contains("l"))));
        assertEquals(List.of(1L, 2L), ids(cache.search(Student.class, Student::name, Match.contains("AL"))));
        assertEquals(List.of(5L), ids(cache.search(Student.class, Student::name, Match.contains("x"))));
        assertEquals(List.of(2L), ids(cache.search(Student.class, Student::name, Match.contains("ber"))));
        cache.remove(Student.class, 1L);
        cache.put(new Student(3, "Bjorn"));
        assertEquals(List.of(2L, 4L), ids(cache.search(Student.class, Student::name, Match.contains("l"))));
        assertEquals(List.of(), ids(cache.search(Student.class, Student::name, Match.contains("bo"))));
    }

    @Test void searchesStayExactWhileEntriesComeGoAndAreEvicted() {
        String[] first = {"Anna", "Per", "Ola", "Kari", "Ali", "Bo"};
        String[] last = {"Olsen", "Hansen", "Berg", "Andersen", "Li", "Dahl"};
        for (Entries entries : Entries.values()) {
            var cache = ZDCache.builder().entries(entries).capacity(Student.class, 300).build();
            cache.register(SPEC);
            Map<Long, Student> expected = new HashMap<>();
            Random random = new Random(entries.ordinal() + 5);
            for (int step = 0; step < 4_000; step++) {
                long id = random.nextInt(500);
                if (random.nextInt(6) == 0) {
                    cache.remove(Student.class, id);
                    expected.remove(id);
                } else {
                    Student student = new Student(id, first[random.nextInt(first.length)] + " " + last[random.nextInt(last.length)]);
                    cache.put(student);
                    expected.put(id, student);
                }
                if (step % 97 != 0) continue;
                expected.keySet().removeIf(key -> !cache.contains(Student.class, key));
                for (String query : List.of("ol", "an", "a", "sen", "li", "nnao", "e")) {
                    String lower = query.toLowerCase(Locale.ROOT);
                    List<Long> contains = expected.values().stream().filter(s -> s.name().toLowerCase(Locale.ROOT).contains(lower))
                        .map(Student::id).sorted().toList();
                    assertEquals(contains, ids(cache.search(Student.class, Student::name, Match.contains(query))), entries + " contains " + query);
                }
                for (String query : List.of("an ol", "ola", "b", "kari hansen")) {
                    List<String> words = List.of(query.split(" "));
                    List<Long> matching = expected.values().stream().filter(s -> words.stream().allMatch(w ->
                        List.of(s.name().toLowerCase(Locale.ROOT).split(" ")).stream().anyMatch(v -> v.startsWith(w)))).map(Student::id).sorted().toList();
                    assertEquals(matching, ids(cache.search(Student.class, Student::name, Match.words(query))), entries + " words " + query);
                    assertEquals(matching.size(), cache.searchBest(Student::name, 3, Match.words(query)).total());
                }
            }
        }
    }

    private static List<Long> ids(List<Student> found) {
        List<Long> ids = new ArrayList<>();
        for (Student student : found) ids.add(student.id());
        ids.sort(null);
        return ids;
    }
}
