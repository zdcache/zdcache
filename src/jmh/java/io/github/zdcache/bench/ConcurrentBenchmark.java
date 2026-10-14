package io.github.zdcache.bench;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class ConcurrentBenchmark {
    private static final int ORDER_MASK = (1 << 16) - 1;

    @Param({"1000", "3000", "5000", "10000", "50000"})
    int size;

    @Param({Subjects.ZDCACHE_BUFFERED_KEYS, Subjects.ZDCACHE_BUFFERED_INDEXED, Subjects.ZDCACHE_APPROXIMATE_KEYS,
            Subjects.ZDCACHE_APPROXIMATE_INDEXED, Subjects.ZDCACHE_LHM_KEYS, Subjects.ZDCACHE_CAFFEINE_KEYS,
            Subjects.CAFFEINE, Subjects.CACHE2K, Subjects.GUAVA, Subjects.EHCACHE, Subjects.CONCURRENT_HASH_MAP,
            Subjects.HAZELCAST, Subjects.CQENGINE})
    String subject;

    CacheSubject cache;
    Person[][] versions;

    @Setup(Level.Trial)
    public void setUp() {
        Person[] people = Dataset.people(size, 42);
        versions = new Person[][] {people, Dataset.changed(people, 43)};
        cache = Subjects.create(subject, size);
        for (Person person : people) cache.put(person);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        cache.close();
    }

    @State(Scope.Thread)
    public static class Cursor {
        int[] order;
        int next;

        @Setup(Level.Trial)
        public void setUp(ConcurrentBenchmark benchmark) {
            order = Dataset.accessOrder(benchmark.size, ORDER_MASK + 1, Thread.currentThread().threadId());
        }

        int nextIndex() {
            return order[next++ & ORDER_MASK];
        }
    }

    @Benchmark
    @Threads(8)
    public Person readOnly(Cursor cursor) {
        return cache.get(cursor.nextIndex());
    }

    @Benchmark
    @Group("mixed")
    @GroupThreads(6)
    public Person mixedRead(Cursor cursor) {
        return cache.get(cursor.nextIndex());
    }

    @Benchmark
    @Group("mixed")
    @GroupThreads(2)
    public void mixedWrite(Cursor cursor) {
        int i = cursor.next;
        cache.put(versions[(i >>> 16) & 1][cursor.nextIndex()]);
    }
}
