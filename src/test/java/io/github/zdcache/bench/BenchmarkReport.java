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
    static final int SUMMARY_SIZE = 100_000;

    record Estimate(double score, double error) {}
    record Measurement(String benchmark, String subject, int size, String unit, Estimate primary, Map<String, Estimate> secondary) {}
    record Footprint(String subject, int size, double bytesPerEntry) {}
    record Run(String mode, String generatedAt, String javaVersion, int cpuCount, int forks,
               List<Measurement> results, List<Footprint> footprint) {}

    private record Row(String subject, String label) {}

    private static final List<Row> LIBRARIES = List.of(
        new Row(Subjects.ZDCACHE_BUFFERED_KEYS, "zdcache"),
        new Row(Subjects.ZDCACHE_BUFFERED_INDEXED, "zdcache, 4 indexes"),
        new Row(Subjects.ZDCACHE_APPROXIMATE_KEYS, "zdcache, approximate recency"),
        new Row(Subjects.ZDCACHE_APPROXIMATE_INDEXED, "zdcache, approximate, 4 indexes"),
        new Row(Subjects.CAFFEINE, "Caffeine"),
        new Row(Subjects.CACHE2K, "cache2k"),
        new Row(Subjects.GUAVA, "Guava"),
        new Row(Subjects.CQENGINE, "CQEngine, 4 indexes"));
    private static final List<Row> SEARCHERS = List.of(
        new Row(Subjects.ZDCACHE_BUFFERED_INDEXED, "zdcache, 4 indexes"),
        new Row(Subjects.CQENGINE, "CQEngine, 4 indexes"),
        new Row(Subjects.CAFFEINE, "Caffeine (full scan)"),
        new Row(Subjects.CACHE2K, "cache2k (full scan)"),
        new Row(Subjects.GUAVA, "Guava (full scan)"));

    private static final ObjectMapper JSON = new ObjectMapper();

    private BenchmarkReport() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 4) {
            throw new IllegalArgumentException("Usage: BenchmarkReport <jmh results.json> <footprint results.json> <mode> <output dir>");
        }
        Path jmhResults = Path.of(args[0]);
        Path footprintResults = Path.of(args[1]);
        Path output = Path.of(args[3]);
        if (!Files.exists(jmhResults)) throw new IllegalStateException("No JMH results at " + jmhResults + "; run the benchmarks first");
        List<Footprint> footprint = Files.exists(footprintResults) ? readFootprint(Files.readString(footprintResults)) : List.of();
        String generatedAt = Files.getLastModifiedTime(jmhResults).toInstant().truncatedTo(ChronoUnit.SECONDS).toString();
        Run run = read(Files.readString(jmhResults), footprint, args[2], generatedAt, Runtime.getRuntime().availableProcessors());

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
        int forks = 0;
        for (JsonNode result : results) {
            JsonNode params = result.path("params");
            JsonNode primary = result.path("primaryMetric");
            Map<String, Estimate> secondary = new LinkedHashMap<>();
            result.path("secondaryMetrics").properties().forEach(entry -> secondary.put(entry.getKey(), estimate(entry.getValue())));
            String benchmark = result.path("benchmark").asText();
            measurements.add(new Measurement(benchmark.substring(benchmark.lastIndexOf('.', benchmark.lastIndexOf('.') - 1) + 1),
                params.path("subject").asText(), params.path("size").asInt(), primary.path("scoreUnit").asText(),
                estimate(primary), secondary));
            javaVersion = result.path("jdkVersion").asText(javaVersion);
            forks = Math.max(forks, result.path("forks").asInt());
        }
        return new Run(mode, generatedAt, javaVersion, cpuCount, forks, List.copyOf(measurements), List.copyOf(footprint));
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
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "Measured %s on Java %s with %d CPUs, %d %s per benchmark, at %,d entries. "
                + "Lower is better for time and memory; higher is better for operations per second.%n%n",
            run.generatedAt(), run.javaVersion(), run.cpuCount(), run.forks(), run.forks() == 1 ? "fork" : "forks", SUMMARY_SIZE));

        out.append("**Single thread and memory**\n\n| | get | put | Memory per entry |\n|---|---:|---:|---:|\n");
        for (Row row : LIBRARIES) {
            out.append("| ").append(row.label())
                .append(" | ").append(cell(primary(run, "KeyLookupBenchmark.get", row.subject(), SUMMARY_SIZE), BenchmarkReport::time))
                .append(" | ").append(cell(primary(run, "KeyLookupBenchmark.put", row.subject(), SUMMARY_SIZE), BenchmarkReport::time))
                .append(" | ").append(cell(bytes(run, row.subject(), SUMMARY_SIZE), BenchmarkReport::memory))
                .append(" |\n");
        }

        out.append("\n**Concurrent, 8 threads (million operations per second)**\n\n"
            + "| | Reads only | Mixed: reads | Mixed: writes |\n|---|---:|---:|---:|\n");
        for (Row row : LIBRARIES) {
            out.append("| ").append(row.label())
                .append(" | ").append(cell(primary(run, "ConcurrentBenchmark.readOnly", row.subject(), SUMMARY_SIZE).map(Measurement::primary), BenchmarkReport::throughput))
                .append(" | ").append(cell(secondary(run, "mixedRead", row.subject()), BenchmarkReport::throughput))
                .append(" | ").append(cell(secondary(run, "mixedWrite", row.subject()), BenchmarkReport::throughput))
                .append(" |\n");
        }

        out.append("\n**Search**\n\n| | Prefix | Exact | Compound | Range |\n|---|---:|---:|---:|---:|\n");
        for (Row row : SEARCHERS) {
            out.append("| ").append(row.label());
            for (String query : List.of("prefixName", "exactCity", "compoundCityAge", "rangeAge")) {
                out.append(" | ").append(cell(primary(run, "SearchBenchmark." + query, row.subject(), SUMMARY_SIZE), BenchmarkReport::time));
            }
            out.append(" |\n");
        }
        return out.toString();
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
        return primary(run, "ConcurrentBenchmark.mixed", subject, SUMMARY_SIZE).map(m -> m.secondary().get(metric));
    }

    private static Optional<Double> bytes(Run run, String subject, int size) {
        return run.footprint().stream()
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
            .put("forks", run.forks());
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
        ArrayNode footprint = root.putArray("footprint");
        for (Footprint entry : run.footprint()) {
            footprint.addObject().put("subject", entry.subject()).put("size", entry.size()).put("bytesPerEntry", entry.bytesPerEntry());
        }
        return root;
    }

    private static void put(ObjectNode node, Estimate estimate) {
        node.put("score", estimate.score());
        if (Double.isFinite(estimate.error())) node.put("error", estimate.error());
        else node.putNull("error");
    }

    private static Estimate estimate(JsonNode metric) {
        return new Estimate(metric.path("score").asDouble(), metric.path("scoreError").asDouble(Double.NaN));
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
