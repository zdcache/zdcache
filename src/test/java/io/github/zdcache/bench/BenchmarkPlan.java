package io.github.zdcache.bench;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public final class BenchmarkPlan {
    public static final List<String> BENCHMARKS =
        List.of("KeyLookupBenchmark", "SearchBenchmark", "ConcurrentBenchmark", "LockingBenchmark");
    private static final String LOCKING = "LockingBenchmark";
    private static final String EVERY = "*";
    private static final String FOOTPRINT = "MemoryFootprint";
    private static final List<String> DEFAULT_FOOTPRINT_SIZES = List.of("10000", "100000");
    static final int MAX_SIZE = 1_000_000;
    private static final Set<String> REQUIRED = Set.of(
        "forks", "warmupIterations", "warmupTime", "measurementIterations", "measurementTime");
    private static final Set<String> NARROWING = Set.of("sizes", "subjects");
    private static final int MAX_FORKS = 10;
    private static final int MAX_ITERATIONS = 50;
    private static final Duration MIN_TIME = Duration.ofMillis(100);
    private static final Duration MAX_TIME = Duration.ofSeconds(10);

    public record Settings(int forks, int warmupIterations, Duration warmupTime, int measurementIterations,
                           Duration measurementTime, List<String> sizes, List<String> subjects) {}

    private final Map<String, Settings> settings;
    private final List<Integer> footprintSizes;

    private BenchmarkPlan(Map<String, Settings> settings, List<Integer> footprintSizes) {
        this.settings = Map.copyOf(settings);
        this.footprintSizes = List.copyOf(footprintSizes);
    }

    public static BenchmarkPlan read(Path file) {
        try {
            return parse(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static BenchmarkPlan parse(String text) {
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(text));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Map<String, String>> byScope = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) {
            int dot = key.indexOf('.');
            if (dot < 1 || dot == key.length() - 1) {
                throw new IllegalArgumentException("Expected <benchmark or *>.<option> but got '" + key + "'");
            }
            String scope = key.substring(0, dot);
            String option = key.substring(dot + 1);
            if (scope.equals(FOOTPRINT)) {
                if (!option.equals("sizes")) throw new IllegalArgumentException("MemoryFootprint only takes sizes, not '" + key + "'");
            } else if (!scope.equals(EVERY) && !BENCHMARKS.contains(scope)) {
                throw new IllegalArgumentException("Unknown benchmark '" + scope + "' in '" + key + "'; choose *, "
                    + FOOTPRINT + " or one of " + BENCHMARKS);
            }
            if (!REQUIRED.contains(option) && !NARROWING.contains(option)) {
                throw new IllegalArgumentException("Unknown option '" + option + "' in '" + key + "'");
            }
            if (scope.equals(LOCKING) && option.equals("subjects")) {
                throw new IllegalArgumentException("'" + key + "' is not allowed: LockingBenchmark compares locking strategies, not subjects");
            }
            if (scope.equals(EVERY) && NARROWING.contains(option)) {
                throw new IllegalArgumentException("'" + key + "' must name a benchmark, because sizes and subjects differ per benchmark");
            }
            byScope.computeIfAbsent(scope, ignored -> new LinkedHashMap<>()).put(option, properties.getProperty(key).trim());
        }
        Map<String, String> every = byScope.getOrDefault(EVERY, Map.of());
        for (String option : REQUIRED) {
            if (!every.containsKey(option)) {
                throw new IllegalArgumentException("The plan must set *." + option);
            }
        }
        Map<String, Settings> settings = new LinkedHashMap<>();
        for (String benchmark : BENCHMARKS) {
            Map<String, String> merged = new LinkedHashMap<>(every);
            merged.putAll(byScope.getOrDefault(benchmark, Map.of()));
            settings.put(benchmark, settings(benchmark, merged));
        }
        Map<String, String> footprint = byScope.getOrDefault(FOOTPRINT, Map.of());
        List<String> footprintSizes = footprint.containsKey("sizes")
            ? list(FOOTPRINT, "sizes", footprint, true) : DEFAULT_FOOTPRINT_SIZES;
        return new BenchmarkPlan(settings, footprintSizes.stream().map(Integer::valueOf).toList());
    }

    public List<Integer> footprintSizes() {
        return footprintSizes;
    }

    public Settings settings(String benchmark) {
        Settings found = settings.get(benchmark);
        if (found == null) throw new IllegalArgumentException("Unknown benchmark " + benchmark);
        return found;
    }

    private static Settings settings(String benchmark, Map<String, String> options) {
        return new Settings(
            number(benchmark, "forks", options, 1, MAX_FORKS),
            number(benchmark, "warmupIterations", options, 0, MAX_ITERATIONS),
            time(benchmark, "warmupTime", options),
            number(benchmark, "measurementIterations", options, 1, MAX_ITERATIONS),
            time(benchmark, "measurementTime", options),
            list(benchmark, "sizes", options, true),
            list(benchmark, "subjects", options, false));
    }

    private static int number(String benchmark, String option, Map<String, String> options, int min, int max) {
        String value = options.get(option);
        try {
            int number = Integer.parseInt(value);
            if (number >= min && number <= max) return number;
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException(benchmark + "." + option + " must be a number from " + min + " to " + max + ", not '" + value + "'");
    }

    private static Duration time(String benchmark, String option, Map<String, String> options) {
        String value = options.get(option);
        Duration duration = null;
        try {
            if (value.endsWith("ms")) duration = Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2)));
            else if (value.endsWith("s")) duration = Duration.ofSeconds(Long.parseLong(value.substring(0, value.length() - 1)));
        } catch (NumberFormatException ignored) {
        }
        if (duration == null || duration.compareTo(MIN_TIME) < 0 || duration.compareTo(MAX_TIME) > 0) {
            throw new IllegalArgumentException(benchmark + "." + option + " must be a time from 100ms to 10s such as 500ms or 1s, not '" + value + "'");
        }
        return duration;
    }

    private static List<String> list(String benchmark, String option, Map<String, String> options, boolean numbers) {
        String value = options.get(option);
        if (value == null) return List.of();
        List<String> items = Arrays.stream(value.split(",")).map(String::trim).toList();
        for (String item : items) {
            boolean valid = numbers ? item.matches("[1-9][0-9]{0,6}") && Integer.parseInt(item) <= MAX_SIZE
                : Subjects.ALL.contains(item);
            if (!valid) {
                throw new IllegalArgumentException(benchmark + "." + option + " has an invalid entry '" + item + "'"
                    + (numbers ? "; sizes go from 1 to " + MAX_SIZE : "; choose from " + Subjects.ALL));
            }
        }
        return items;
    }
}
