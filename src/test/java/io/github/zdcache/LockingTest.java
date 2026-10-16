package io.github.zdcache;

import java.util.ArrayList;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LockingTest {
    record Customer(long id, String name, int openOrders) {}
    record Order(long id, long customerId) {}
    record Invoice(long id, String number) {}

    static final IdentityDimension<Customer, Long> CUSTOMER_ID = IdentityDimension.of(Customer.class, "id", Customer::id);
    static final IdentityDimension<Order, Long> ORDER_ID = IdentityDimension.of(Order.class, "id", Order::id);

    private record Setup(Locking locking, Entries entries, Searches searches) {
        ZDCache cache() { return LockingTest.cache(locking, entries, searches); }
        @Override public String toString() { return locking + "/" + entries + "/" + searches; }
    }

    private static final List<Setup> SETUPS = java.util.Arrays.stream(Locking.values())
        .flatMap(locking -> java.util.Arrays.stream(Entries.values())
            .flatMap(entries -> java.util.Arrays.stream(Searches.values()).map(searches -> new Setup(locking, entries, searches))))
        .toList();

    private static ZDCache cache(Locking locking) {
        return cache(locking, Entries.PER_PUT, Searches.BLOCKING);
    }

    private static ZDCache cache(Locking locking, Entries entries, Searches searches) {
        var cache = ZDCache.builder().locking(locking).entries(entries).searches(searches).build();
        cache.register(SearchSpecification.builder(CUSTOMER_ID).prefix(Customer::name).build());
        cache.register(SearchSpecification.builder(ORDER_ID).exact(Order::customerId).build());
        return cache;
    }

    private static boolean writeToOrdersFinishesWhileCustomersAreLocked(Locking locking) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var slowSpec = SearchSpecification.builder(CUSTOMER_ID)
            .prefix("name", customer -> {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return customer.name();
            })
            .build();
        var cache = ZDCache.builder().locking(locking).build();
        cache.register(SearchSpecification.builder(ORDER_ID).build());
        Thread slowWriter = new Thread(() -> cache.put(new Customer(1, "Ada", 0), slowSpec));
        slowWriter.start();
        entered.await();

        Thread orderWriter = new Thread(() -> cache.put(new Order(7, 1)));
        orderWriter.start();
        orderWriter.join(300);
        boolean finished = !orderWriter.isAlive();

        release.countDown();
        slowWriter.join();
        orderWriter.join();
        assertNotNull(cache.get(Order.class, 7L));
        return finished;
    }

    @Test void perTypeLockingLetsOtherTypesWriteWhileOneTypeIsBusy() throws Exception {
        assertTrue(writeToOrdersFinishesWhileCustomersAreLocked(Locking.PER_TYPE));
    }

    @Test void singleLockingMakesEveryTypeWaitForTheBusyOne() throws Exception {
        assertFalse(writeToOrdersFinishesWhileCustomersAreLocked(Locking.SINGLE));
    }

    @Test void perTypeIsTheDefaultAndLockingIsRequired() throws Exception {
        assertThrows(NullPointerException.class, () -> ZDCache.builder().locking(null));
    }

    @Test void otherThreadsNeverSeeAnAtomicBlockHalfDone() throws Exception {
        for (Setup locking : SETUPS) {
            var cache = locking.cache();
            cache.put(new Customer(1, "Ada", 1_000));
            for (long id = 1; id <= 1_000; id++) cache.put(new Order(id, 1));
            var done = new AtomicBoolean();
            var violation = new AtomicReference<String>();

            Thread reader = new Thread(() -> {
                while (!done.get() && violation.get() == null) {
                    long finishedBlocks = 1_000 - cache.get(Customer.class, 1L).openOrders();
                    if (finishedBlocks > 0 && cache.get(Order.class, finishedBlocks) != null) {
                        violation.set(locking + ": saw block " + finishedBlocks + " decrement the customer before removing its order");
                    }
                }
            });
            reader.start();
            for (long id = 1; id <= 1_000; id++) {
                long order = id;
                cache.atomically(() -> {
                    Customer customer = cache.get(Customer.class, 1L);
                    cache.put(new Customer(1, "Ada", customer.openOrders() - 1));
                    Thread.onSpinWait();
                    cache.remove(Order.class, order);
                });
            }
            done.set(true);
            reader.join();

            assertNull(violation.get(), violation::get);
            assertEquals(0, cache.get(Customer.class, 1L).openOrders());
        }
    }

    @Test void searchesNeverSeeAnAtomicBlockHalfDone() throws Exception {
        for (Setup locking : SETUPS) {
            var cache = locking.cache();
            cache.put(new Customer(1, "Ada", 1_000));
            for (long id = 1; id <= 1_000; id++) cache.put(new Order(id, 1));
            var done = new AtomicBoolean();
            var violation = new AtomicReference<String>();

            Thread reader = new Thread(() -> {
                while (!done.get() && violation.get() == null) {
                    long finishedBlocks = 1_000 - cache.search(Customer.class, "name", "Ada").getFirst().openOrders();
                    boolean orderStillThere = cache.search(Order.class, "customerId", 1L).stream()
                        .anyMatch(order -> order.id() == finishedBlocks);
                    if (finishedBlocks > 0 && orderStillThere) {
                        violation.set(locking + ": saw block " + finishedBlocks + " decrement the customer before removing its order");
                    }
                }
            });
            reader.start();
            for (long id = 1; id <= 1_000; id++) {
                long order = id;
                cache.atomically(() -> {
                    Customer customer = cache.get(Customer.class, 1L);
                    cache.put(new Customer(1, "Ada", customer.openOrders() - 1));
                    Thread.onSpinWait();
                    cache.remove(Order.class, order);
                });
            }
            done.set(true);
            reader.join();

            assertNull(violation.get(), violation::get);
            assertEquals(0, cache.search(Customer.class, "name", "Ada").getFirst().openOrders());
        }
    }

    @Test void oneSearchNeverSeesPartOfABlock() throws Exception {
        for (Setup locking : SETUPS) {
            var cache = locking.cache();
            cache.put(new Customer(1, "Ada", 500));
            cache.put(new Customer(2, "Adam", 500));
            var done = new AtomicBoolean();
            var violation = new AtomicReference<String>();
            List<Thread> readers = new java.util.ArrayList<>();
            for (int r = 0; r < 3; r++) {
                Thread reader = new Thread(() -> {
                    while (!done.get() && violation.get() == null) {
                        List<Customer> both = cache.search(Customer.class, "name", "Ad");
                        int total = both.stream().mapToInt(Customer::openOrders).sum();
                        if (both.size() == 2 && total != 1_000) violation.set(locking + ": one search saw a total of " + total);
                    }
                });
                reader.start();
                readers.add(reader);
            }
            for (int i = 0; i < 5_000; i++) {
                cache.atomically(() -> {
                    Customer from = cache.get(Customer.class, 1L);
                    Customer to = cache.get(Customer.class, 2L);
                    cache.put(new Customer(1, "Ada", from.openOrders() - 1));
                    cache.put(new Customer(2, "Adam", to.openOrders() + 1));
                });
            }
            done.set(true);
            for (Thread reader : readers) reader.join();
            assertNull(violation.get(), violation::get);
        }
    }

    @Test void nonBlockingSearchesDoNotWaitForAWriterHoldingTheTypeLock() throws Exception {
        for (Searches searches : Searches.values()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var slowSpec = SearchSpecification.builder(CUSTOMER_ID)
                .prefix("name", customer -> {
                    if (customer.openOrders() == 99) {
                        entered.countDown();
                        awaitUninterruptibly(release);
                    }
                    return customer.name();
                })
                .build();
            var cache = ZDCache.builder().searches(searches).build();
            cache.put(new Customer(1, "Ada", 0), slowSpec);
            Thread writer = new Thread(() -> cache.put(new Customer(2, "Bob", 99)));
            writer.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            var found = new AtomicReference<List<Customer>>();
            Thread search = new Thread(() -> found.set(cache.search(Customer.class, "name", "Ada")));
            search.start();
            search.join(500);
            boolean finished = !search.isAlive();
            release.countDown();
            writer.join();
            search.join();

            assertEquals(searches == Searches.NON_BLOCKING, finished, searches + ": search finished while the writer held the lock");
            assertEquals(List.of(new Customer(1, "Ada", 0)), found.get());
        }
    }

    @Test void lockFreeReadsWaitForARunningBlock() throws Exception {
        for (Setup locking : SETUPS) {
            var cache = locking.cache();
            cache.put(new Customer(1, "Ada", 0));
            var inside = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var seen = new AtomicReference<Customer>();

            Thread block = new Thread(() -> cache.atomically(() -> {
                cache.put(new Customer(1, "Ada", 5));
                inside.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                cache.put(new Customer(1, "Ada Lovelace", 5));
            }));
            block.start();
            inside.await();
            Thread reader = new Thread(() -> seen.set(cache.get(Customer.class, 1L)));
            reader.start();
            reader.join(200);
            assertTrue(reader.isAlive(), locking + ": get returned while the block was running");

            release.countDown();
            block.join();
            reader.join();
            assertEquals("Ada Lovelace", seen.get().name());
        }
    }

    @Test void typesRegisteredInsideABlockAreLockedUntilItEnds() throws Exception {
        for (Setup locking : SETUPS) {
            var cache = locking.cache();
            var inside = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var seen = new AtomicReference<Invoice>();

            Thread block = new Thread(() -> cache.atomically(() -> {
                cache.put(Invoice.class, 1L, new Invoice(1, "draft"));
                inside.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                cache.put(Invoice.class, 1L, new Invoice(1, "INV-1"));
            }));
            block.start();
            inside.await();
            Thread reader = new Thread(() -> seen.set(cache.get(Invoice.class, 1L)));
            reader.start();
            reader.join(200);
            assertTrue(reader.isAlive(), locking + ": the new type was readable before the block ended");

            release.countDown();
            block.join();
            reader.join();
            assertEquals("INV-1", seen.get().number());
        }
    }

    @Test void blocksReturnValuesAndNestInsideEachOther() {
        for (Setup locking : SETUPS) {
            var cache = locking.cache();
            cache.put(new Customer(1, "Ada", 2));

            List<Customer> found = cache.atomically(() -> cache.atomically(() -> cache.search(Customer::name, "ad")));
            Customer customer = cache.atomically(() -> cache.get(Customer.class, 1L));

            assertEquals(1, found.size());
            assertEquals(2, customer.openOrders());
        }
    }

    @Test void virtualThreadsWaitingForABlockLeaveCarriersForOtherWork() throws Exception {
        var cache = cache(Locking.PER_TYPE);
        int waiters = 2 * Runtime.getRuntime().availableProcessors() + 4;
        var inside = new CountDownLatch(1);
        var started = new CountDownLatch(waiters);
        var release = new CountDownLatch(1);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<?> holder = executor.submit(() -> cache.atomically(() -> {
                inside.countDown();
                awaitUninterruptibly(release);
                cache.put(new Customer(1, "Ada", 0));
            }));
            inside.await();
            List<Future<?>> waiting = new ArrayList<>();
            for (int i = 0; i < waiters; i++) {
                long id = i;
                waiting.add(executor.submit(() -> {
                    started.countDown();
                    cache.atomically(() -> cache.put(new Order(id, 1)));
                }));
            }
            assertTrue(started.await(10, TimeUnit.SECONDS), "waiting threads could not all start");
            Thread.sleep(100);
            executor.submit(release::countDown);
            holder.get(10, TimeUnit.SECONDS);
            for (Future<?> future : waiting) future.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.close();
        }
        assertNotNull(cache.get(Customer.class, 1L));
        for (long id = 0; id < waiters; id++) assertNotNull(cache.get(Order.class, id));
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test void blocksAllocateNothingPerCall() {
        var cache = cache(Locking.PER_TYPE);
        Runnable nothing = () -> { };
        Supplier<String> constant = () -> "done";
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        for (int i = 0; i < 20_000; i++) {
            cache.atomically(nothing);
            cache.atomically(constant);
        }
        long before = threads.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < 100_000; i++) {
            cache.atomically(nothing);
            cache.atomically(constant);
        }
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        assertTrue(allocated < 100_000, allocated + " bytes for 200,000 blocks");
    }

    @Test void aBlockOnOneCacheCanOpenABlockOnAnother() throws Exception {
        var first = cache(Locking.PER_TYPE);
        var second = cache(Locking.PER_TYPE);
        first.atomically(() -> {
            first.put(new Customer(1, "Ada", 0));
            second.atomically(() -> second.put(new Customer(2, "Bob", 0)));
        });
        Thread writer = new Thread(() -> {
            first.put(new Order(1, 1));
            second.put(new Order(2, 2));
        });
        writer.start();
        writer.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(writer.isAlive(), "a lock was left held");
        assertNotNull(second.get(Customer.class, 2L));
    }

    @Test void anExceptionInsideABlockReleasesEveryLock() throws Exception {
        for (Setup locking : SETUPS) {
            var cache = locking.cache();
            cache.put(new Customer(1, "Ada", 0));

            assertThrows(IllegalStateException.class, () -> cache.atomically(() -> {
                cache.put(new Customer(2, "Bob", 0));
                throw new IllegalStateException("abort");
            }));

            Thread writer = new Thread(() -> cache.put(new Order(1, 1)));
            writer.start();
            writer.join(TimeUnit.SECONDS.toMillis(5));
            assertFalse(writer.isAlive(), locking + ": a lock was left held");
            assertNotNull(cache.get(Customer.class, 2L));
        }
    }
}
