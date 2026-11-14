package io.github.zdcache.bench;

import io.github.zdcache.IdentityDimension;
import io.github.zdcache.Match;
import io.github.zdcache.SearchSpecification;
import io.github.zdcache.ZDCache;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.openjdk.jmh.annotations.*;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class TextSearchBenchmark {
    record Student(long id, String name) {}

    static final String PREFIX = "text-prefix";
    static final String WORDS = "text-words";
    static final String WORDS_RANKED = "text-words-ranked";
    static final String FUZZY_WORDS_ONE = "text-fuzzy-words-one-edit";
    static final String FUZZY_WORDS = "text-fuzzy-words";
    static final String FUZZY_WHOLE_ONE = "text-fuzzy-whole-one-edit";
    static final String FUZZY_WHOLE_TWO = "text-fuzzy-whole-two-edits";
    static final String CONTAINS = "text-contains";
    static final String CONTAINS_TWO = "text-contains-two-characters";

    private static final int QUERIES = 1024;

    @Param({"1000", "3000", "5000", "10000", "50000"})
    int size;

    @Param({PREFIX, WORDS, WORDS_RANKED, FUZZY_WORDS_ONE, FUZZY_WORDS, FUZZY_WHOLE_ONE, FUZZY_WHOLE_TWO, CONTAINS, CONTAINS_TWO})
    String subject;

    private Supplier<List<?>> search;
    private int next;

    @Setup(Level.Trial)
    public void setUp() {
        Random random = new Random(42);
        var id = IdentityDimension.of(Student::id);
        var spec = switch (subject) {
            case FUZZY_WHOLE_ONE, FUZZY_WHOLE_TWO -> SearchSpecification.builder(id).prefix(Student::name).build();
            case CONTAINS, CONTAINS_TWO -> SearchSpecification.builder(id).prefixContains(Student::name).build();
            default -> SearchSpecification.builder(id).prefixSplit(Student::name).build();
        };
        ZDCache cache = ZDCache.builder().defaultMaxPerType(size).build();
        String[] names = new String[size];
        for (int i = 0; i < size; i++) {
            names[i] = Names.person(random);
            if (i == 0) cache.put(new Student(i, names[i]), spec);
            else cache.put(new Student(i, names[i]));
        }
        Object[] queries = new Object[QUERIES];
        for (int q = 0; q < QUERIES; q++) {
            String name = names[random.nextInt(size)];
            String[] parts = name.split(" ");
            String last = parts[parts.length - 1];
            int from = 1 + random.nextInt(Math.max(1, last.length() - 4));
            queries[q] = switch (subject) {
                case PREFIX -> name.substring(0, 2);
                case WORDS, WORDS_RANKED -> Match.words(parts[0] + " " + last.substring(0, Math.min(3, last.length())));
                case FUZZY_WORDS_ONE -> Match.fuzzy(parts[0] + " " + Names.typo(last, random), 1);
                case FUZZY_WORDS -> Match.fuzzy(parts[0] + " " + Names.typo(last, random));
                case FUZZY_WHOLE_ONE -> Match.fuzzy(Names.typo(name, random), 1);
                case FUZZY_WHOLE_TWO -> Match.fuzzy(Names.typo(name, random), 2);
                case CONTAINS -> Match.contains(last.substring(from, Math.min(last.length(), from + 4)));
                case CONTAINS_TWO -> Match.contains(last.substring(from, Math.min(last.length(), from + 2)));
                default -> throw new IllegalArgumentException("Unknown text search " + subject);
            };
        }
        search = subject.equals(WORDS_RANKED)
            ? () -> cache.searchRanked(Student::name, queries[query()])
            : () -> cache.search(Student.class, Student::name, queries[query()]);
    }

    private int query() { return next++ & (QUERIES - 1); }

    @Benchmark public List<?> search() { return search.get(); }
}
