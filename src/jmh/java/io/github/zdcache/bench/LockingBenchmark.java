package io.github.zdcache.bench;

import io.github.zdcache.Locking;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.jmh.annotations.*;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class LockingBenchmark {
    private static final int ORDER_MASK = (1 << 16) - 1;

    @Param({"100000"})
    int size;

    @Param({"PER_TYPE", "SINGLE"})
    String locking;

    MultiTypeCache cache;
    final AtomicInteger threads = new AtomicInteger();

    @Setup(Level.Trial)
    public void setUp() {
        cache = new MultiTypeCache(Locking.valueOf(locking), size);
    }

    @State(Scope.Thread)
    public static class Cursor {
        int[] order;
        int next;
        int type;

        @Setup(Level.Trial)
        public void setUp(LockingBenchmark benchmark) {
            type = benchmark.threads.getAndIncrement() % MultiTypeCache.TYPES;
            order = Dataset.accessOrder(benchmark.size, ORDER_MASK + 1, Thread.currentThread().threadId());
        }

        int nextIndex() {
            return order[next++ & ORDER_MASK];
        }

        int version() {
            return (next >>> 16) & 1;
        }
    }

    @Benchmark
    @Threads(4)
    public void writesToSeparateTypes(Cursor cursor) {
        cache.write(cursor.type, cursor.nextIndex(), cursor.version());
    }

    @Benchmark
    @Threads(4)
    public MultiTypeCache.Account atomicBlocksAcrossTypes(Cursor cursor) {
        return cache.transfer(cursor.type, (cursor.type + 1) % MultiTypeCache.TYPES, cursor.nextIndex(), cursor.version(), true);
    }

    @Benchmark
    @Threads(4)
    public MultiTypeCache.Account separateCallsAcrossTypes(Cursor cursor) {
        return cache.transfer(cursor.type, (cursor.type + 1) % MultiTypeCache.TYPES, cursor.nextIndex(), cursor.version(), false);
    }

    @Benchmark
    @Group("mixed")
    @GroupThreads(6)
    public MultiTypeCache.Account mixedRead(Cursor cursor) {
        int index = cursor.nextIndex();
        return cache.read(index % MultiTypeCache.TYPES, index);
    }

    @Benchmark
    @Group("mixed")
    @GroupThreads(2)
    public void mixedWrite(Cursor cursor) {
        cache.write(cursor.type, cursor.nextIndex(), cursor.version());
    }
}
