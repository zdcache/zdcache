package io.github.zdcache.store;

import io.github.zdcache.Recency;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

public final class IdentityStoreBufferedLru<ID, T> implements IdentityStore<ID, T> {
    static final int STRIPE_CAPACITY = 64;
    private static final int STRIPE_MASK = STRIPE_CAPACITY - 1;
    private static final int DRAIN_THRESHOLD = STRIPE_CAPACITY / 2;
    private static final int WRITER_SPINS = 1_000;
    private static final int MAX_STRIPES = 128;
    static final int WRITE_BUFFER_LIMIT = 1_024;
    private static final byte NEW = 0;
    private static final byte LINKED = 1;
    private static final byte DEAD = 2;

    private static final class Node<ID, T> {
        final ID id;
        volatile IndexedEntry<T> entry;
        Node<ID, T> prev;
        Node<ID, T> next;
        byte state = NEW;

        Node(ID id, IndexedEntry<T> entry) {
            this.id = id;
            this.entry = entry;
        }

        @SuppressWarnings("unchecked")
        IndexedEntry<T> swap(IndexedEntry<T> replacement) {
            return (IndexedEntry<T>) ENTRY.getAndSet(this, replacement);
        }
    }

    private static final VarHandle ENTRY;

    static {
        try {
            ENTRY = MethodHandles.lookup().findVarHandle(Node.class, "entry", IndexedEntry.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final class Stripe {
        final AtomicReferenceArray<Node<?, ?>> slots = new AtomicReferenceArray<>(STRIPE_CAPACITY);
        final AtomicLong written = new AtomicLong();
        volatile long drained;

        boolean offer(Node<?, ?> node) {
            for (;;) {
                long w = written.get();
                if (w - drained >= STRIPE_CAPACITY) return false;
                if (written.compareAndSet(w, w + 1)) {
                    slots.setRelease((int) (w & STRIPE_MASK), node);
                    return true;
                }
            }
        }

        long pending() { return written.get() - drained; }
    }

    private final int maximumSize;
    private final ConcurrentHashMap<ID, Node<ID, T>> nodes = new ConcurrentHashMap<>();
    private final Node<ID, T> head = new Node<>(null, null);
    private final AtomicReferenceArray<Stripe> stripes;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicInteger writersWaiting = new AtomicInteger();
    private final Queue<EvictedEntry<ID, T>> evictions = new ConcurrentLinkedQueue<>();
    private final Queue<Node<ID, T>> pendingLinks = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingWrites = new AtomicInteger();
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicBoolean replayScheduled = new AtomicBoolean();
    private final AtomicInteger readingThreads = new AtomicInteger();
    private final boolean approximate;


    public IdentityStoreBufferedLru(int maximumSize) {
        this(maximumSize, Recency.STRICT);
    }

    public IdentityStoreBufferedLru(int maximumSize, Recency recency) {
        if (maximumSize < 1) throw new IllegalArgumentException("maximumSize must be positive");
        this.maximumSize = maximumSize;
        this.approximate = Objects.requireNonNull(recency) == Recency.APPROXIMATE;
        head.prev = head;
        head.next = head;
        int wanted = Math.min(MAX_STRIPES, 4 * Runtime.getRuntime().availableProcessors());
        stripes = new AtomicReferenceArray<>(wanted <= 1 ? 1 : Integer.highestOneBit(wanted - 1) << 1);
    }

    @Override public IndexedEntry<T> get(ID id) {
        Node<ID, T> node = nodes.get(id);
        if (node == null) return null;
        IndexedEntry<T> entry = node.entry;
        recordAccess(node);
        return entry;
    }

    @Override public IndexedEntry<T> peek(ID id) {
        Node<ID, T> node = nodes.get(id);
        return node == null ? null : node.entry;
    }

    @Override public IndexedEntry<T> put(ID id, IndexedEntry<T> entry) {
        return approximate ? putBuffered(id, entry) : putInOrder(id, entry);
    }

    private IndexedEntry<T> putBuffered(ID id, IndexedEntry<T> entry) {
        Node<ID, T> node;
        for (;;) {
            Node<ID, T> existing = nodes.get(id);
            if (existing != null) {
                IndexedEntry<T> previous = existing.swap(entry);
                if (nodes.get(id) == existing) {
                    recordAccess(existing);
                    return previous;
                }
                continue;
            }
            node = new Node<>(id, entry);
            if (nodes.putIfAbsent(id, node) == null) break;
        }
        size.incrementAndGet();
        pendingLinks.add(node);
        if (pendingWrites.incrementAndGet() > WRITE_BUFFER_LIMIT) {
            lockAsWriter();
            try {
                maintain();
            } finally { lock.unlock(); }
        }
        while (!pendingLinks.isEmpty() && writersWaiting.get() == 0 && lock.tryLock()) {
            try {
                maintain();
            } finally { lock.unlock(); }
        }
        return null;
    }

    private IndexedEntry<T> putInOrder(ID id, IndexedEntry<T> entry) {
        lockAsWriter();
        try {
            Stripe own = stripes.get(stripeIndex());
            if (own != null) drain(own);
            Node<ID, T> node = nodes.get(id);
            if (node != null) {
                IndexedEntry<T> previous = node.entry;
                node.entry = entry;
                moveToMostRecent(node);
                return previous;
            }
            if (size.get() >= maximumSize) drainAll();
            node = new Node<>(id, entry);
            nodes.put(id, node);
            attachLast(node);
            node.state = LINKED;
            size.incrementAndGet();
            evictOverflow();
            return null;
        } finally { lock.unlock(); }
    }

    @Override public IndexedEntry<T> remove(ID id) {
        lockAsWriter();
        try {
            linkPending();
            Node<ID, T> node = nodes.remove(id);
            if (node == null) return null;
            size.decrementAndGet();
            discard(node);
            return node.entry;
        } finally { lock.unlock(); }
    }

    @Override public Map<ID, IndexedEntry<T>> snapshot() {
        lockAsWriter();
        try {
            maintain();
            Map<ID, IndexedEntry<T>> copy = new LinkedHashMap<>();
            for (Node<ID, T> node = head.next; node != head; node = node.next) copy.put(node.id, node.entry);
            return copy;
        } finally { lock.unlock(); }
    }

    @Override public boolean contains(ID id) { return nodes.containsKey(id); }

    @Override public int size() { return size.get(); }

    @Override public void clear() {
        lockAsWriter();
        try {
            linkPending();
            for (Node<ID, T> node : nodes.values()) {
                if (nodes.remove(node.id, node)) {
                    size.decrementAndGet();
                    node.state = DEAD;
                }
            }
            for (Node<ID, T> node = head.next; node != head; node = node.next) node.state = DEAD;
            head.prev = head;
            head.next = head;
            evictions.clear();
        } finally { lock.unlock(); }
    }

    @Override public ID mostRecentlyAccessed(Predicate<? super T> matching) {
        lockAsWriter();
        try {
            maintain();
            for (Node<ID, T> node = head.prev; node != head; node = node.prev) {
                if (matching.test(node.entry.entity())) return node.id;
            }
            return null;
        } finally { lock.unlock(); }
    }

    @Override public ID leastRecentlyAccessed(Predicate<? super T> matching) {
        lockAsWriter();
        try {
            maintain();
            for (Node<ID, T> node = head.next; node != head; node = node.next) {
                if (matching.test(node.entry.entity())) return node.id;
            }
            return null;
        } finally { lock.unlock(); }
    }

    @Override public void settle() {
        lockAsWriter();
        try {
            maintain();
        } finally { lock.unlock(); }
    }

    @Override public List<EvictedEntry<ID, T>> drainEvictions() {
        if (evictions.isEmpty()) return List.of();
        List<EvictedEntry<ID, T>> events = new ArrayList<>();
        EvictedEntry<ID, T> event;
        while ((event = evictions.poll()) != null) events.add(event);
        return events;
    }

    @Override public boolean concurrent() { return true; }

    @Override public boolean concurrentWrites() { return approximate; }

    private void recordAccess(Node<ID, T> node) {
        Stripe stripe = stripe();
        if (approximate) {
            if (stripe.offer(node)) {
                if (stripe.pending() < DRAIN_THRESHOLD) return;
                if (readingThreads.get() > 1) {
                    scheduleReplay();
                } else if (writersWaiting.get() == 0 && lock.tryLock()) {
                    try {
                        drain(stripe);
                    } finally { lock.unlock(); }
                }
            } else if (writersWaiting.get() == 0 && lock.tryLock()) {
                try {
                    drain(stripe);
                    moveToMostRecent(node);
                } finally { lock.unlock(); }
            }
            return;
        }
        if (!stripe.offer(node)) {
            while (writersWaiting.get() > 0) Thread.yield();
            lock.lock();
            try {
                drain(stripe);
                moveToMostRecent(node);
            } finally { lock.unlock(); }
        } else if (stripe.pending() >= DRAIN_THRESHOLD && writersWaiting.get() == 0 && lock.tryLock()) {
            try {
                drainUntilAWriterWaits(stripe);
            } finally { lock.unlock(); }
        }
    }

    private void scheduleReplay() {
        if (replayScheduled.compareAndSet(false, true)) ForkJoinPool.commonPool().execute(this::replay);
    }

    private void replay() {
        try {
            lockAsWriter();
            try {
                drainAll();
            } finally { lock.unlock(); }
        } finally {
            replayScheduled.set(false);
        }
    }

    private void lockAsWriter() {
        writersWaiting.incrementAndGet();
        try {
            for (int spin = 0; spin < WRITER_SPINS; spin++) {
                if (lock.tryLock()) return;
                Thread.onSpinWait();
            }
            lock.lock();
        } finally {
            writersWaiting.decrementAndGet();
        }
    }

    private Stripe stripe() {
        int index = stripeIndex();
        Stripe stripe = stripes.get(index);
        if (stripe != null) return stripe;
        if (stripes.compareAndSet(index, null, new Stripe())) readingThreads.incrementAndGet();
        return stripes.get(index);
    }

    private int stripeIndex() {
        long thread = Thread.currentThread().threadId();
        int hash = (int) (thread ^ (thread >>> 32)) * 0x9E3779B9;
        return (hash ^ (hash >>> 16)) & (stripes.length() - 1);
    }

    private void maintain() {
        drainAll();
        linkPending();
        evictOverflow();
    }

    private void linkPending() {
        Node<ID, T> node;
        while ((node = pendingLinks.poll()) != null) {
            pendingWrites.decrementAndGet();
            if (node.state == NEW) {
                attachLast(node);
                node.state = LINKED;
            }
        }
    }

    private void evictOverflow() {
        while (size.get() > maximumSize && head.next != head) {
            Node<ID, T> victim = head.next;
            if (nodes.remove(victim.id, victim)) {
                size.decrementAndGet();
                victim.entry.markEvicted();
                evictions.add(new EvictedEntry<>(victim.id, victim.entry));
            }
            discard(victim);
        }
    }

    private void drainAll() {
        for (int i = 0; i < stripes.length(); i++) {
            Stripe stripe = stripes.get(i);
            if (stripe != null) drain(stripe);
        }
    }

    private void drain(Stripe stripe) {
        drain(stripe, false);
    }

    private void drainUntilAWriterWaits(Stripe stripe) {
        drain(stripe, true);
    }

    @SuppressWarnings("unchecked")
    private void drain(Stripe stripe, boolean yieldToWriters) {
        long read = stripe.drained;
        long written = stripe.written.get();
        while (read < written) {
            int slot = (int) (read & STRIPE_MASK);
            Node<ID, T> node = (Node<ID, T>) stripe.slots.getAcquire(slot);
            if (node == null) break;
            stripe.slots.setPlain(slot, null);
            moveToMostRecent(node);
            read++;
            if (yieldToWriters && (read & 7) == 0 && writersWaiting.get() > 0) break;
        }
        stripe.drained = read;
    }

    private void moveToMostRecent(Node<ID, T> node) {
        if (node.state != LINKED || head.prev == node) return;
        detach(node);
        attachLast(node);
    }

    private void attachLast(Node<ID, T> node) {
        node.prev = head.prev;
        node.next = head;
        head.prev.next = node;
        head.prev = node;
    }

    private void detach(Node<ID, T> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    private void discard(Node<ID, T> node) {
        if (node.state == LINKED) {
            detach(node);
            node.prev = null;
            node.next = null;
        }
        node.state = DEAD;
    }
}
