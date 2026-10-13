package io.github.zdcache.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
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

    private static final class Node<ID, T> {
        final ID id;
        volatile IndexedEntry<T> entry;
        Node<ID, T> prev;
        Node<ID, T> next;
        boolean unlinked;

        Node(ID id, IndexedEntry<T> entry) {
            this.id = id;
            this.entry = entry;
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
    private final Stripe[] stripes;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicInteger writersWaiting = new AtomicInteger();
    private final Queue<EvictedEntry<ID, T>> evictions = new ConcurrentLinkedQueue<>();
    private volatile int size;

    public IdentityStoreBufferedLru(int maximumSize) {
        if (maximumSize < 1) throw new IllegalArgumentException("maximumSize must be positive");
        this.maximumSize = maximumSize;
        head.prev = head;
        head.next = head;
        int processors = Math.min(64, Runtime.getRuntime().availableProcessors());
        stripes = new Stripe[processors <= 1 ? 1 : Integer.highestOneBit(processors - 1) << 1];
        for (int i = 0; i < stripes.length; i++) stripes[i] = new Stripe();
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
        lockAsWriter();
        try {
            drain(stripes[stripeIndex()]);
            Node<ID, T> node = nodes.get(id);
            if (node != null) {
                IndexedEntry<T> previous = node.entry;
                node.entry = entry;
                moveToMostRecent(node);
                return previous;
            }
            if (size >= maximumSize) drainAll();
            node = new Node<>(id, entry);
            nodes.put(id, node);
            attachLast(node);
            size++;
            while (size > maximumSize) {
                Node<ID, T> victim = head.next;
                nodes.remove(victim.id);
                discard(victim);
                victim.entry.markEvicted();
                evictions.add(new EvictedEntry<>(victim.id, victim.entry));
            }
            return null;
        } finally { lock.unlock(); }
    }

    @Override public IndexedEntry<T> remove(ID id) {
        lockAsWriter();
        try {
            Node<ID, T> node = nodes.remove(id);
            if (node == null) return null;
            discard(node);
            return node.entry;
        } finally { lock.unlock(); }
    }

    @Override public Map<ID, IndexedEntry<T>> snapshot() {
        lockAsWriter();
        try {
            drainAll();
            Map<ID, IndexedEntry<T>> copy = new LinkedHashMap<>();
            for (Node<ID, T> node = head.next; node != head; node = node.next) copy.put(node.id, node.entry);
            return copy;
        } finally { lock.unlock(); }
    }

    @Override public boolean contains(ID id) { return nodes.containsKey(id); }

    @Override public int size() { return size; }

    @Override public void clear() {
        lockAsWriter();
        try {
            for (Node<ID, T> node = head.next; node != head; node = node.next) node.unlinked = true;
            head.prev = head;
            head.next = head;
            nodes.clear();
            evictions.clear();
            size = 0;
        } finally { lock.unlock(); }
    }

    @Override public ID mostRecentlyAccessed(Predicate<? super T> matching) {
        lockAsWriter();
        try {
            drainAll();
            for (Node<ID, T> node = head.prev; node != head; node = node.prev) {
                if (matching.test(node.entry.entity())) return node.id;
            }
            return null;
        } finally { lock.unlock(); }
    }

    @Override public ID leastRecentlyAccessed(Predicate<? super T> matching) {
        lockAsWriter();
        try {
            drainAll();
            for (Node<ID, T> node = head.next; node != head; node = node.next) {
                if (matching.test(node.entry.entity())) return node.id;
            }
            return null;
        } finally { lock.unlock(); }
    }

    @Override public void settle() {
        lockAsWriter();
        try {
            drainAll();
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

    private void recordAccess(Node<ID, T> node) {
        Stripe stripe = stripes[stripeIndex()];
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

    private int stripeIndex() {
        long thread = Thread.currentThread().threadId();
        int hash = (int) (thread ^ (thread >>> 32)) * 0x9E3779B9;
        return (hash ^ (hash >>> 16)) & (stripes.length - 1);
    }

    private void drainAll() {
        for (Stripe stripe : stripes) drain(stripe);
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
        if (node.unlinked || head.prev == node) return;
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
        detach(node);
        node.prev = null;
        node.next = null;
        node.unlinked = true;
        size--;
    }
}
