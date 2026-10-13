package io.github.zdcache.bench;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class KeyLookupBenchmark {
    private static final int ORDER_MASK = (1 << 16) - 1;

    @Param({"10000", "100000"})
    int size;

    @Param({Subjects.CAFFEINE, Subjects.ZDCACHE_LHM_KEYS, Subjects.ZDCACHE_CAFFEINE_KEYS,
            Subjects.ZDCACHE_LHM_INDEXED, Subjects.ZDCACHE_CAFFEINE_INDEXED, Subjects.ZDCACHE_LHM_INDEXED_LAMBDA,
            Subjects.ZDCACHE_BUFFERED_KEYS, Subjects.ZDCACHE_BUFFERED_INDEXED,
            Subjects.GUAVA, Subjects.CACHE2K, Subjects.CQENGINE})
    String subject;

    private CacheSubject cache;
    private Person[][] versions;
    private int[] order;
    private int next;

    @Setup(Level.Trial)
    public void setUp() {
        Person[] people = Dataset.people(size, 42);
        versions = new Person[][] {people, Dataset.changed(people, 43)};
        order = Dataset.accessOrder(size, ORDER_MASK + 1, 44);
        cache = Subjects.create(subject, size);
        for (Person person : people) cache.put(person);
    }

    @Benchmark
    public Person get() {
        int i = next++;
        return cache.get(order[i & ORDER_MASK]);
    }

    @Benchmark
    public void put() {
        int i = next++;
        cache.put(versions[(i >>> 16) & 1][order[i & ORDER_MASK]]);
    }
}
