package io.github.zdcache.bench;

import io.github.zdcache.bench.text.Student;
import io.github.zdcache.bench.text.TextSubject;
import java.util.List;
import java.util.Locale;
import org.openjdk.jol.info.GraphLayout;

public final class TextComparisonFootprint {
    static final List<String> SUBJECTS = List.of("zdcache-text", "lucene-text");

    private TextComparisonFootprint() { }

    public static void main(String[] args) {
        System.out.println("| Students | zdcache | Lucene |");
        System.out.println("|---:|---:|---:|");
        for (int size : new int[] {10_000, 50_000, 100_000})
            System.out.println(String.format(Locale.ROOT, "| %,d | %.1f | %.1f |", size, bytesPerEntry(SUBJECTS.get(0), size), bytesPerEntry(SUBJECTS.get(1), size)));
    }

    static double bytesPerEntry(String subject, int size) {
        List<Student> students = TextComparisonBenchmark.students(size);
        long own = GraphLayout.parseInstance(students.toArray()).totalSize();
        try (TextSubject engine = TextSubject.create(TextComparisonBenchmark.engineOf(subject))) {
            engine.load(students);
            long total = GraphLayout.parseInstance(engine.root(), students.toArray()).totalSize();
            return (double) (total - own) / size;
        }
    }
}
