package io.github.zdcache.internal;

import io.github.zdcache.SearchSpecification;
import io.github.zdcache.store.IndexedEntry;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

final class SearchIndexes<T, ID> {
    private sealed interface Change<T> {}
    private record Add<T>(IndexedEntry<T> entry, Object[] snapshot) implements Change<T> {}
    private record Remove<T>(IndexedEntry<T> entry, Object[] snapshot) implements Change<T> {}
    private record Replace<T>(IndexedEntry<T> previous, Object[] before, IndexedEntry<T> entry, Object[] after)
        implements Change<T> {}
    private record Update<T>(IndexedEntry<T> entry, Object[] before, T entity, Object[] after) implements Change<T> {}

    interface Search<T, ID, R> {
        R run(DimensionRegistry<T, ID> copy, int side);
    }

    private record InFlight<T>(Map<IndexedEntry<T>, T> previous, int updatedSide) {}

    private static final int SPINS = 100;

    private final DimensionRegistry<T, ID>[] copies;
    private final List<Change<T>> pending = new ArrayList<>();
    private final LongAdder[] arrived = {new LongAdder(), new LongAdder()};
    private final LongAdder[] departed = {new LongAdder(), new LongAdder()};
    private volatile int active;
    private volatile int readersSide;
    private volatile InFlight<T> inFlight;

    SearchIndexes(SearchSpecification<T, ID> specification, boolean twoCopies) {
        this(new DimensionRegistry<>(specification), twoCopies ? new DimensionRegistry<>(specification) : null);
    }

    @SuppressWarnings("unchecked")
    SearchIndexes(DimensionRegistry<T, ID> first, DimensionRegistry<T, ID> secondOrNull) {
        copies = secondOrNull == null ? new DimensionRegistry[] {first} : new DimensionRegistry[] {first, secondOrNull};
    }

    boolean twoCopies() {
        return copies.length == 2;
    }

    DimensionRegistry<T, ID> current() {
        return copies[active];
    }

    int activeSide() {
        return active;
    }

    void add(IndexedEntry<T> entry, Object[] snapshot) {
        if (twoCopies()) pending.add(new Add<>(entry, snapshot));
        else copies[0].add(entry, snapshot);
    }

    void remove(IndexedEntry<T> entry, Object[] snapshot) {
        if (twoCopies()) pending.add(new Remove<>(entry, snapshot));
        else copies[0].remove(entry, snapshot);
    }

    void replace(IndexedEntry<T> previous, Object[] before, IndexedEntry<T> entry, Object[] after) {
        if (twoCopies()) pending.add(new Replace<>(previous, before, entry, after));
        else copies[0].replace(previous, before, entry, after);
    }

    void update(IndexedEntry<T> entry, T entity, Object[] after) {
        Object[] before = entry.snapshot();
        if (twoCopies()) {
            pending.add(new Update<>(entry, before, entity, after));
            entry.replaceSnapshot(after);
        } else {
            copies[0].replace(entry, before, entry, after);
            entry.update(entity, after);
        }
    }

    void publish() {
        if (pending.isEmpty()) return;
        int old = active;
        int next = 1 - old;
        Map<IndexedEntry<T>, T> previous = null;
        for (Change<T> change : pending) {
            if (change instanceof Update<T> update) {
                if (previous == null) previous = new IdentityHashMap<>();
                previous.putIfAbsent(update.entry(), update.entry().entity());
            }
        }
        if (previous != null) {
            inFlight = new InFlight<>(previous, next);
            for (Change<T> change : pending) {
                if (change instanceof Update<T> update) update.entry().update(update.entity(), update.entry().snapshot());
            }
        }
        apply(copies[next]);
        active = next;
        waitForReaders();
        apply(copies[old]);
        inFlight = null;
        pending.clear();
    }

    T entity(IndexedEntry<T> entry, int side) {
        T current = entry.entity();
        InFlight<T> update = inFlight;
        if (update == null || update.updatedSide() == side) return current;
        T previous = update.previous().get(entry);
        return previous != null ? previous : current;
    }

    <R> R read(Search<T, ID, R> search) {
        int side = readersSide;
        arrived[side].increment();
        try {
            int copy = active;
            return search.run(copies[copy], copy);
        } finally {
            departed[side].increment();
        }
    }

    private void apply(DimensionRegistry<T, ID> copy) {
        for (Change<T> change : pending) {
            switch (change) {
                case Add<T> add -> copy.add(add.entry(), add.snapshot());
                case Remove<T> remove -> copy.remove(remove.entry(), remove.snapshot());
                case Replace<T> replace -> copy.replace(replace.previous(), replace.before(), replace.entry(), replace.after());
                case Update<T> update -> copy.replace(update.entry(), update.before(), update.entry(), update.after());
            }
        }
    }

    private void waitForReaders() {
        int previous = readersSide;
        int next = 1 - previous;
        waitUntilEmpty(next);
        readersSide = next;
        waitUntilEmpty(previous);
    }

    private void waitUntilEmpty(int side) {
        for (int spin = 0; departed[side].sum() != arrived[side].sum(); spin++) {
            if (spin < SPINS) Thread.onSpinWait();
            else Thread.yield();
        }
    }
}
