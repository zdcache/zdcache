package io.github.zdcache.bench;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkReportTest {
    private static final String JMH = """
        [
          {
            "benchmark": "io.github.zdcache.bench.LibraryBenchmark.get",
            "jdkVersion": "21.0.6", "forks": 3,
            "params": {"size": "100000", "subject": "zdcache-buffered-keys"},
            "primaryMetric": {"score": 27.73, "scoreError": 1.8, "scoreUnit": "ns/op",
              "rawData": [[26.0, 28.0], [60.0, 62.0], [27.0, 29.0]]},
            "secondaryMetrics": {}
          },
          {
            "benchmark": "io.github.zdcache.bench.LibraryBenchmark.put",
            "jdkVersion": "21.0.6", "forks": 3,
            "params": {"size": "100000", "subject": "zdcache-buffered-keys"},
            "primaryMetric": {"score": 1488.6, "scoreError": "NaN", "scoreUnit": "ns/op"},
            "secondaryMetrics": {}
          },
          {
            "benchmark": "io.github.zdcache.bench.ConcurrentBenchmark.mixed",
            "jdkVersion": "21.0.6", "forks": 3,
            "params": {"size": "100000", "subject": "caffeine"},
            "primaryMetric": {"score": 63.5, "scoreError": 5.5, "scoreUnit": "ops/us"},
            "secondaryMetrics": {
              "mixedRead": {"score": 56.25, "scoreError": 5.1, "scoreUnit": "ops/us"},
              "mixedWrite": {"score": 0.553, "scoreError": 0.07, "scoreUnit": "ops/us", "rawData": [[0.5, 0.6]]}
            }
          },
          {
            "benchmark": "io.github.zdcache.bench.LibrarySearchBenchmark.prefixName",
            "jdkVersion": "21.0.6", "forks": 3,
            "params": {"size": "100000", "subject": "cqengine"},
            "primaryMetric": {"score": 19.44, "scoreError": 0.42, "scoreUnit": "us/op"},
            "secondaryMetrics": {}
          }
        ]
        """;
    private static final String FOOTPRINT = """
        [
          {"subject": "zdcache-buffered-keys", "size": 100000, "bytesPerEntry": 122.5},
          {"subject": "cqengine", "size": 10000, "bytesPerEntry": 760.6}
        ]
        """;

    private static BenchmarkReport.Run run() {
        return BenchmarkReport.read(JMH, BenchmarkReport.readFootprint(FOOTPRINT), "full", "2026-10-13T10:00:00Z", 11);
    }

    @Test void readsBenchmarkNamesParametersAndEstimates() {
        var run = run();

        assertEquals("21.0.6", run.javaVersion());
        assertEquals(3, run.minForks());
        assertEquals(3, run.maxForks());
        assertEquals(4, run.results().size());
        var get = run.results().getFirst();
        assertEquals("LibraryBenchmark.get", get.benchmark());
        assertEquals("zdcache-buffered-keys", get.subject());
        assertEquals(100_000, get.size());
        assertEquals("ns/op", get.unit());
        assertEquals(new BenchmarkReport.Estimate(28.0, 27.0, 61.0), get.primary());
        assertEquals(new BenchmarkReport.Estimate(1488.6, 1488.6, 1488.6), run.results().get(1).primary());
        assertEquals(new BenchmarkReport.Estimate(0.55, 0.55, 0.55), run.results().get(2).secondary().get("mixedWrite"));
    }

    @Test void readsFootprintEntries() {
        assertEquals(List.of(new BenchmarkReport.Footprint("zdcache-buffered-keys", 100_000, 122.5),
                new BenchmarkReport.Footprint("cqengine", 10_000, 760.6)),
            BenchmarkReport.readFootprint(FOOTPRINT));
    }

    @Test void summaryHasOneRowPerLibraryAndADashForWhatWasNotMeasured() {
        String summary = BenchmarkReport.summary(run());

        assertTrue(summary.startsWith("Measured 2026-10-13T10:00:00Z on Java 21.0.6 with 11 CPUs, 3 forks per benchmark, at 100,000 entries."));
        assertTrue(summary.contains("| zdcache | 28 ns | 1,489 ns | 123 B |"));
        assertTrue(summary.contains("| Caffeine | – | 56 | 0.55 |"));
        assertTrue(summary.contains("| CQEngine, 4 indexes | 19 µs | – | – | – |"));
        assertTrue(summary.contains("| CQEngine, 4 indexes | – | – | – |"));
        assertEquals(6, summary.split("\\|---\\|").length - 1);
    }

    @Test void numbersKeepTwoSignificantDigitsBelowTen() {
        assertEquals("0.55", BenchmarkReport.number(0.553));
        assertEquals("8.1", BenchmarkReport.number(8.11));
        assertEquals("28", BenchmarkReport.number(27.73));
        assertEquals("4,341", BenchmarkReport.number(4341.04));
    }

    @Test void reportEmbedsTheRunAndEscapesClosingTags() {
        var run = new BenchmarkReport.Run("quick", "</script>", "21", 4, 1, 1, List.of(), List.of());

        String html = BenchmarkReport.html(run, "<script>const DATA = " + BenchmarkReport.PLACEHOLDER + ";</script>");

        assertFalse(html.contains(BenchmarkReport.PLACEHOLDER));
        assertTrue(html.contains("\"mode\":\"quick\""));
        assertTrue(html.contains("<\\/script>"));
        assertEquals(1, html.split("</script>", -1).length - 1);
    }

    @Test void templateWithoutThePlaceholderIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> BenchmarkReport.html(run(), "<html></html>"));
    }

    @Test void missingErrorsAreWrittenAsNull() {
        String html = BenchmarkReport.html(run(), BenchmarkReport.PLACEHOLDER);

        assertTrue(html.contains("\"score\":1488.6,\"low\":null,\"high\":null"));
        assertTrue(html.contains("\"score\":28.0,\"low\":27.0,\"high\":61.0"));
    }

    @Test void forksAreShownAsARangeWhenBenchmarksDiffer() {
        var mixed = new BenchmarkReport.Run("full", "t", "21", 4, 2, 3, List.of(), List.of());
        var same = new BenchmarkReport.Run("full", "t", "21", 4, 3, 3, List.of(), List.of());
        var single = new BenchmarkReport.Run("full", "t", "21", 4, 1, 1, List.of(), List.of());

        assertEquals("2–3 forks", BenchmarkReport.forks(mixed));
        assertEquals("3 forks", BenchmarkReport.forks(same));
        assertEquals("1 fork", BenchmarkReport.forks(single));
    }

    @Test void summaryUsesTheLargestMeasuredSize() {
        String json = JMH.replace("\"size\": \"100000\", \"subject\": \"zdcache-buffered-keys\"", "\"size\": \"250000\", \"subject\": \"zdcache-buffered-keys\"");
        var run = BenchmarkReport.read(json, List.of(), "full", "t", 4);

        assertEquals(250_000, BenchmarkReport.summarySize(run));
        assertTrue(BenchmarkReport.summary(run).contains("at 250,000 entries"));
    }

    @Test void anEvenNumberOfForksTakesTheMiddleTwo() throws Exception {
        var metric = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree("{\"score\": 9, \"rawData\": [[10.0], [20.0], [40.0], [400.0]]}");

        assertEquals(new BenchmarkReport.Estimate(30.0, 10.0, 400.0), BenchmarkReport.estimate(metric));
    }

    @Test void lockingResultsAreNamedAfterTheirStrategyAndSummarised() {
        String json = """
            [
              {"benchmark": "io.github.zdcache.bench.LockingBenchmark.writesToSeparateTypes", "jdkVersion": "21", "forks": 3,
               "params": {"size": "100000", "locking": "SINGLE"},
               "primaryMetric": {"score": 1.5, "scoreUnit": "ops/us", "rawData": [[1.4, 1.6]]}, "secondaryMetrics": {}},
              {"benchmark": "io.github.zdcache.bench.LockingBenchmark.mixed", "jdkVersion": "21", "forks": 3,
               "params": {"size": "100000", "locking": "PER_TYPE"},
               "primaryMetric": {"score": 30, "scoreUnit": "ops/us", "rawData": [[30.0]]},
               "secondaryMetrics": {"mixedRead": {"score": 28, "rawData": [[28.0]]}, "mixedWrite": {"score": 2, "rawData": [[2.0]]}}}
            ]
            """;
        var run = BenchmarkReport.read(json, List.of(), "full", "t", 4);

        assertEquals("zdcache-locking-single", run.results().getFirst().subject());
        assertEquals("zdcache-locking-per-type", run.results().get(1).subject());
        String summary = BenchmarkReport.summary(run);
        assertTrue(summary.contains("| zdcache, `Locking.SINGLE` | 1.5 | – | – | – | – |"));
        assertTrue(summary.contains("| zdcache, `Locking.PER_TYPE` | – | – | – | 28 | 2.0 |"));
    }

    @Test void zdcacheConfigurationsAreSummarisedSeparatelyFromTheLibraries() {
        String json = """
            [
              {"benchmark": "io.github.zdcache.bench.ConfigurationBenchmark.putAtomically", "jdkVersion": "21", "forks": 3,
               "params": {"size": "50000", "subject": "zdcache-single-indexed"},
               "primaryMetric": {"score": 2100, "scoreUnit": "ns/op", "rawData": [[2100.0]]}, "secondaryMetrics": {}},
              {"benchmark": "io.github.zdcache.bench.ConfigurationSearchBenchmark.prefixName", "jdkVersion": "21", "forks": 3,
               "params": {"size": "50000", "subject": "zdcache-single-indexed"},
               "primaryMetric": {"score": 1.2, "scoreUnit": "us/op", "rawData": [[1.2]]}, "secondaryMetrics": {}}
            ]
            """;
        String summary = BenchmarkReport.summary(BenchmarkReport.read(json, List.of(), "full", "t", 4));

        assertTrue(summary.contains("| `Locking.SINGLE`, 4 indexes | – | – | 2,100 ns | 1.2 µs | – |"));
        assertTrue(summary.contains("| zdcache, approximate, 4 indexes | – | – | – | – |"));
    }

    @Test void compactHeaderMemoryIsReportedNextToTheStandardFigure() {
        var compact = List.of(new BenchmarkReport.Footprint("zdcache-buffered-keys", 100_000, 106.5));
        var run = run().withCompactFootprint(compact);

        String summary = BenchmarkReport.summary(run);
        assertTrue(summary.contains("| get | put | Memory per entry | With compact headers |"));
        assertTrue(summary.contains("| zdcache | 28 ns | 1,489 ns | 123 B | 107 B |"));
        assertTrue(summary.contains("| Caffeine | – | – | – | – |"));
        String html = BenchmarkReport.html(run, "<script>const DATA = " + BenchmarkReport.PLACEHOLDER + ";</script>");
        assertTrue(html.contains("\"compactFootprint\":[{\"subject\":\"zdcache-buffered-keys\",\"size\":100000,\"bytesPerEntry\":106.5}]"));
    }

    @Test void aRunWithoutCompactHeaderMemoryShowsNoFigure() {
        assertTrue(BenchmarkReport.summary(run()).contains("| zdcache | 28 ns | 1,489 ns | 123 B | – |"));
    }

    @Test void concurrentSearchesAndOccasionalWritesAreSummarised() {
        String json = """
            [
              {"benchmark": "io.github.zdcache.bench.ConcurrentSearchBenchmark.searchOnly", "jdkVersion": "25", "forks": 3,
               "params": {"size": "50000", "subject": "zdcache-buffered-indexed"},
               "primaryMetric": {"score": 170, "scoreUnit": "ops/ms", "rawData": [[170.0]]}, "secondaryMetrics": {}},
              {"benchmark": "io.github.zdcache.bench.ConcurrentSearchBenchmark.searchWithWrites", "jdkVersion": "25", "forks": 3,
               "params": {"size": "50000", "subject": "zdcache-buffered-indexed"},
               "primaryMetric": {"score": 113, "scoreUnit": "ops/ms", "rawData": [[113.0]]},
               "secondaryMetrics": {"searches": {"score": 108, "rawData": [[108.0]]}, "writes": {"score": 5.4, "rawData": [[5.4]]}}}
            ]
            """;
        String summary = BenchmarkReport.summary(BenchmarkReport.read(json, List.of(), "full", "t", 4));

        assertTrue(summary.contains("**Concurrent search, 4 threads (thousand operations per second)**"));
        assertTrue(summary.contains("| zdcache, 4 indexes | 170 | 108 | 5.4 |"));
        assertTrue(summary.contains("| CQEngine, 4 indexes | – | – | – |"));
    }
}
