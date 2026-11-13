package io.github.zdcache.bench;

import io.github.zdcache.IdentityDimension;
import io.github.zdcache.SearchSpecification;
import io.github.zdcache.ZDCache;
import java.util.Locale;
import java.util.Random;
import org.openjdk.jol.info.GraphLayout;

public final class TextFootprint {
    record Student(long id, String name) {}

    private TextFootprint() { }

    public static void main(String[] args) {
        var id = IdentityDimension.of(Student::id);
        var none = SearchSpecification.basic(id);
        var prefix = SearchSpecification.builder(id).prefix(Student::name).build();
        var split = SearchSpecification.builder(id).prefixSplit(Student::name).build();
        var contains = SearchSpecification.builder(id).prefixContains(Student::name).build();
        System.out.println("| Students | keys only | prefix | prefixSplit | prefixContains | prefix index | word index | trigram index |");
        System.out.println("|---:|---:|---:|---:|---:|---:|---:|---:|");
        for (int size : new int[] {10_000, 100_000}) {
            Student[] students = students(size);
            double keys = bytesPerEntry(students, none), whole = bytesPerEntry(students, prefix), words = bytesPerEntry(students, split);
            double inside = bytesPerEntry(students, contains);
            System.out.println(String.format(Locale.ROOT, "| %,d | %.1f | %.1f | %.1f | %.1f | %.1f | %.1f | %.1f |",
                size, keys, whole, words, inside, whole - keys, words - whole, inside - whole));
        }
    }

    private static Student[] students(int size) {
        Random random = new Random(42);
        Student[] students = new Student[size];
        for (int i = 0; i < size; i++) students[i] = new Student(i, Names.person(random));
        return students;
    }

    private static double bytesPerEntry(Student[] students, SearchSpecification<Student, Long> spec) {
        long own = GraphLayout.parseInstance((Object) students).totalSize();
        ZDCache cache = ZDCache.builder().defaultMaxPerType(students.length).build();
        cache.put(students[0], spec);
        for (int i = 1; i < students.length; i++) cache.put(students[i]);
        long total = GraphLayout.parseInstance(cache, (Object) students).totalSize();
        return (double) (total - own) / students.length;
    }
}
