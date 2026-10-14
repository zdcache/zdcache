package io.github.zdcache.bench;

import io.github.zdcache.Locking;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MultiTypeCacheTest {

    @Test void everyTypeIsFilledAndIndexed() {
        for (Locking locking : Locking.values()) {
            var fixture = new MultiTypeCache(locking, 500);

            for (int type = 0; type < MultiTypeCache.TYPES; type++) {
                assertEquals(type, List.of(MultiTypeCache.Customer.class, MultiTypeCache.Vendor.class,
                    MultiTypeCache.Warehouse.class, MultiTypeCache.Carrier.class).indexOf(fixture.read(type, 7).getClass()));
            }
            assertEquals(500, fixture.cache().size(MultiTypeCache.Customer.class));
            var account = fixture.read(2, 7);
            assertTrue(fixture.cache().search(MultiTypeCache.Warehouse.class, "city", account.city()).contains(account));
        }
    }

    @Test void writesAndTransfersReplaceEntriesWithTheChosenVersion() {
        for (Locking locking : Locking.values()) {
            var fixture = new MultiTypeCache(locking, 100);

            fixture.write(0, 3, 1);
            var before = fixture.transfer(1, 2, 5, 1, true);
            fixture.transfer(3, 0, 6, 1, false);

            assertEquals(1, fixture.read(0, 3).balance());
            assertEquals(0, before.balance());
            assertEquals(1, fixture.read(1, 5).balance());
            assertEquals(1, fixture.read(2, 5).balance());
            assertEquals(1, fixture.read(3, 6).balance());
            assertEquals(1, fixture.read(0, 6).balance());
        }
    }

    @Test void concurrentTransfersInBlocksKeepBothTypesInStep() throws Exception {
        for (Locking locking : Locking.values()) {
            var fixture = new MultiTypeCache(locking, 50);
            List<Thread> threads = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                int from = t;
                threads.add(new Thread(() -> {
                    for (int i = 0; i < 2_000; i++) fixture.transfer(from, (from + 1) % MultiTypeCache.TYPES, i % 50, i & 1, true);
                }));
            }
            for (Thread thread : threads) thread.start();
            for (Thread thread : threads) thread.join();

            for (int type = 0; type < MultiTypeCache.TYPES; type++) {
                assertEquals(50, fixture.cache().size(fixture.read(type, 0).getClass()));
            }
        }
    }
}
