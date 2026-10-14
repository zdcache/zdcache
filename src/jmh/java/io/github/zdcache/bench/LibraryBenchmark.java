package io.github.zdcache.bench;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class LibraryBenchmark extends KeyLookupBase {
    @Param({Subjects.ZDCACHE_BUFFERED_KEYS, Subjects.ZDCACHE_BUFFERED_INDEXED, Subjects.ZDCACHE_APPROXIMATE_KEYS,
            Subjects.ZDCACHE_APPROXIMATE_INDEXED, Subjects.CAFFEINE, Subjects.CACHE2K, Subjects.GUAVA, Subjects.EHCACHE,
            Subjects.CONCURRENT_HASH_MAP, Subjects.HAZELCAST, Subjects.CQENGINE})
    String subject;

    @Override String subject() { return subject; }
}
