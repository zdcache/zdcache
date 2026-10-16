package io.github.zdcache;

import io.github.zdcache.store.IdentityStoreFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConcurrencyTest {
    record Person(long id, String name, String city) {}

    private static final int CAPACITY = 200;
    private static final int IDS = 1_000;
    private static final int LOOPS = 3_000;

    private static final IdentityDimension<Person, Long> ID = IdentityDimension.of(Person.class, "id", Person::id);

    private static SearchSpecification<Person, Long> spec() {
        return SearchSpecification.builder(ID)
            .prefix("name")
            .exact("city")
            .compound("cityName", c -> c.exact("city").prefix("name"))
            .build();
    }

    private static Person randomPerson(ThreadLocalRandom random) {
        char[] name = {(char) ('a' + random.nextInt(26)), (char) ('a' + random.nextInt(26)), (char) ('a' + random.nextInt(26))};
        return new Person(random.nextInt(IDS), new String(name), "city-" + random.nextInt(5));
    }

    @Test void linkedHashMapStoreStaysConsistentUnderConcurrentUse() throws Exception {
        hammer(ZDCache.builder().identityStore(IdentityStoreFactory.linkedHashMap()));
    }

    @Test void caffeineStoreStaysConsistentUnderConcurrentUseAndEviction() throws Exception {
        hammer(ZDCache.builder().identityStore(IdentityStoreFactory.caffeine()));
    }

    @Test void bufferedLruStoreStaysConsistentUnderConcurrentUseAndEviction() throws Exception {
        hammer(ZDCache.builder().identityStore(IdentityStoreFactory.bufferedLru()));
    }

    @Test void approximateRecencyStaysConsistentUnderConcurrentUseAndEviction() throws Exception {
        hammer(ZDCache.builder().identityStore(IdentityStoreFactory.bufferedLru()).recency(Recency.APPROXIMATE));
    }

    @Test void entriesPerIdStayConsistentUnderConcurrentUseAndEviction() throws Exception {
        hammer(ZDCache.builder().identityStore(IdentityStoreFactory.bufferedLru()).entries(Entries.PER_ID));
        hammer(ZDCache.builder().identityStore(IdentityStoreFactory.bufferedLru()).recency(Recency.APPROXIMATE).entries(Entries.PER_ID));
        hammer(ZDCache.builder().identityStore(IdentityStoreFactory.caffeine()).entries(Entries.PER_ID));
        hammer(ZDCache.builder().identityStore(IdentityStoreFactory.linkedHashMap()).entries(Entries.PER_ID));
    }

    @Test void nonBlockingSearchesStayConsistentUnderConcurrentUseAndEviction() throws Exception {
        for (Entries entries : Entries.values()) {
            hammer(ZDCache.builder().identityStore(IdentityStoreFactory.bufferedLru()).entries(entries).searches(Searches.NON_BLOCKING));
            hammer(ZDCache.builder().identityStore(IdentityStoreFactory.bufferedLru()).recency(Recency.APPROXIMATE)
                .entries(entries).searches(Searches.NON_BLOCKING));
            hammer(ZDCache.builder().identityStore(IdentityStoreFactory.caffeine()).entries(entries).searches(Searches.NON_BLOCKING));
            hammer(ZDCache.builder().identityStore(IdentityStoreFactory.linkedHashMap()).entries(entries).searches(Searches.NON_BLOCKING));
        }
    }

    private static void hammer(ZDCache.Builder builder) throws Exception {
        var cache = builder.defaultMaxPerType(CAPACITY).build();
        cache.register(spec());
        var problem = new AtomicReference<Throwable>();
        var start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(9);
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int t = 0; t < 3; t++) tasks.add(pool.submit(worker(start, problem, random -> cache.put(randomPerson(random)))));
            tasks.add(pool.submit(worker(start, problem, random -> cache.remove(Person.class, (long) random.nextInt(IDS)))));
            for (int t = 0; t < 3; t++) {
                tasks.add(pool.submit(worker(start, problem, random -> {
                    long id = random.nextInt(IDS);
                    Person found = cache.get(Person.class, id);
                    if (found != null && found.id() != id) throw new AssertionError("get returned another id");
                })));
            }
            for (int t = 0; t < 2; t++) {
                tasks.add(pool.submit(worker(start, problem, random -> {
                    String prefix = String.valueOf((char) ('a' + random.nextInt(26)));
                    String city = "city-" + random.nextInt(5);
                    for (Person p : cache.search(Person.class, "name", prefix)) {
                        if (!p.name().startsWith(prefix)) throw new AssertionError("stale name index: " + p);
                    }
                    for (Person p : cache.search(Person.class, "cityName", city, prefix)) {
                        if (!p.city().equals(city) || !p.name().startsWith(prefix)) {
                            throw new AssertionError("stale compound index: " + p);
                        }
                    }
                })));
            }
            start.countDown();
            for (var task : tasks) task.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        if (problem.get() != null) throw new AssertionError(problem.get());

        int size = cache.size(Person.class);
        assertTrue(size <= CAPACITY, "size " + size);
        List<Person> everyone = cache.search(Person.class, "name", "");
        assertEquals(size, everyone.size());
        for (Person p : everyone) {
            assertSame(p, cache.get(Person.class, p.id()));
            assertTrue(cache.search(Person.class, "city", p.city()).contains(p));
            assertTrue(cache.search(Person.class, "cityName", p.city(), p.name()).contains(p));
        }
        for (String city : List.of("city-0", "city-1", "city-2", "city-3", "city-4")) {
            for (Person p : cache.search(Person.class, "city", city)) assertSame(p, cache.get(Person.class, p.id()));
        }
    }

    private interface Step { void run(ThreadLocalRandom random); }

    private static Runnable worker(CountDownLatch start, AtomicReference<Throwable> problem, Step step) {
        return () -> {
            try {
                start.await();
                ThreadLocalRandom random = ThreadLocalRandom.current();
                for (int i = 0; i < LOOPS && problem.get() == null; i++) step.run(random);
            } catch (Throwable error) {
                problem.compareAndSet(null, error);
            }
        };
    }
}
