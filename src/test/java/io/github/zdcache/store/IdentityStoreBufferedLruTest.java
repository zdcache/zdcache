package io.github.zdcache.store;

import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdentityStoreBufferedLruTest {

    private static IndexedEntry<String> entry(String value) {
        return new IndexedEntry<>(value, new Object[0]);
    }

    private static IdentityStoreBufferedLru<Integer, String> filled(int maximumSize, int... ids) {
        var store = new IdentityStoreBufferedLru<Integer, String>(maximumSize);
        for (int id : ids) store.put(id, entry("v" + id));
        return store;
    }

    private static List<Integer> evicted(IdentityStoreBufferedLru<Integer, String> store) {
        return store.drainEvictions().stream().map(EvictedEntry::id).toList();
    }

    private static List<Integer> lruOrder(IdentityStoreBufferedLru<Integer, String> store) {
        return List.copyOf(store.snapshot().keySet());
    }

    @Test void evictsLeastRecentlyInsertedWhenNothingIsRead() {
        var store = filled(2, 1, 2);
        store.put(3, entry("v3"));
        assertEquals(List.of(1), evicted(store));
    }

    @Test void getCountsAsUse() {
        var store = filled(2, 1, 2);
        assertEquals("v1", store.get(1).entity());
        store.put(3, entry("v3"));
        assertEquals(List.of(2), evicted(store));
    }

    @Test void peekDoesNotCountAsUse() {
        var store = filled(2, 1, 2);
        assertEquals("v1", store.peek(1).entity());
        store.put(3, entry("v3"));
        assertEquals(List.of(1), evicted(store));
    }

    @Test void replacingAnEntryCountsAsUse() {
        var store = filled(2, 1, 2);
        assertEquals("v1", store.put(1, entry("new")).entity());
        store.put(3, entry("v3"));
        assertEquals(List.of(2), evicted(store));
        assertEquals("new", store.peek(1).entity());
    }

    @Test void missingIdsReturnNullWithoutSideEffects() {
        var store = filled(2, 1, 2);
        assertNull(store.get(9));
        assertNull(store.peek(9));
        assertNull(store.remove(9));
        assertEquals(2, store.size());
        assertEquals(List.of(1, 2), lruOrder(store));
    }

    @Test void snapshotAndContainsDoNotCountAsUse() {
        var store = filled(2, 1, 2);
        store.snapshot();
        assertTrue(store.contains(1));
        store.put(3, entry("v3"));
        assertEquals(List.of(1), evicted(store));
    }

    @Test void clearRemovesEntriesAndPendingEvictions() {
        var store = filled(2, 1, 2, 3);
        store.clear();
        assertEquals(0, store.size());
        assertNull(store.peek(2));
        assertTrue(store.drainEvictions().isEmpty());
        assertTrue(lruOrder(store).isEmpty());
    }

    @Test void leastRecentlyAccessedIsNextToBeEvicted() {
        var store = filled(3, 1, 2, 3);
        store.get(1);
        assertEquals(1, store.mostRecentlyAccessed(value -> true));
        assertEquals(2, store.leastRecentlyAccessed(value -> true));
        store.put(4, entry("v4"));
        assertEquals(List.of(2), evicted(store));
    }

    @Test void mostAndLeastRecentlyAccessedSkipEntitiesThatDoNotMatch() {
        var store = filled(3, 1, 2, 3);
        assertEquals(2, store.mostRecentlyAccessed(value -> !value.equals("v3")));
        assertEquals(2, store.leastRecentlyAccessed(value -> !value.equals("v1")));
        assertNull(store.mostRecentlyAccessed(value -> false));
        assertNull(store.leastRecentlyAccessed(value -> false));
    }

    @Test void mostAndLeastRecentlyAccessedDoNotCountAsUse() {
        var store = filled(2, 1, 2);
        store.leastRecentlyAccessed(value -> true);
        store.mostRecentlyAccessed(value -> true);
        store.put(3, entry("v3"));
        assertEquals(List.of(1), evicted(store));
    }

    @Test void readsBeyondTheBufferCapacityAreAllCountedInOrder() {
        var store = filled(3, 1, 2, 3);
        for (int i = 0; i < 10 * IdentityStoreBufferedLru.STRIPE_CAPACITY; i++) {
            store.get(2);
            store.get(1);
        }
        assertEquals(List.of(3, 2, 1), lruOrder(store));
        store.put(4, entry("v4"));
        assertEquals(List.of(3), evicted(store));
    }

    @Test void replacingAfterReadsKeepsTheOrderOfThisThread() {
        var store = filled(3, 1, 2, 3);
        store.get(2);
        store.put(1, entry("new"));
        assertEquals(List.of(3, 2, 1), lruOrder(store));
        store.put(4, entry("v4"));
        assertEquals(List.of(3), evicted(store));
    }

    @Test void insertingIntoAFullStoreNeverEvictsTheNewEntry() {
        var store = filled(1, 1);
        store.get(1);
        store.put(2, entry("v2"));
        assertEquals(List.of(1), evicted(store));
        assertEquals(List.of(2), lruOrder(store));
    }

    @Test void readsFromAnotherThreadCountAsUse() throws Exception {
        var store = filled(3, 1, 2, 3);
        Thread reader = new Thread(() -> store.get(1));
        reader.start();
        reader.join();

        store.put(4, entry("v4"));

        assertEquals(List.of(2), evicted(store));
    }

    @Test void bufferedReadsOfRemovedEntriesAreIgnored() {
        var store = filled(3, 1, 2, 3);
        store.get(1);
        store.remove(1);
        store.settle();
        assertEquals(List.of(2, 3), lruOrder(store));
        assertEquals(2, store.size());
    }

    @Test void bufferedReadsOfClearedEntriesAreIgnored() {
        var store = filled(3, 1, 2, 3);
        store.get(1);
        store.clear();
        store.put(4, entry("v4"));
        store.settle();
        assertEquals(List.of(4), lruOrder(store));
    }

    @Test void readsRacingEvictionNeverResurrectEntries() {
        var store = filled(2, 1, 2);
        store.get(1);
        store.get(2);
        store.put(3, entry("v3"));
        store.put(4, entry("v4"));
        store.get(3);
        store.settle();
        assertEquals(List.of(4, 3), lruOrder(store));
        assertEquals(List.of(1, 2), evicted(store));
    }

    @Test void concurrentReadsAndWritesKeepTheOrderAndTheEntriesInAgreement() throws Exception {
        concurrentReadsAndWrites(io.github.zdcache.Recency.STRICT);
    }

    @Test void approximateConcurrentReadsAndWritesKeepTheOrderAndTheEntriesInAgreement() throws Exception {
        concurrentReadsAndWrites(io.github.zdcache.Recency.APPROXIMATE);
    }

    private static void concurrentReadsAndWrites(io.github.zdcache.Recency recency) throws Exception {
        int capacity = 100;
        int ids = 1_000;
        var store = new IdentityStoreBufferedLru<Integer, String>(capacity, recency);
        var start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int t = 0; t < 8; t++) {
                boolean writer = t < 2;
                tasks.add(pool.submit(() -> {
                    start.await();
                    ThreadLocalRandom random = ThreadLocalRandom.current();
                    for (int i = 0; i < 50_000; i++) {
                        int id = random.nextInt(ids);
                        if (writer) {
                            if (random.nextInt(10) == 0) store.remove(id);
                            else store.put(id, entry("v" + id));
                        } else {
                            IndexedEntry<String> found = store.get(id);
                            if (found != null && !found.entity().equals("v" + id)) {
                                throw new AssertionError("get returned another entry");
                            }
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var task : tasks) task.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        store.settle();
        List<Integer> order = lruOrder(store);
        assertTrue(store.size() <= capacity);
        assertEquals(store.size(), order.size());
        assertEquals(order.size(), new HashSet<>(order).size());
        for (Integer id : order) assertTrue(store.contains(id));
        for (int id = 0; id < ids; id++) assertEquals(order.contains(id), store.contains(id));
    }

    @Test void evictedEntriesAreMarkedEvicted() {
        var store = filled(2, 1, 2);
        IndexedEntry<String> first = store.peek(1);
        store.put(3, entry("v3"));

        assertTrue(first.evicted());
        assertFalse(store.peek(2).evicted());
        assertFalse(store.peek(3).evicted());
    }

    @Test void removedAndReplacedEntriesAreNotMarkedEvicted() {
        var store = filled(3, 1, 2);
        IndexedEntry<String> replaced = store.put(1, entry("new"));
        IndexedEntry<String> removed = store.remove(2);

        assertFalse(replaced.evicted());
        assertFalse(removed.evicted());
    }

    private static java.util.concurrent.locks.ReentrantLock lockOf(IdentityStoreBufferedLru<?, ?> store) throws Exception {
        var field = IdentityStoreBufferedLru.class.getDeclaredField("lock");
        field.setAccessible(true);
        return (java.util.concurrent.locks.ReentrantLock) field.get(store);
    }

    private static Thread holding(java.util.concurrent.locks.ReentrantLock lock, CountDownLatch held, CountDownLatch release) {
        Thread holder = new Thread(() -> {
            lock.lock();
            try {
                held.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        });
        holder.start();
        return holder;
    }

    @Test void approximateReadersNeverWaitForTheLock() throws Exception {
        var store = new IdentityStoreBufferedLru<Integer, String>(3, io.github.zdcache.Recency.APPROXIMATE);
        for (int id = 1; id <= 3; id++) store.put(id, entry("v" + id));
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Thread holder = holding(lockOf(store), held, release);
        held.await();

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            for (int i = 0; i < 10 * IdentityStoreBufferedLru.STRIPE_CAPACITY; i++) assertEquals("v1", store.get(1).entity());
        });

        release.countDown();
        holder.join();
        store.settle();
        assertEquals(3, store.size());
        assertEquals(3, lruOrder(store).size());
    }

    @Test void strictReadersWaitForTheLockWhenTheirBufferIsFull() throws Exception {
        var store = filled(3, 1, 2, 3);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Thread holder = holding(lockOf(store), held, release);
        held.await();
        Thread reader = new Thread(() -> {
            for (int i = 0; i < 2 * IdentityStoreBufferedLru.STRIPE_CAPACITY; i++) store.get(1);
        });
        reader.start();

        reader.join(300);
        assertTrue(reader.isAlive());

        release.countDown();
        holder.join();
        reader.join(5_000);
        assertFalse(reader.isAlive());
        assertEquals(1, store.mostRecentlyAccessed(value -> true));
    }

    @Test void onlyApproximateRecencyAcceptsConcurrentWrites() {
        assertFalse(new IdentityStoreBufferedLru<Integer, String>(3).concurrentWrites());
        assertTrue(new IdentityStoreBufferedLru<Integer, String>(3, io.github.zdcache.Recency.APPROXIMATE).concurrentWrites());
        assertTrue(new IdentityStoreCaffeine<Integer, String>(3).concurrentWrites());
        assertFalse(new IdentityStoreLinkedHashMap<Integer, String>(3).concurrentWrites());
    }

    @Test void approximatePutsAreVisibleBeforeTheyAreLinked() throws Exception {
        var store = new IdentityStoreBufferedLru<Integer, String>(3, io.github.zdcache.Recency.APPROXIMATE);
        for (int id = 1; id <= 3; id++) store.put(id, entry("v" + id));
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Thread holder = holding(lockOf(store), held, release);
        held.await();

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            assertNull(store.put(4, entry("v4")));
            assertEquals("v1", store.put(1, entry("new")).entity());
        });
        assertEquals("v4", store.get(4).entity());
        assertEquals("new", store.peek(1).entity());
        assertTrue(store.contains(4));
        assertEquals(4, store.size());

        release.countDown();
        holder.join();
        store.settle();
        assertEquals(3, store.size());
        assertEquals(1, store.drainEvictions().size());
        assertEquals(3, lruOrder(store).size());
    }

    @Test void approximateWritersHelpDrainOnceTheWriteBufferIsFull() throws Exception {
        int capacity = 10;
        var store = new IdentityStoreBufferedLru<Integer, String>(capacity, io.github.zdcache.Recency.APPROXIMATE);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Thread holder = holding(lockOf(store), held, release);
        held.await();

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            for (int id = 0; id < IdentityStoreBufferedLru.WRITE_BUFFER_LIMIT; id++) store.put(id, entry("v" + id));
        });
        Thread overflowing = new Thread(() -> store.put(-1, entry("over")));
        overflowing.start();
        overflowing.join(300);
        assertTrue(overflowing.isAlive());

        release.countDown();
        holder.join();
        overflowing.join(5_000);
        assertFalse(overflowing.isAlive());
        store.settle();
        assertEquals(capacity, store.size());
        assertEquals(capacity, lruOrder(store).size());
        assertEquals(IdentityStoreBufferedLru.WRITE_BUFFER_LIMIT + 1 - capacity, store.drainEvictions().size());
    }

    @Test void approximateEvictionsAreExactWithoutContention() {
        var store = new IdentityStoreBufferedLru<Integer, String>(2, io.github.zdcache.Recency.APPROXIMATE);
        store.put(1, entry("v1"));
        store.put(2, entry("v2"));
        store.get(1);

        store.put(3, entry("v3"));

        assertEquals(List.of(2), evicted(store));
        assertEquals(List.of(1, 3), lruOrder(store));
        assertTrue(store.drainEvictions().isEmpty());
    }

    @Test void approximateReadsFromOneThreadAreReplayedInOrderWithoutContention() {
        var store = new IdentityStoreBufferedLru<Integer, String>(3, io.github.zdcache.Recency.APPROXIMATE);
        for (int id = 1; id <= 3; id++) store.put(id, entry("v" + id));
        for (int i = 0; i < 10 * IdentityStoreBufferedLru.STRIPE_CAPACITY; i++) {
            store.get(2);
            store.get(1);
        }

        assertEquals(List.of(3, 2, 1), lruOrder(store));
    }

    @Test void threadsCreatedOneAfterAnotherReadThroughSeparateBuffers() throws Exception {
        var store = new IdentityStoreBufferedLru<Integer, String>(10);
        int threads = Math.min(8, store.stripeCount());
        var indexes = java.util.Collections.synchronizedSet(new HashSet<Integer>());
        var workers = new ArrayList<Thread>();
        for (int t = 0; t < threads; t++) workers.add(new Thread(() -> indexes.add(store.stripeIndex())));
        for (Thread worker : workers) worker.start();
        for (Thread worker : workers) worker.join();

        assertEquals(threads, indexes.size());
    }
}
