package io.github.zdcache.bench;

import io.github.zdcache.bench.text.Student;
import io.github.zdcache.bench.text.TextQuery;
import io.github.zdcache.bench.text.TextSubject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class TextComparisonBenchmark {
    private static final int QUERIES = 1024;

    @Param({"10000", "50000", "100000"})
    int size;

    @Param({"zdcache-text-prefix", "lucene-text-prefix", "zdcache-text-words", "lucene-text-words",
            "zdcache-text-words-ranked", "lucene-text-words-ranked", "zdcache-text-fuzzy-words-one-edit", "lucene-text-fuzzy-words-one-edit",
            "zdcache-text-fuzzy-words", "lucene-text-fuzzy-words", "zdcache-text-fuzzy-whole-one-edit", "lucene-text-fuzzy-whole-one-edit",
            "zdcache-text-fuzzy-whole-two-edits", "lucene-text-fuzzy-whole-two-edits", "zdcache-text-contains", "lucene-text-contains",
            "zdcache-text-contains-two", "lucene-text-contains-two", "zdcache-text-words-best", "lucene-text-words-best",
            "zdcache-text-fuzzy-words-best", "lucene-text-fuzzy-words-best", "zdcache-text-contains-best", "lucene-text-contains-best"})
    String subject;

    private TextSubject engine;
    private final TextQuery[] queries = new TextQuery[QUERIES];
    private int next;

    static List<Student> students(int size) {
        Random random = new Random(42);
        List<Student> students = new ArrayList<>(size);
        for (int id = 0; id < size; id++) students.add(new Student(id, Names.person(random)));
        return students;
    }

    static String engineOf(String subject) { return subject.substring(0, subject.indexOf('-')); }

    static TextQuery.Kind kindOf(String subject) {
        return TextQuery.Kind.valueOf(subject.substring(subject.indexOf("-text-") + 6).toUpperCase(Locale.ROOT).replace('-', '_'));
    }

    @Setup(Level.Trial)
    public void setUp() {
        List<Student> students = students(size);
        engine = TextSubject.create(engineOf(subject));
        engine.load(students);
        Random random = new Random(7);
        TextQuery.Kind kind = kindOf(subject);
        for (int q = 0; q < QUERIES; q++) queries[q] = TextQuery.of(kind, students.get(random.nextInt(size)).name(), random);
    }

    @TearDown(Level.Trial)
    public void tearDown() { engine.close(); }

    @Benchmark public List<Student> search() { return engine.search(queries[next++ & (QUERIES - 1)]); }
}
