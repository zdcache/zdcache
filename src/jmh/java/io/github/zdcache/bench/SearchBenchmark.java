package io.github.zdcache.bench;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class SearchBenchmark {
    @Param({"10000", "100000"})
    int size;

    @Param({Subjects.CAFFEINE, Subjects.ZDCACHE_LHM_INDEXED, Subjects.ZDCACHE_CAFFEINE_INDEXED, Subjects.ZDCACHE_BUFFERED_INDEXED,
            Subjects.GUAVA, Subjects.CACHE2K, Subjects.CQENGINE})
    String subject;

    private CacheSubject cache;
    private Queries queries;
    private int next;

    @Setup(Level.Trial)
    public void setUp() {
        Person[] people = Dataset.people(size, 42);
        queries = new Queries(people, 7);
        cache = Subjects.create(subject, size);
        for (Person person : people) cache.put(person);
    }

    private int query() {
        return next++ & (Queries.COUNT - 1);
    }

    @Benchmark
    public List<Person> exactCity() {
        return cache.byCity(queries.cities[query()]);
    }

    @Benchmark
    public List<Person> prefixName() {
        return cache.byNamePrefix(queries.namePrefixes[query()]);
    }

    @Benchmark
    public List<Person> rangeAge() {
        int q = query();
        return cache.byAge(queries.ageFrom[q], queries.ageTo[q]);
    }

    @Benchmark
    public List<Person> compoundCityAge() {
        int q = query();
        return cache.byCityAndAge(queries.cities[q], queries.ageFrom[q], queries.ageTo[q]);
    }
}
