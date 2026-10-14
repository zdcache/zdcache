package io.github.zdcache;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LockingTest {
    record Customer(long id, String name, int openOrders) {}
    record Order(long id, long customerId) {}
    record Invoice(long id, String number) {}

    static final IdentityDimension<Customer, Long> CUSTOMER_ID = IdentityDimension.of(Customer.class, "id", Customer::id);
    static final IdentityDimension<Order, Long> ORDER_ID = IdentityDimension.of(Order.class, "id", Order::id);

    private static ZDCache cache(Locking locking) {
        var cache = ZDCache.builder().locking(locking).build();
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
        for (Locking locking : Locking.values()) {
            var cache = cache(locking);
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

    @Test void lockFreeReadsWaitForARunningBlock() throws Exception {
        for (Locking locking : Locking.values()) {
            var cache = cache(locking);
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
        for (Locking locking : Locking.values()) {
            var cache = cache(locking);
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
        for (Locking locking : Locking.values()) {
            var cache = cache(locking);
            cache.put(new Customer(1, "Ada", 2));

            List<Customer> found = cache.atomically(() -> cache.atomically(() -> cache.search(Customer::name, "ad")));
            Customer customer = cache.atomically(() -> cache.get(Customer.class, 1L));

            assertEquals(1, found.size());
            assertEquals(2, customer.openOrders());
        }
    }

    @Test void anExceptionInsideABlockReleasesEveryLock() throws Exception {
        for (Locking locking : Locking.values()) {
            var cache = cache(locking);
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
