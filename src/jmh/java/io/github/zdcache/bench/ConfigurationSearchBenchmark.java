package io.github.zdcache.bench;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class ConfigurationSearchBenchmark extends SearchBase {
    @Param({Subjects.ZDCACHE_BUFFERED_INDEXED, Subjects.ZDCACHE_APPROXIMATE_INDEXED, Subjects.ZDCACHE_LHM_INDEXED,
            Subjects.ZDCACHE_CAFFEINE_INDEXED, Subjects.ZDCACHE_SINGLE_INDEXED,
            Subjects.ZDCACHE_PER_PUT_INDEXED, Subjects.ZDCACHE_NON_BLOCKING_INDEXED})
    String subject;

    @Override String subject() { return subject; }
}
