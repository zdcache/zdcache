package io.github.zdcache.bench;

import io.github.zdcache.bench.text.Student;
import io.github.zdcache.bench.text.TextSubject;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class TextComparisonWriteBenchmark {
    private static final int RENAMES = 1024;

    @Param({"10000", "50000", "100000"})
    int size;

    @Param({"zdcache-text", "lucene-text"})
    String subject;

    private List<Student> students;
    private TextSubject engine;
    private final Student[] renamed = new Student[RENAMES];
    private int next;

    @Setup(Level.Trial)
    public void setUp() {
        students = TextComparisonBenchmark.students(size);
        engine = TextSubject.create(TextComparisonBenchmark.engineOf(subject));
        engine.load(students);
        Random random = new Random(11);
        for (int i = 0; i < RENAMES; i++) renamed[i] = new Student(random.nextInt(size), Names.person(random));
    }

    @TearDown(Level.Trial)
    public void tearDown() { engine.close(); }

    @Benchmark public void putVisibleAtOnce() { engine.put(renamed[next++ & (RENAMES - 1)]); }

    @Benchmark @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public Object bulkLoad() {
        try (TextSubject fresh = TextSubject.create(TextComparisonBenchmark.engineOf(subject))) {
            fresh.load(students);
            return fresh;
        }
    }
}
