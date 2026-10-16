package io.github.zdcache.bench;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.openjdk.jmh.annotations.*;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class ConcurrentSearchBenchmark {
    private static final int ORDER_MASK = (1 << 16) - 1;
    private static final long WRITE_PAUSE_NANOS = 100_000;

    @Param({"1000", "3000", "5000", "10000", "50000"})
    int size;

    @Param({Subjects.ZDCACHE_BUFFERED_INDEXED, Subjects.ZDCACHE_NON_BLOCKING_INDEXED, Subjects.ZDCACHE_APPROXIMATE_INDEXED,
            Subjects.ZDCACHE_SINGLE_INDEXED, Subjects.ZDCACHE_PER_PUT_INDEXED, Subjects.ZDCACHE_LHM_INDEXED, Subjects.ZDCACHE_CAFFEINE_INDEXED,
            Subjects.CQENGINE, Subjects.HAZELCAST, Subjects.CAFFEINE})
    String subject;

    CacheSubject cache;
    Queries queries;
    Person[][] versions;

    @Setup(Level.Trial)
    public void setUp() {
        Person[] people = Dataset.people(size, 42);
        queries = new Queries(people, 7);
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
        public void setUp(ConcurrentSearchBenchmark benchmark) {
            order = Dataset.accessOrder(benchmark.size, ORDER_MASK + 1, Thread.currentThread().threadId());
            next = (int) Thread.currentThread().threadId();
        }
    }

    private List<Person> search(Cursor cursor) {
        int i = cursor.next++;
        int q = i & (Queries.COUNT - 1);
        return switch ((i >>> 4) & 3) {
            case 0 -> cache.byNamePrefix(queries.namePrefixes[q]);
            case 1 -> cache.byCity(queries.cities[q]);
            case 2 -> cache.byCityAndAge(queries.cities[q], queries.ageFrom[q], queries.ageTo[q]);
            default -> cache.byAge(queries.ageFrom[q], queries.ageTo[q]);
        };
    }

    @Benchmark
    @Threads(4)
    public List<Person> searchOnly(Cursor cursor) {
        return search(cursor);
    }

    @Benchmark
    @Group("searchWithWrites")
    @GroupThreads(3)
    public List<Person> searches(Cursor cursor) {
        return search(cursor);
    }

    @Benchmark
    @Group("searchWithWrites")
    @GroupThreads(1)
    public void writes(Cursor cursor) {
        int i = cursor.next++;
        cache.put(versions[(i >>> 16) & 1][cursor.order[i & ORDER_MASK]]);
        LockSupport.parkNanos(WRITE_PAUSE_NANOS);
    }
}
