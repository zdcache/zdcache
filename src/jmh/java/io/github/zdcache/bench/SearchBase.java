package io.github.zdcache.bench;

import java.util.List;
import org.openjdk.jmh.annotations.*;

@State(Scope.Benchmark)
public abstract class SearchBase {
    @Param({"1000", "3000", "5000", "10000", "50000"})
    int size;

    private CacheSubject cache;
    private Queries queries;
    private int next;

    abstract String subject();

    @Setup(Level.Trial)
    public void setUp() {
        Person[] people = Dataset.people(size, 42);
        queries = new Queries(people, 7);
        cache = Subjects.create(subject(), size);
        for (Person person : people) cache.put(person);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        cache.close();
    }

    private int query() {
        return next++ & (Queries.COUNT - 1);
    }

    @Benchmark
    public List<Person> exactCity() {
        return cache.byCity(queries.cities[query()]);
    }

    @Benchmark
    public List<Person> prefixName() {
        return cache.byNamePrefix(queries.namePrefixes[query()]);
    }

    @Benchmark
    public List<Person> rangeAge() {
        int q = query();
        return cache.byAge(queries.ageFrom[q], queries.ageTo[q]);
    }

    @Benchmark
    public List<Person> compoundCityAge() {
        int q = query();
        return cache.byCityAndAge(queries.cities[q], queries.ageFrom[q], queries.ageTo[q]);
    }
}
