package io.github.zdcache.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import org.openjdk.jol.info.GraphLayout;

public final class MemoryFootprint {
    private static List<Integer> SIZES = List.of(10_000, 100_000);
    private static final List<String> KEY_ONLY =
        List.of(Subjects.CAFFEINE, Subjects.ZDCACHE_LHM_KEYS, Subjects.ZDCACHE_CAFFEINE_KEYS, Subjects.ZDCACHE_BUFFERED_KEYS,
            Subjects.GUAVA, Subjects.CACHE2K);
    private static final List<String> INDEXED =
        List.of(Subjects.ZDCACHE_LHM_INDEXED, Subjects.ZDCACHE_CAFFEINE_INDEXED, Subjects.ZDCACHE_BUFFERED_INDEXED,
            Subjects.CQENGINE);

    public static void main(String[] args) throws IOException {
        if (args.length > 1) SIZES = BenchmarkPlan.read(Path.of(args[1])).footprintSizes();
        Map<String, Map<Integer, Double>> measured = new LinkedHashMap<>();
        for (String subject : KEY_ONLY) measured.put(subject, measure(subject));
        for (String subject : INDEXED) measured.put(subject, measure(subject));
        List<String> lines = new ArrayList<>() {
            @Override public boolean add(String line) {
                System.out.println(line);
                return super.add(line);
            }
        };
        lines.add("Bytes per entry, excluding the cached Person objects themselves.");
        lines.add("");
        lines.add(header());
        lines.add(separator());
        for (String subject : KEY_ONLY) lines.add(row(subject + " (keys only)", measured.get(subject)));
        lines.add("");
        lines.add("With indexes on name (prefix), city (exact), age (range) and city+age (compound); CQEngine uses radix-tree, hash and navigable indexes.");
        lines.add("");
        lines.add(header());
        lines.add(separator());
        for (String subject : INDEXED) lines.add(row(subject, measured.get(subject)));
        Path report = Path.of(args.length > 0 ? args[0] : "build/reports/footprint/results.md");
        Files.createDirectories(report.getParent());
        Files.write(report, lines);
        Path json = report.resolveSibling("results.json");
        Files.writeString(json, json(measured));
        System.out.println();
        System.out.println("Written to " + report.toAbsolutePath() + " and " + json.toAbsolutePath());
    }

    private static String header() {
        StringBuilder header = new StringBuilder("| Subject |");
        for (int size : SIZES) header.append(String.format(Locale.ROOT, " %,d |", size));
        return header.toString();
    }

    private static String separator() {
        return "|---|" + "---:|".repeat(SIZES.size());
    }

    private static Map<Integer, Double> measure(String subject) {
        Map<Integer, Double> bySize = new LinkedHashMap<>();
        for (int size : SIZES) bySize.put(size, bytesPerEntry(subject, size));
        return bySize;
    }

    private static String row(String label, Map<Integer, Double> bySize) {
        StringBuilder row = new StringBuilder("| " + label + " |");
        for (int size : SIZES) row.append(String.format(Locale.ROOT, " %.1f |", bySize.get(size)));
        return row.toString();
    }

    private static String json(Map<String, Map<Integer, Double>> measured) {
        List<String> entries = new ArrayList<>();
        measured.forEach((subject, bySize) -> bySize.forEach((size, bytes) -> entries.add(String.format(Locale.ROOT,
            "  {\"subject\": \"%s\", \"size\": %d, \"bytesPerEntry\": %.1f}", subject, size, bytes))));
        return "[\n" + String.join(",\n", entries) + "\n]\n";
    }

    static double bytesPerEntry(String subject, int size) {
        Person[] people = Dataset.people(size, 42);
        long peopleBytes = GraphLayout.parseInstance((Object) people).totalSize();
        long empty = GraphLayout.parseInstance(Subjects.create(subject, size, true).root()).totalSize();
        CacheSubject filled = Subjects.create(subject, size, true);
        for (Person person : people) filled.put(person);
        long total = GraphLayout.parseInstance(filled.root(), (Object) people).totalSize();
        return (double) (total - peopleBytes - empty) / size;
    }
}
