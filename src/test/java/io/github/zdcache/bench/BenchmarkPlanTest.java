package io.github.zdcache.bench;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkPlanTest {
    private static final String EVERY = """
        *.forks=2
        *.warmupIterations=2
        *.warmupTime=1s
        *.measurementIterations=3
        *.measurementTime=500ms
        """;

    @Test void everyBenchmarkUsesTheSharedSettings() {
        var plan = BenchmarkPlan.parse(EVERY);

        for (String benchmark : BenchmarkPlan.BENCHMARKS) {
            assertEquals(new BenchmarkPlan.Settings(2, 2, Duration.ofSeconds(1), 3, Duration.ofMillis(500), List.of(), List.of()),
                plan.settings(benchmark));
        }
    }

    @Test void aBenchmarkOverridesTheSharedSettingsAndNarrowsItsParameters() {
        var plan = BenchmarkPlan.parse(EVERY + """
            ConcurrentBenchmark.forks=3
            SearchBenchmark.measurementTime=2s
            SearchBenchmark.sizes=100000
            SearchBenchmark.subjects=zdcache-buffered-indexed, cqengine
            """);

        assertEquals(3, plan.settings("ConcurrentBenchmark").forks());
        assertEquals(2, plan.settings("KeyLookupBenchmark").forks());
        var search = plan.settings("SearchBenchmark");
        assertEquals(Duration.ofSeconds(2), search.measurementTime());
        assertEquals(List.of("100000"), search.sizes());
        assertEquals(List.of("zdcache-buffered-indexed", "cqengine"), search.subjects());
    }

    @Test void everySharedSettingIsRequired() {
        var thrown = assertThrows(IllegalArgumentException.class, () -> BenchmarkPlan.parse(EVERY.replace("*.forks=2\n", "")));
        assertTrue(thrown.getMessage().contains("*.forks"));
    }

    @Test void unknownBenchmarksOptionsAndSharedNarrowingAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> BenchmarkPlan.parse(EVERY + "PutBenchmark.forks=1\n"));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkPlan.parse(EVERY + "SearchBenchmark.threads=4\n"));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkPlan.parse(EVERY + "*.sizes=10000\n"));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkPlan.parse(EVERY + "forks=1\n"));
    }

    @Test void valuesOutsideTheLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> BenchmarkPlan.parse(EVERY + "KeyLookupBenchmark.forks=0\n"));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkPlan.parse(EVERY + "KeyLookupBenchmark.forks=11\n"));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkPlan.parse(EVERY + "KeyLookupBenchmark.measurementIterations=0\n"));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkPlan.parse(EVERY + "KeyLookupBenchmark.warmupTime=50ms\n"));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkPlan.parse(EVERY + "KeyLookupBenchmark.warmupTime=1m\n"));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkPlan.parse(EVERY + "KeyLookupBenchmark.sizes=10k\n"));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkPlan.parse(EVERY + "KeyLookupBenchmark.subjects=memcached\n"));
        assertEquals(0, BenchmarkPlan.parse(EVERY + "KeyLookupBenchmark.warmupIterations=0\n").settings("KeyLookupBenchmark").warmupIterations());
    }

    @Test void theFullAndQuickPlansAreValid() {
        var full = BenchmarkPlan.read(Path.of("src/jmh/resources/benchmark-full.properties"));
        var quick = BenchmarkPlan.read(Path.of("src/jmh/resources/benchmark-quick.properties"));

        for (String benchmark : BenchmarkPlan.BENCHMARKS) {
            assertTrue(full.settings(benchmark).forks() >= 3, benchmark);
            assertTrue(quick.settings(benchmark).forks() >= 1, benchmark);
        }
    }

    @Test void memoryFootprintSizesDefaultAndCanBeChosen() {
        assertEquals(List.of(10_000, 100_000), BenchmarkPlan.parse(EVERY).footprintSizes());
        assertEquals(List.of(1_000, 50_000), BenchmarkPlan.parse(EVERY + "MemoryFootprint.sizes=1000,50000\n").footprintSizes());
        assertThrows(IllegalArgumentException.class, () -> BenchmarkPlan.parse(EVERY + "MemoryFootprint.forks=2\n"));
    }

    @Test void sizesAreCappedAtOneMillion() {
        assertEquals(List.of("1000000"), BenchmarkPlan.parse(EVERY + "SearchBenchmark.sizes=1000000\n").settings("SearchBenchmark").sizes());
        var thrown = assertThrows(IllegalArgumentException.class,
            () -> BenchmarkPlan.parse(EVERY + "SearchBenchmark.sizes=1000001\n"));
        assertTrue(thrown.getMessage().contains("1 to 1000000"));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkPlan.parse(EVERY + "MemoryFootprint.sizes=99999999\n"));
    }
}
