package io.github.zdcache.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public final class BenchmarkReport {
    static final String PLACEHOLDER = "/*BENCHMARK_DATA*/null";

    record Estimate(double score, double low, double high) {}
    record Measurement(String benchmark, String subject, int size, String unit, Estimate primary, Map<String, Estimate> secondary) {}
    record Footprint(String subject, int size, double bytesPerEntry) {}
    record Run(String mode, String generatedAt, String javaVersion, int cpuCount, int minForks, int maxForks,
               List<Measurement> results, List<Footprint> footprint, List<Footprint> compactFootprint) {
        Run(String mode, String generatedAt, String javaVersion, int cpuCount, int minForks, int maxForks,
            List<Measurement> results, List<Footprint> footprint) {
            this(mode, generatedAt, javaVersion, cpuCount, minForks, maxForks, results, footprint, List.of());
        }

        Run withCompactFootprint(List<Footprint> compact) {
            return new Run(mode, generatedAt, javaVersion, cpuCount, minForks, maxForks, results, footprint, List.copyOf(compact));
        }
    }

    private record Row(String subject, String label) {}

    private static final List<Row> LIBRARIES = List.of(
        new Row(Subjects.ZDCACHE_BUFFERED_KEYS, "zdcache"),
        new Row(Subjects.ZDCACHE_BUFFERED_INDEXED, "zdcache, 4 indexes"),
        new Row(Subjects.ZDCACHE_APPROXIMATE_KEYS, "zdcache, approximate recency"),
        new Row(Subjects.ZDCACHE_APPROXIMATE_INDEXED, "zdcache, approximate, 4 indexes"),
        new Row(Subjects.CAFFEINE, "Caffeine"),
        new Row(Subjects.CACHE2K, "cache2k"),
        new Row(Subjects.GUAVA, "Guava"),
        new Row(Subjects.EHCACHE, "Ehcache"),
        new Row(Subjects.CONCURRENT_HASH_MAP, "ConcurrentHashMap, no eviction"),
        new Row(Subjects.HAZELCAST, "Hazelcast, 3 indexes"),
        new Row(Subjects.CQENGINE, "CQEngine, 4 indexes"));
    private static final List<Row> CONFIGURATIONS = List.of(
        new Row(Subjects.ZDCACHE_BUFFERED_KEYS, "Buffered LRU (default)"),
        new Row(Subjects.ZDCACHE_BUFFERED_INDEXED, "Buffered LRU, 4 indexes"),
        new Row(Subjects.ZDCACHE_APPROXIMATE_KEYS, "Approximate recency"),
        new Row(Subjects.ZDCACHE_APPROXIMATE_INDEXED, "Approximate recency, 4 indexes"),
        new Row(Subjects.ZDCACHE_LHM_KEYS, "LinkedHashMap store"),
        new Row(Subjects.ZDCACHE_LHM_INDEXED, "LinkedHashMap store, 4 indexes"),
        new Row(Subjects.ZDCACHE_CAFFEINE_KEYS, "Caffeine store"),
        new Row(Subjects.ZDCACHE_CAFFEINE_INDEXED, "Caffeine store, 4 indexes"),
        new Row(Subjects.ZDCACHE_SINGLE_INDEXED, "`Locking.SINGLE`, 4 indexes"),
        new Row(Subjects.ZDCACHE_PER_PUT_INDEXED, "`Entries.PER_PUT`, 4 indexes"));
    private static final List<Row> SEARCHERS = List.of(
        new Row(Subjects.ZDCACHE_BUFFERED_INDEXED, "zdcache, 4 indexes"),
        new Row(Subjects.ZDCACHE_APPROXIMATE_INDEXED, "zdcache, approximate, 4 indexes"),
        new Row(Subjects.HAZELCAST, "Hazelcast, 3 indexes"),
        new Row(Subjects.CQENGINE, "CQEngine, 4 indexes"),
        new Row(Subjects.CAFFEINE, "Caffeine (full scan)"),
        new Row(Subjects.CACHE2K, "cache2k (full scan)"),
        new Row(Subjects.GUAVA, "Guava (full scan)"),
        new Row(Subjects.EHCACHE, "Ehcache (full scan)"),
        new Row(Subjects.CONCURRENT_HASH_MAP, "ConcurrentHashMap (full scan)"));
    private static final List<Row> CONCURRENT_SEARCHERS = List.of(
        new Row(Subjects.ZDCACHE_BUFFERED_INDEXED, "zdcache, 4 indexes"),
        new Row(Subjects.ZDCACHE_APPROXIMATE_INDEXED, "zdcache, approximate, 4 indexes"),
        new Row(Subjects.ZDCACHE_SINGLE_INDEXED, "zdcache, `Locking.SINGLE`, 4 indexes"),
        new Row(Subjects.ZDCACHE_PER_PUT_INDEXED, "zdcache, `Entries.PER_PUT`, 4 indexes"),
        new Row(Subjects.ZDCACHE_LHM_INDEXED, "zdcache, LinkedHashMap store, 4 indexes"),
        new Row(Subjects.ZDCACHE_CAFFEINE_INDEXED, "zdcache, Caffeine store, 4 indexes"),
        new Row(Subjects.HAZELCAST, "Hazelcast, 3 indexes"),
        new Row(Subjects.CQENGINE, "CQEngine, 4 indexes"),
        new Row(Subjects.CAFFEINE, "Caffeine (full scan)"));
    private static final List<String> QUERIES = List.of("prefixName", "exactCity", "compoundCityAge", "rangeAge");

    private static final List<Row> LOCKINGS = List.of(
        new Row("zdcache-locking-per-type", "zdcache, `Locking.PER_TYPE`"),
        new Row("zdcache-locking-single", "zdcache, `Locking.SINGLE`"));

    private static final ObjectMapper JSON = new ObjectMapper();

    private BenchmarkReport() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 4 && args.length != 5) {
            throw new IllegalArgumentException("Usage: BenchmarkReport <jmh results.json> <footprint results.json> <mode> <output dir>"
                + " [<compact-header footprint results.json>]");
        }
        Path jmhResults = Path.of(args[0]);
        Path footprintResults = Path.of(args[1]);
        Path output = Path.of(args[3]);
        if (!Files.exists(jmhResults)) throw new IllegalStateException("No JMH results at " + jmhResults + "; run the benchmarks first");
        List<Footprint> footprint = Files.exists(footprintResults) ? readFootprint(Files.readString(footprintResults)) : List.of();
        String generatedAt = Files.getLastModifiedTime(jmhResults).toInstant().truncatedTo(ChronoUnit.SECONDS).toString();
        Run run = read(Files.readString(jmhResults), footprint, args[2], generatedAt, Runtime.getRuntime().availableProcessors());
        Path compactResults = args.length == 5 ? Path.of(args[4]) : null;
        if (compactResults != null && Files.exists(compactResults)) {
            run = run.withCompactFootprint(readFootprint(Files.readString(compactResults)));
        }

        Files.createDirectories(output);
        Files.writeString(output.resolve("index.html"), html(run, template()));
        Files.writeString(output.resolve("summary.md"), summary(run));
        Files.writeString(output.resolve("run-mode.txt"), run.mode() + "\n");
        System.out.println("Benchmark report written to " + output.resolve("index.html").toAbsolutePath());
    }

    static Run read(String jmhJson, List<Footprint> footprint, String mode, String generatedAt, int cpuCount) {
        JsonNode results = parse(jmhJson);
        List<Measurement> measurements = new ArrayList<>();
        String javaVersion = "";
        int minForks = Integer.MAX_VALUE;
        int maxForks = 0;
        for (JsonNode result : results) {
            JsonNode params = result.path("params");
            JsonNode primary = result.path("primaryMetric");
            Map<String, Estimate> secondary = new LinkedHashMap<>();
            result.path("secondaryMetrics").properties().forEach(entry -> secondary.put(entry.getKey(), estimate(entry.getValue())));
            String benchmark = result.path("benchmark").asText();
            String subject = params.has("subject") ? params.path("subject").asText()
                : "zdcache-locking-" + params.path("locking").asText().toLowerCase(Locale.ROOT).replace('_', '-');
            measurements.add(new Measurement(benchmark.substring(benchmark.lastIndexOf('.', benchmark.lastIndexOf('.') - 1) + 1),
                subject, params.path("size").asInt(), primary.path("scoreUnit").asText(),
                estimate(primary), secondary));
            javaVersion = result.path("jdkVersion").asText(javaVersion);
            minForks = Math.min(minForks, result.path("forks").asInt());
            maxForks = Math.max(maxForks, result.path("forks").asInt());
        }
        return new Run(mode, generatedAt, javaVersion, cpuCount, measurements.isEmpty() ? 0 : minForks, maxForks,
            List.copyOf(measurements), List.copyOf(footprint));
    }

    static List<Footprint> readFootprint(String json) {
        List<Footprint> footprint = new ArrayList<>();
        for (JsonNode entry : parse(json)) {
            footprint.add(new Footprint(entry.path("subject").asText(), entry.path("size").asInt(), entry.path("bytesPerEntry").asDouble()));
        }
        return List.copyOf(footprint);
    }

    static String html(Run run, String template) {
        if (!template.contains(PLACEHOLDER)) throw new IllegalArgumentException("Template lacks " + PLACEHOLDER);
        return template.replace(PLACEHOLDER, data(run).toString().replace("</", "<\\/"));
    }

    static String summary(Run run) {
        int size = summarySize(run);
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "Measured %s on Java %s with %d CPUs, %s per benchmark, at %,d entries. "
                + "Lower is better for time and memory; higher is better for operations per second.%n%n",
            run.generatedAt(), run.javaVersion(), run.cpuCount(), forks(run), size));

        out.append("**Single thread and memory**\n\n| | get | put | Memory per entry | With compact headers |\n|---|---:|---:|---:|---:|\n");
        for (Row row : LIBRARIES) {
            out.append("| ").append(row.label())
                .append(" | ").append(cell(primary(run, "LibraryBenchmark.get", row.subject(), size), BenchmarkReport::time))
                .append(" | ").append(cell(primary(run, "LibraryBenchmark.put", row.subject(), size), BenchmarkReport::time))
                .append(" | ").append(cell(bytes(run, row.subject(), size), BenchmarkReport::memory))
                .append(" | ").append(cell(compactBytes(run, row.subject(), size), BenchmarkReport::memory))
                .append(" |\n");
        }

        out.append("\n**zdcache configurations, single thread**\n\n"
            + "| | get | put | put in `atomically` | Prefix search | Memory per entry | With compact headers |\n"
            + "|---|---:|---:|---:|---:|---:|---:|\n");
        for (Row row : CONFIGURATIONS) {
            out.append("| ").append(row.label());
            for (String benchmark : List.of("get", "put", "putAtomically")) {
                out.append(" | ").append(cell(primary(run, "ConfigurationBenchmark." + benchmark, row.subject(), size), BenchmarkReport::time));
            }
            out.append(" | ").append(cell(primary(run, "ConfigurationSearchBenchmark.prefixName", row.subject(), size), BenchmarkReport::time))
                .append(" | ").append(cell(bytes(run, row.subject(), size), BenchmarkReport::memory))
                .append(" | ").append(cell(compactBytes(run, row.subject(), size), BenchmarkReport::memory))
                .append(" |\n");
        }

        out.append("\n**Concurrent, 4 threads (million operations per second)**\n\n"
            + "| | Reads only | Mixed: reads | Mixed: writes |\n|---|---:|---:|---:|\n");
        for (Row row : LIBRARIES) {
            out.append("| ").append(row.label())
                .append(" | ").append(cell(primary(run, "ConcurrentBenchmark.readOnly", row.subject(), size).map(Measurement::primary), BenchmarkReport::throughput))
                .append(" | ").append(cell(secondary(run, "mixedRead", row.subject()), BenchmarkReport::throughput))
                .append(" | ").append(cell(secondary(run, "mixedWrite", row.subject()), BenchmarkReport::throughput))
                .append(" |\n");
        }

        out.append("\n**Concurrent search, 3 searching threads (thousand operations per second)**\n\n"
            + "| | Searches only | With occasional writes: searches | With occasional writes: writes |\n|---|---:|---:|---:|\n");
        for (Row row : CONCURRENT_SEARCHERS) {
            Optional<Measurement> withWrites = primary(run, "ConcurrentSearchBenchmark.searchWithWrites", row.subject(), size);
            out.append("| ").append(row.label())
                .append(" | ").append(cell(primary(run, "ConcurrentSearchBenchmark.searchOnly", row.subject(), size).map(Measurement::primary), BenchmarkReport::throughput))
                .append(" | ").append(cell(withWrites.map(m -> m.secondary().get("searches")), BenchmarkReport::throughput))
                .append(" | ").append(cell(withWrites.map(m -> m.secondary().get("writes")), BenchmarkReport::throughput))
                .append(" |\n");
        }

        out.append("\n**Locking across four types (million operations per second)**\n\n"
            + "| | Writes to separate types | `atomically` blocks | Same calls without a block | Mixed: reads | Mixed: writes |\n"
            + "|---|---:|---:|---:|---:|---:|\n");
        for (Row row : LOCKINGS) {
            out.append("| ").append(row.label());
            for (String benchmark : List.of("writesToSeparateTypes", "atomicBlocksAcrossTypes", "separateCallsAcrossTypes")) {
                out.append(" | ").append(cell(primary(run, "LockingBenchmark." + benchmark, row.subject(), size).map(Measurement::primary),
                    BenchmarkReport::throughput));
            }
            for (String metric : List.of("mixedRead", "mixedWrite")) {
                out.append(" | ").append(cell(primary(run, "LockingBenchmark.mixed", row.subject(), size)
                    .map(m -> m.secondary().get(metric)), BenchmarkReport::throughput));
            }
            out.append(" |\n");
        }

        out.append("\n**Search**\n\n| | Prefix | Exact | Compound | Range |\n|---|---:|---:|---:|---:|\n");
        for (Row row : SEARCHERS) {
            out.append("| ").append(row.label());
            for (String query : QUERIES) {
                out.append(" | ").append(cell(primary(run, "LibrarySearchBenchmark." + query, row.subject(), size), BenchmarkReport::time));
            }
            out.append(" |\n");
        }
        return out.toString();
    }

    static int summarySize(Run run) {
        return run.results().stream().mapToInt(Measurement::size).max().orElse(0);
    }

    static String forks(Run run) {
        if (run.minForks() == run.maxForks()) return run.maxForks() + (run.maxForks() == 1 ? " fork" : " forks");
        return run.minForks() + "–" + run.maxForks() + " forks";
    }

    static String time(Measurement measurement) {
        double score = measurement.primary().score();
        String unit = switch (measurement.unit()) {
            case "ns/op" -> " ns";
            case "us/op" -> " µs";
            case "ms/op" -> " ms";
            default -> " " + measurement.unit();
        };
        return number(score) + unit;
    }

    static String throughput(Estimate estimate) {
        return number(estimate.score());
    }

    static String memory(Double bytes) {
        return String.format(Locale.ROOT, "%,.0f B", bytes);
    }

    static String number(double value) {
        if (value < 1) return String.format(Locale.ROOT, "%.2f", value);
        if (value < 10) return String.format(Locale.ROOT, "%.1f", value);
        return String.format(Locale.ROOT, "%,.0f", value);
    }

    private static Optional<Measurement> primary(Run run, String benchmark, String subject, int size) {
        return run.results().stream()
            .filter(m -> m.benchmark().equals(benchmark) && m.subject().equals(subject) && m.size() == size)
            .findFirst();
    }

    private static Optional<Estimate> secondary(Run run, String metric, String subject) {
        return primary(run, "ConcurrentBenchmark.mixed", subject, summarySize(run)).map(m -> m.secondary().get(metric));
    }

    private static Optional<Double> bytes(Run run, String subject, int size) {
        return bytes(run.footprint(), subject, size);
    }

    private static Optional<Double> compactBytes(Run run, String subject, int size) {
        return bytes(run.compactFootprint(), subject, size);
    }

    private static Optional<Double> bytes(List<Footprint> footprint, String subject, int size) {
        return footprint.stream()
            .filter(f -> f.subject().equals(subject) && f.size() == size)
            .map(Footprint::bytesPerEntry)
            .findFirst();
    }

    private static <V> String cell(Optional<V> value, Function<V, String> format) {
        return value.map(format).orElse("–");
    }

    private static ObjectNode data(Run run) {
        ObjectNode root = JSON.createObjectNode()
            .put("mode", run.mode())
            .put("generatedAt", run.generatedAt())
            .put("javaVersion", run.javaVersion())
            .put("cpuCount", run.cpuCount())
            .put("forks", forks(run));
        ArrayNode results = root.putArray("results");
        for (Measurement measurement : run.results()) {
            ObjectNode node = results.addObject()
                .put("benchmark", measurement.benchmark())
                .put("subject", measurement.subject())
                .put("size", measurement.size())
                .put("unit", measurement.unit());
            put(node.putObject("primary"), measurement.primary());
            ObjectNode secondary = node.putObject("secondary");
            measurement.secondary().forEach((name, estimate) -> put(secondary.putObject(name), estimate));
        }
        putFootprint(root.putArray("footprint"), run.footprint());
        putFootprint(root.putArray("compactFootprint"), run.compactFootprint());
        return root;
    }

    private static void putFootprint(ArrayNode array, List<Footprint> footprint) {
        for (Footprint entry : footprint) {
            array.addObject().put("subject", entry.subject()).put("size", entry.size()).put("bytesPerEntry", entry.bytesPerEntry());
        }
    }

    private static void put(ObjectNode node, Estimate estimate) {
        node.put("score", estimate.score());
        if (estimate.low() < estimate.high()) {
            node.put("low", estimate.low());
            node.put("high", estimate.high());
        } else {
            node.putNull("low");
            node.putNull("high");
        }
    }

    static Estimate estimate(JsonNode metric) {
        List<Double> forkAverages = new ArrayList<>();
        for (JsonNode fork : metric.path("rawData")) {
            double sum = 0;
            int count = 0;
            for (JsonNode iteration : fork) {
                sum += iteration.asDouble();
                count++;
            }
            if (count > 0) forkAverages.add(sum / count);
        }
        if (forkAverages.isEmpty()) {
            double score = metric.path("score").asDouble();
            return new Estimate(score, score, score);
        }
        forkAverages.sort(null);
        int middle = forkAverages.size() / 2;
        double median = forkAverages.size() % 2 == 1 ? forkAverages.get(middle)
            : (forkAverages.get(middle - 1) + forkAverages.get(middle)) / 2;
        return new Estimate(median, forkAverages.getFirst(), forkAverages.getLast());
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String template() throws IOException {
        try (InputStream in = BenchmarkReport.class.getResourceAsStream("/benchmark-report.html")) {
            if (in == null) throw new IllegalStateException("benchmark-report.html is missing from the test resources");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
