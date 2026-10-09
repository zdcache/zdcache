package io.github.zdcache.benchmark;

import io.github.zdcache.IdentityDimension;
import io.github.zdcache.SearchSpecification;
import io.github.zdcache.ZDCache;
import io.github.zdcache.store.IdentityStoreFactory;
import java.util.concurrent.TimeUnit;

/**
 * Small executable smoke comparison, NOT a scientifically rigorous benchmark.
 * Use JMH for reliable performance conclusions, especially under concurrency.
 */
public final class StoreComparison {
    record Person(long id, String name) {}
    private static volatile long blackhole;

    public static void main(String[] args) {
        compare("LinkedHashMap", IdentityStoreFactory.linkedHashMap());
        compare("Caffeine", IdentityStoreFactory.caffeine());
        System.out.println("NOTE: results are smoke timings, not JMH measurements.");
    }

    private static void compare(String label, IdentityStoreFactory factory) {
        var identity = IdentityDimension.of(Person.class, "id", Person::id);
        var spec = SearchSpecification.builder(identity)
            .prefix("name", Person::name).build();
        var cache = ZDCache.builder()
            .capacity(Person.class, 1_000)
            .identityStore(factory)
            .build();

        for (int i = 0; i < 1_000; i++) {
            cache.put(new Person(i, "person-" + i), spec);
        }
        long checksum = 0;
        for (int i = 0; i < 20_000; i++) {
            checksum += cache.search(Person.class, "name", "person-1").size();
            cache.get(Person.class, (long) (i % 1_000));
        }

        long started = System.nanoTime();
        for (int i = 0; i < 50_000; i++) {
            checksum += cache.search(Person.class, "name", "person-1").size();
            var value = cache.get(Person.class, (long) (i % 1_000));
            if (value != null) checksum += value.id();
        }
        long elapsed = System.nanoTime() - started;
        blackhole = checksum;
        System.out.printf("%-14s %,d ns/iteration; checksum=%d%n",
            label, elapsed / 50_000, blackhole);
    }
}
