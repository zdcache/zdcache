package io.github.zdcache.bench;

import io.github.zdcache.IdentityDimension;
import io.github.zdcache.Match;
import io.github.zdcache.Ranked;
import io.github.zdcache.SearchSpecification;
import io.github.zdcache.ZDCache;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class TextSearchBenchmark {
    record Student(long id, String name) {}

    private static final int QUERIES = 1024;

    @Param({"1000", "10000", "100000"})
    int size;

    private ZDCache cache;
    private ZDCache inside;
    private final String[] prefixes = new String[QUERIES];
    private final String[] words = new String[QUERIES];
    private final String[] wholeTypos = new String[QUERIES];
    private final String[] wordTypos = new String[QUERIES];
    private final String[] middles = new String[QUERIES];
    private final String[] shortMiddles = new String[QUERIES];
    private int next;

    @Setup(Level.Trial)
    public void setUp() {
        Random random = new Random(42);
        cache = ZDCache.builder().defaultMaxPerType(size).build();
        inside = ZDCache.builder().defaultMaxPerType(size).build();
        var spec = SearchSpecification.builder(IdentityDimension.of(Student::id)).prefixSplit(Student::name).build();
        var containing = SearchSpecification.builder(IdentityDimension.of(Student::id)).prefixContains(Student::name).build();
        String[] names = new String[size];
        for (int id = 0; id < size; id++) {
            names[id] = Names.person(random);
            if (id == 0) {
                cache.put(new Student(id, names[id]), spec);
                inside.put(new Student(id, names[id]), containing);
            } else {
                cache.put(new Student(id, names[id]));
                inside.put(new Student(id, names[id]));
            }
        }
        for (int q = 0; q < QUERIES; q++) {
            String name = names[random.nextInt(size)];
            String[] parts = name.split(" ");
            String last = parts[parts.length - 1];
            prefixes[q] = name.substring(0, 2);
            words[q] = parts[0] + " " + last.substring(0, Math.min(3, last.length()));
            wholeTypos[q] = Names.typo(name, random);
            wordTypos[q] = parts[0] + " " + Names.typo(last, random);
            int from = 1 + random.nextInt(Math.max(1, last.length() - 4));
            middles[q] = last.substring(from, Math.min(last.length(), from + 4));
            shortMiddles[q] = last.substring(from, Math.min(last.length(), from + 2));
        }
    }

    private int query() { return next++ & (QUERIES - 1); }

    @Benchmark public List<Student> prefixName() {
        return cache.search(Student.class, Student::name, prefixes[query()]);
    }

    @Benchmark public List<Student> wordsName() {
        return cache.search(Student.class, Student::name, Match.words(words[query()]));
    }

    @Benchmark public List<Ranked<Student>> wordsNameRanked() {
        return cache.searchRanked(Student::name, Match.words(words[query()]));
    }

    @Benchmark public List<Student> fuzzyWordsName() {
        return cache.search(Student.class, Student::name, Match.fuzzy(wordTypos[query()]));
    }

    @Benchmark public List<Student> fuzzyWordsNameOneEdit() {
        return cache.search(Student.class, Student::name, Match.fuzzy(wordTypos[query()], 1));
    }

    @Benchmark public List<Student> fuzzyWholeNameOneEdit() {
        return cache.search(Student.class, Student::name, Match.fuzzy(wholeTypos[query()], 1));
    }

    @Benchmark public List<Student> containsName() {
        return inside.search(Student.class, Student::name, Match.contains(middles[query()]));
    }

    @Benchmark public List<Student> containsNameTwoCharacters() {
        return inside.search(Student.class, Student::name, Match.contains(shortMiddles[query()]));
    }

    @Benchmark public List<Student> fuzzyWholeNameTwoEdits() {
        return cache.search(Student.class, Student::name, Match.fuzzy(wholeTypos[query()], 2));
    }
}
