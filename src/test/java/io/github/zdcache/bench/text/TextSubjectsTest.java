package io.github.zdcache.bench.text;

import io.github.zdcache.bench.Names;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextSubjectsTest {
    private static List<Student> students(int size, long seed) {
        Random random = new Random(seed);
        List<Student> students = new ArrayList<>(size);
        for (int id = 0; id < size; id++) students.add(new Student(id, Names.person(random)));
        return students;
    }

    private static List<Long> ids(List<Student> found) { return found.stream().map(Student::id).sorted().toList(); }

    @Test void zdcacheAndLuceneFindTheSameStudentsForEveryKindOfQuery() {
        List<Student> students = students(2_000, 42);
        try (TextSubject zdcache = TextSubject.create(TextSubject.ZDCACHE); TextSubject lucene = TextSubject.create(TextSubject.LUCENE)) {
            zdcache.load(students);
            lucene.load(students);
            Random random = new Random(7);
            for (TextQuery.Kind kind : TextQuery.Kind.values()) {
                int nonEmpty = 0;
                for (int round = 0; round < 60; round++) {
                    TextQuery query = TextQuery.of(kind, students.get(random.nextInt(students.size())).name(), random);
                    if (kind.best()) {
                        TextSubject.Best ours = zdcache.best(query, TextQuery.BEST), theirs = lucene.best(query, TextQuery.BEST);
                        assertEquals(ours.total(), theirs.total(), query::toString);
                        assertEquals(Math.min(TextQuery.BEST, ours.total()), ours.results().size(), query::toString);
                        assertEquals(ours.results().size(), theirs.results().size(), query::toString);
                        if (ours.total() > 0) nonEmpty++;
                        continue;
                    }
                    List<Long> expected = ids(zdcache.search(query));
                    assertEquals(expected, ids(lucene.search(query)), query::toString);
                    if (!expected.isEmpty()) nonEmpty++;
                }
                assertTrue(nonEmpty > 30, kind + " finds something most of the time, found " + nonEmpty);
            }
        }
    }

    @Test void aReplacedStudentIsFoundByItsNewNameAndNotItsOldOneAtOnceInBoth() {
        List<Student> students = students(200, 1);
        String old = students.get(5).name().toLowerCase(Locale.ROOT);
        for (String name : List.of(TextSubject.ZDCACHE, TextSubject.LUCENE)) {
            try (TextSubject subject = TextSubject.create(name)) {
                subject.load(students);
                subject.put(new Student(5, "Zelda Quist"));
                assertEquals(List.of(5L), ids(subject.search(new TextQuery(TextQuery.Kind.WORDS, "zel qui"))), name);
                assertFalse(ids(subject.search(new TextQuery(TextQuery.Kind.PREFIX, old))).contains(5L), name);
            }
        }
    }
}
