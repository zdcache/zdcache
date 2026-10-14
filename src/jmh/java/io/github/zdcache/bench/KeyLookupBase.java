package io.github.zdcache.bench;

import org.openjdk.jmh.annotations.*;

@State(Scope.Benchmark)
public abstract class KeyLookupBase {
    private static final int ORDER_MASK = (1 << 16) - 1;

    @Param({"1000", "3000", "5000", "10000", "50000"})
    int size;

    CacheSubject cache;
    private Person[][] versions;
    private Person[][] retagged;
    private int[] order;
    private int next;

    abstract String subject();

    @Setup(Level.Trial)
    public void setUp() {
        Person[] people = Dataset.people(size, 42);
        versions = new Person[][] {people, Dataset.changed(people, 43)};
        retagged = new Person[][] {Dataset.retagged(people, 45), Dataset.retagged(people, 46)};
        order = Dataset.accessOrder(size, ORDER_MASK + 1, 44);
        cache = Subjects.create(subject(), size);
        for (Person person : people) cache.put(person);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        cache.close();
    }

    Person nextVersion() {
        int i = next++;
        return versions[(i >>> 16) & 1][order[i & ORDER_MASK]];
    }

    Person nextRetagged() {
        int i = next++;
        return retagged[(i >>> 16) & 1][order[i & ORDER_MASK]];
    }

    @Benchmark
    public Person get() {
        int i = next++;
        return cache.get(order[i & ORDER_MASK]);
    }

    @Benchmark
    public void put() {
        cache.put(nextVersion());
    }
}
