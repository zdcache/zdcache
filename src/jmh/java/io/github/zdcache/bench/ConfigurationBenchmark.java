package io.github.zdcache.bench;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class ConfigurationBenchmark extends KeyLookupBase {
    @Param({Subjects.ZDCACHE_BUFFERED_KEYS, Subjects.ZDCACHE_BUFFERED_INDEXED, Subjects.ZDCACHE_APPROXIMATE_KEYS,
            Subjects.ZDCACHE_APPROXIMATE_INDEXED, Subjects.ZDCACHE_LHM_KEYS, Subjects.ZDCACHE_LHM_INDEXED,
            Subjects.ZDCACHE_CAFFEINE_KEYS, Subjects.ZDCACHE_CAFFEINE_INDEXED, Subjects.ZDCACHE_SINGLE_INDEXED})
    String subject;

    @Override String subject() { return subject; }

    @Benchmark
    public void putKeepingIndexedValues() {
        cache.put(nextRetagged());
    }

    @Benchmark
    public void putAtomically() {
        cache.putAtomically(nextVersion());
    }
}
