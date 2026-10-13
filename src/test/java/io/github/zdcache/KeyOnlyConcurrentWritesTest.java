package io.github.zdcache;

import io.github.zdcache.store.IdentityStoreFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KeyOnlyConcurrentWritesTest {
    record Item(long id, String name) {}

    static final IdentityDimension<Item, Long> ITEM_ID = IdentityDimension.of(Item.class, "id", Item::id);
    static final int THREADS = 8;
    static final int PER_THREAD = 2_000;

    private static List<ZDCache.Builder> concurrentStores() {
        return List.of(
            ZDCache.builder().identityStore(IdentityStoreFactory.bufferedLru()),
            ZDCache.builder().identityStore(IdentityStoreFactory.bufferedLru()).recency(Recency.APPROXIMATE),
            ZDCache.builder().identityStore(IdentityStoreFactory.caffeine()),
            ZDCache.builder().identityStore(IdentityStoreFactory.linkedHashMap()));
    }

    private static void inParallel(int threads, Worker worker) throws Exception {
        var start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                int thread = t;
                tasks.add(pool.submit(() -> {
                    start.await();
                    worker.run(thread);
                    return null;
                }));
            }
            start.countDown();
            for (var task : tasks) task.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    private interface Worker { void run(int thread) throws Exception; }

    @Test void concurrentKeyOnlyPutsAreAllStored() throws Exception {
        for (var builder : concurrentStores()) {
            var cache = builder.defaultMaxPerType(THREADS * PER_THREAD).build();
            cache.register(SearchSpecification.basic(ITEM_ID));

            inParallel(THREADS, thread -> {
                for (int i = 0; i < PER_THREAD; i++) cache.put(new Item(thread * PER_THREAD + i, "item"));
            });

            assertEquals(THREADS * PER_THREAD, cache.size(Item.class));
            for (long id = 0; id < THREADS * PER_THREAD; id++) assertEquals(id, cache.get(Item.class, id).id());
        }
    }

    @Test void addingIndexesDuringConcurrentKeyOnlyPutsIndexesEveryEntity() throws Exception {
        for (var builder : concurrentStores()) {
            var cache = builder.defaultMaxPerType(100_000).build();
            cache.register(SearchSpecification.basic(ITEM_ID));
            var indexed = SearchSpecification.builder(ITEM_ID).exact(Item::name).build();
            var writing = new AtomicBoolean(true);

            inParallel(THREADS + 1, thread -> {
                if (thread == THREADS) {
                    Thread.sleep(5);
                    cache.updateSpecification(Item.class, indexed);
                    Thread.sleep(5);
                    writing.set(false);
                    return;
                }
                for (int i = 0; writing.get() || i < 500; i++) {
                    long id = (long) thread * 1_000_000 + i % 5_000;
                    cache.put(new Item(id, "n" + (id % 7)));
                }
            });

            int found = 0;
            for (int bucket = 0; bucket < 7; bucket++) found += cache.search(Item::name, "n" + bucket).size();
            assertEquals(cache.size(Item.class), found);
            for (Item item : cache.values(Item.class)) {
                assertTrue(cache.search(Item::name, item.name()).contains(item), item::toString);
            }
        }
    }
}
