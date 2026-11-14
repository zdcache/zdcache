package io.github.zdcache.internal;

import io.github.zdcache.IndexKind;
import io.github.zdcache.Match;
import io.github.zdcache.SearchDimension;
import io.github.zdcache.SearchSpecification;
import io.github.zdcache.Split;
import io.github.zdcache.UndeclaredSearchStrategyException;
import io.github.zdcache.ValueRange;
import io.github.zdcache.index.CompoundIndex;
import io.github.zdcache.index.DimensionIndex;
import io.github.zdcache.index.ExactIndex;
import io.github.zdcache.index.PrefixTrieIndex;
import io.github.zdcache.index.RangeIndex;
import io.github.zdcache.index.TrigramIndex;
import io.github.zdcache.store.IndexedEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

public final class DimensionRegistry<T, ID> {
    private static final Object[] NO_VALUES = new Object[0];

    private record Many(Object[] values) {}

    private final SearchSpecification<T, ID> specification;
    private final List<SearchDimension<T>> dimensions;
    private final List<DimensionIndex<IndexedEntry<T>>> indexes = new ArrayList<>();
    private final List<PrefixTrieIndex<IndexedEntry<T>>> words = new ArrayList<>();
    private final List<TrigramIndex<IndexedEntry<T>>> trigrams = new ArrayList<>();
    private final Map<String, Integer> positions = new HashMap<>();
    private final int[] carriedFrom;
    private final boolean[] freshIndex;
    private final boolean[] freshWords;
    private final boolean[] freshTrigrams;
    private Consumer<Change<T>> recorder;
    private final Ordinals ordinals;

    public record Change<T>(boolean added, IndexedEntry<T> entry, Object[] snapshot) {}

    public DimensionRegistry(SearchSpecification<T, ID> specification) {
        this.ordinals = new Ordinals();
        this.specification = specification;
        this.dimensions = specification.dimensions();
        carriedFrom = new int[dimensions.size()];
        freshIndex = new boolean[dimensions.size()];
        freshWords = new boolean[dimensions.size()];
        freshTrigrams = new boolean[dimensions.size()];
        for (SearchDimension<T> dimension : dimensions) {
            positions.put(dimension.path(), indexes.size());
            indexes.add(index(dimension));
            words.add(dimension.split() == null ? null : new PrefixTrieIndex<>());
            trigrams.add(dimension.contains() ? new TrigramIndex<>() : null);
        }
    }

    public DimensionRegistry(DimensionRegistry<T, ID> current, SearchSpecification<T, ID> next) {
        this.ordinals = current.ordinals;
        this.specification = next;
        this.dimensions = next.dimensions();
        carriedFrom = new int[dimensions.size()];
        freshIndex = new boolean[dimensions.size()];
        freshWords = new boolean[dimensions.size()];
        freshTrigrams = new boolean[dimensions.size()];
        for (int i = 0; i < dimensions.size(); i++) {
            SearchDimension<T> dimension = dimensions.get(i);
            positions.put(dimension.path(), i);
            Integer before = current.positions.get(dimension.path());
            SearchDimension<T> previous = before == null ? null : current.dimensions.get(before);
            boolean kept = previous != null && previous.extractsLike(dimension);
            carriedFrom[i] = kept ? before : -1;
            freshIndex[i] = !kept;
            indexes.add(kept ? current.indexes.get(before) : index(dimension));
            boolean sameSplit = kept && previous.split() == dimension.split();
            freshWords[i] = dimension.split() != null && !sameSplit;
            words.add(dimension.split() == null ? null : sameSplit ? current.words.get(before) : new PrefixTrieIndex<>());
            boolean sameContains = kept && previous.contains();
            freshTrigrams[i] = dimension.contains() && !sameContains;
            trigrams.add(!dimension.contains() ? null : sameContains ? current.trigrams.get(before) : new TrigramIndex<>());
        }
    }

    private static <T> DimensionIndex<IndexedEntry<T>> index(SearchDimension<T> dimension) {
        return switch (dimension.kind()) {
            case PREFIX -> new PrefixTrieIndex<>();
            case EXACT -> new ExactIndex<>();
            case RANGE -> new RangeIndex<>();
            case COMPOUND -> new CompoundIndex<>(dimension.parts().getLast().kind());
        };
    }

    public void record(Consumer<Change<T>> recorder) { this.recorder = recorder; }

    public boolean builds(String path, Object[] values) {
        Integer position = positions.get(path);
        if (position == null) return false;
        if (freshIndex[position]) return true;
        Object query = values != null && values.length == 1 ? values[0] : null;
        return query instanceof Match.Words && freshWords[position] || query instanceof Match.Contains && freshTrigrams[position];
    }

    private Set<String> words(int position, Object slot) {
        Split split = dimensions.get(position).split();
        Set<String> all = new LinkedHashSet<>();
        for (Object value : values(slot)) all.addAll(split.words((String) value));
        return all;
    }

    private void addTo(int position, IndexedEntry<T> entry, Object slot, boolean index, boolean wordIndex, boolean trigramIndex) {
        if (index) for (Object value : values(slot)) indexes.get(position).add(entry, value);
        if (wordIndex && words.get(position) != null) for (String word : words(position, slot)) words.get(position).add(entry, word);
        if (trigramIndex && trigrams.get(position) != null) for (Object value : values(slot)) trigrams.get(position).add(entry, value);
    }

    private void removeFrom(int position, IndexedEntry<T> entry, Object slot, boolean index, boolean wordIndex, boolean trigramIndex) {
        if (index) for (Object value : values(slot)) indexes.get(position).remove(entry, value);
        if (wordIndex && words.get(position) != null) for (String word : words(position, slot)) words.get(position).remove(entry, word);
        if (trigramIndex && trigrams.get(position) != null) for (Object value : values(slot)) trigrams.get(position).remove(entry, value);
    }

    private void addWords(int position, IndexedEntry<T> entry, Object slot) {
        addTo(position, entry, slot, false, true, true);
    }

    private void removeWords(int position, IndexedEntry<T> entry, Object slot) {
        removeFrom(position, entry, slot, false, true, true);
    }

    public Object[] carry(IndexedEntry<T> entry, Object[] previous) {
        Object[] snapshot = new Object[dimensions.size()];
        for (int i = 0; i < snapshot.length; i++) {
            if (carriedFrom[i] >= 0) {
                snapshot[i] = previous[carriedFrom[i]];
            } else {
                snapshot[i] = slot(i, entry.entity());
                validate(i, snapshot[i]);
            }
        }
        return snapshot;
    }

    public void addFresh(IndexedEntry<T> entry, Object[] snapshot) {
        for (int i = 0; i < snapshot.length; i++) addTo(i, entry, snapshot[i], freshIndex[i], freshWords[i], freshTrigrams[i]);
    }

    public void removeFresh(IndexedEntry<T> entry, Object[] snapshot) {
        for (int i = 0; i < snapshot.length; i++) removeFrom(i, entry, snapshot[i], freshIndex[i], freshWords[i], freshTrigrams[i]);
    }

    private Object slot(int position, T entity) {
        Set<Object> values = dimensions.get(position).values(entity);
        return switch (values.size()) {
            case 0 -> null;
            case 1 -> values.iterator().next();
            default -> new Many(values.toArray());
        };
    }

    public Object[] extract(T entity) {
        if (dimensions.isEmpty()) return NO_VALUES;
        Object[] snapshot = new Object[dimensions.size()];
        for (int i = 0; i < snapshot.length; i++) snapshot[i] = slot(i, entity);
        return snapshot;
    }

    private static Object[] values(Object slot) {
        if (slot == null) return NO_VALUES;
        return slot instanceof Many many ? many.values() : new Object[] {slot};
    }

    public void validate(Object[] snapshot) {
        for (int i = 0; i < snapshot.length; i++) validate(i, snapshot[i]);
    }

    private void validate(int position, Object slot) {
        DimensionIndex<IndexedEntry<T>> index = indexes.get(position);
        Object first = null;
        for (Object value : values(slot)) {
            index.validate(value);
            // An empty RANGE index still needs homogeneous keys within a single PUT.
            if (index instanceof RangeIndex<?> && first != null) {
                @SuppressWarnings("unchecked")
                Comparable<Object> next = (Comparable<Object>) value;
                next.compareTo(first);
            }
            if (first == null) first = value;
        }
    }

    public void add(IndexedEntry<T> entry, Object[] snapshot) {
        if (recorder != null) recorder.accept(new Change<>(true, entry, snapshot));
        applyAdd(entry, snapshot);
    }

    private void numbered(IndexedEntry<T> entry) {
        if (entry.ordinal() < 0) entry.ordinal(ordinals.take());
    }

    private void unnumbered(IndexedEntry<T> entry) {
        int ordinal = entry.ordinal();
        if (ordinal < 0) return;
        entry.ordinal(-1);
        ordinals.give(ordinal);
    }

    public int ordinalBound() { return ordinals.bound(); }

    private void applyAdd(IndexedEntry<T> entry, Object[] snapshot) {
        numbered(entry);
        for (int i = 0; i < snapshot.length; i++) {
            Object slot = snapshot[i];
            if (slot instanceof Many many) {
                for (Object value : many.values()) indexes.get(i).add(entry, value);
            } else if (slot != null) {
                indexes.get(i).add(entry, slot);
            }
            addWords(i, entry, slot);
        }
    }

    public void remove(IndexedEntry<T> entry, Object[] snapshot) {
        if (recorder != null) recorder.accept(new Change<>(false, entry, snapshot));
        applyRemove(entry, snapshot);
    }

    private void applyRemove(IndexedEntry<T> entry, Object[] snapshot) {
        for (int i = 0; i < snapshot.length; i++) {
            Object slot = snapshot[i];
            if (slot instanceof Many many) {
                for (Object value : many.values()) indexes.get(i).remove(entry, value);
            } else if (slot != null) {
                indexes.get(i).remove(entry, slot);
            }
            removeWords(i, entry, slot);
        }
        unnumbered(entry);
    }

    public void replace(IndexedEntry<T> previous, Object[] before, IndexedEntry<T> entry, Object[] after) {
        if (recorder != null) {
            recorder.accept(new Change<>(false, previous, before));
            recorder.accept(new Change<>(true, entry, after));
        }
        if (before.length != after.length) {
            applyRemove(previous, before);
            applyAdd(entry, after);
            return;
        }
        numbered(entry);
        if (previous != entry) replaceIndexed(previous, before, entry, after);
        else replaceValues(previous, before, entry, after);
    }

    private void replaceIndexed(IndexedEntry<T> previous, Object[] before, IndexedEntry<T> entry, Object[] after) {
        replaceValues(previous, before, entry, after);
        unnumbered(previous);
    }

    private void replaceValues(IndexedEntry<T> previous, Object[] before, IndexedEntry<T> entry, Object[] after) {
        for (int i = 0; i < after.length; i++) {
            DimensionIndex<IndexedEntry<T>> index = indexes.get(i);
            if (sameValues(before[i], after[i])) {
                if (previous == entry) continue;
                if (after[i] instanceof Many many) {
                    for (Object value : many.values()) index.replace(previous, entry, value);
                } else if (after[i] != null) {
                    index.replace(previous, entry, after[i]);
                }
                PrefixTrieIndex<IndexedEntry<T>> wordIndex = words.get(i);
                if (wordIndex != null) for (String word : words(i, after[i])) wordIndex.replace(previous, entry, word);
                TrigramIndex<IndexedEntry<T>> pieces = trigrams.get(i);
                if (pieces != null) for (Object value : values(after[i])) pieces.replace(previous, entry, value);
            } else {
                removeWords(i, previous, before[i]);
                addWords(i, entry, after[i]);
                if (before[i] instanceof Many many) {
                    for (Object value : many.values()) index.remove(previous, value);
                } else if (before[i] != null) {
                    index.remove(previous, before[i]);
                }
                if (after[i] instanceof Many many) {
                    for (Object value : many.values()) index.add(entry, value);
                } else if (after[i] != null) {
                    index.add(entry, after[i]);
                }
            }
        }
    }

    private static boolean sameValues(Object before, Object after) {
        if (before instanceof Many a && after instanceof Many b) return Arrays.equals(a.values(), b.values());
        return Objects.equals(before, after);
    }

    public boolean multiValued(String path) {
        return dimensions.get(position(path)).multiValued();
    }

    public void forEachMatch(String path, Object[] values, Consumer<? super IndexedEntry<T>> action) {
        int position = position(path);
        if (values != null && values.length == 1 && values[0] instanceof Match.Words query) {
            matchWords(position, query, action);
            return;
        }
        if (values != null && values.length == 1 && values[0] instanceof Match.Fuzzy query) {
            matchFuzzy(position, query, action);
            return;
        }
        if (values != null && values.length == 1 && values[0] instanceof Match.Contains query) {
            matchContains(position, query, action);
            return;
        }
        indexes.get(position).forEachMatch(query(dimensions.get(position), values), action);
    }

    private void matchWords(int position, Match.Words query, Consumer<? super IndexedEntry<T>> action) {
        SearchDimension<T> dimension = dimensions.get(position);
        if (dimension.split() == null) throw undeclared(dimension.path(), "words", "prefixSplit");
        Set<String> wanted = dimension.split().words(query.text());
        if (wanted.isEmpty()) throw new IllegalArgumentException("A words search on " + dimension.path() + " needs at least one word");
        intersect(wanted, (word, each) -> words.get(position).forEachMatch(word, each), action);
    }

    private void intersect(Set<String> wanted, java.util.function.BiConsumer<String, Consumer<IndexedEntry<T>>> matches,
                           Consumer<? super IndexedEntry<T>> action) {
        java.util.BitSet found = null;
        List<IndexedEntry<T>> last = List.of();
        for (String word : wanted) {
            java.util.BitSet previous = found;
            java.util.BitSet now = new java.util.BitSet(ordinals.bound());
            List<IndexedEntry<T>> hits = new ArrayList<>();
            matches.accept(word, entry -> {
                int ordinal = entry.ordinal();
                if ((previous == null || previous.get(ordinal)) && !now.get(ordinal)) { now.set(ordinal); hits.add(entry); }
            });
            if (hits.isEmpty()) return;
            found = now;
            last = hits;
        }
        last.forEach(action);
    }

    private void distinct(Consumer<Consumer<IndexedEntry<T>>> matches, Consumer<? super IndexedEntry<T>> action) {
        java.util.BitSet seen = new java.util.BitSet(ordinals.bound());
        matches.accept(entry -> {
            int ordinal = entry.ordinal();
            if (!seen.get(ordinal)) { seen.set(ordinal); action.accept(entry); }
        });
    }

    public void requireRankable(String path) {
        SearchDimension<T> dimension = dimensions.get(position(path));
        if (dimension.kind() != IndexKind.PREFIX)
            throw new IllegalArgumentException("Only text dimensions are ranked, and '" + path + "' of " + specification.type().getName()
                + " is " + dimension.kind());
    }

    public Ranker<T> ranker(String path, Object[] values) {
        return new Ranker<>(dimensions.get(position(path)), values[0]);
    }

    private void matchFuzzy(int position, Match.Fuzzy query, Consumer<? super IndexedEntry<T>> action) {
        SearchDimension<T> dimension = dimensions.get(position);
        if (dimension.kind() != IndexKind.PREFIX) throw undeclared(dimension.path(), "fuzzy", "prefix or prefixSplit");
        if (dimension.split() == null) {
            String text = query.text().toLowerCase(Locale.ROOT);
            distinct(each -> ((PrefixTrieIndex<IndexedEntry<T>>) indexes.get(position)).forEachWithin(text, query.editsFor(text), each), action);
            return;
        }
        Set<String> wanted = dimension.split().words(query.text());
        if (wanted.isEmpty()) throw new IllegalArgumentException("A fuzzy search on " + dimension.path() + " needs at least one word");
        intersect(wanted, (word, each) -> words.get(position).forEachWithin(word, query.editsFor(word), each), action);
    }

    private void matchContains(int position, Match.Contains query, Consumer<? super IndexedEntry<T>> action) {
        SearchDimension<T> dimension = dimensions.get(position);
        if (!dimension.contains()) throw undeclared(dimension.path(), "contains", "prefixContains");
        String wanted = query.text().toLowerCase(Locale.ROOT);
        if (wanted.isEmpty()) throw new IllegalArgumentException("A contains search on " + dimension.path() + " needs at least one character");
        TrigramIndex<IndexedEntry<T>> pieces = trigrams.get(position);
        if (wanted.length() < TrigramIndex.LENGTH) {
            distinct(each -> pieces.forEachHoldingShort(wanted, each), action);
            pieces.forEachShortValue(entry -> { if (holds(dimension, entry, wanted)) action.accept(entry); });
            return;
        }
        List<List<IndexedEntry<T>>> groups = pieces.holding(wanted);
        if (groups.isEmpty()) return;
        if (groups.size() == 1) { groups.getFirst().forEach(action); return; }
        java.util.BitSet next = new java.util.BitSet(ordinals.bound());
        for (IndexedEntry<T> entry : groups.get(1)) next.set(entry.ordinal());
        for (IndexedEntry<T> candidate : groups.getFirst())
            if (next.get(candidate.ordinal()) && holds(dimension, candidate, wanted)) action.accept(candidate);
    }

    private static <T> boolean holds(SearchDimension<T> dimension, IndexedEntry<T> entry, String wanted) {
        for (Object value : dimension.values(entry.entity()))
            if (((String) value).toLowerCase(Locale.ROOT).contains(wanted)) return true;
        return false;
    }

    private UndeclaredSearchStrategyException undeclared(String path, String strategy, String declaration) {
        return new UndeclaredSearchStrategyException(specification.type(), path, strategy, declaration);
    }

    private int position(String path) {
        Integer position = positions.get(path);
        if (position == null) throw new io.github.zdcache.UnknownSearchDimensionException(specification.type(), path);
        return position;
    }

    private Object query(SearchDimension<?> dimension, Object[] values) {
        String path = dimension.path();
        if (dimension.kind() != IndexKind.COMPOUND) {
            if (values == null || values.length != 1) {
                throw new IllegalArgumentException(path + " takes 1 search value, got " +
                    (values == null ? 0 : values.length));
            }
            return queryValue(path, dimension.kind(), values[0]);
        }
        var parts = dimension.parts();
        if (values == null || values.length != parts.size()) {
            throw new IllegalArgumentException(path + " takes " + parts.size() + " search values " +
                parts.stream().map(SearchDimension.Part::name).toList() + ", got " +
                (values == null ? 0 : values.length));
        }
        List<Object> checked = new ArrayList<>(values.length);
        for (int i = 0; i < values.length; i++) {
            checked.add(queryValue(path + "." + parts.get(i).name(), parts.get(i).kind(), values[i]));
        }
        return List.copyOf(checked);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object queryValue(String path, IndexKind kind, Object value) {
        if (value == null) throw new IllegalArgumentException("Search value for " + path + " must not be null");
        if (value instanceof Match.Words) throw undeclared(path, "words", "prefixSplit");
        if (value instanceof Match.Fuzzy) throw undeclared(path, "fuzzy", "prefix or prefixSplit on a dimension of its own");
        if (value instanceof Match.Contains) throw undeclared(path, "contains", "prefixContains on a dimension of its own");
        if (value instanceof Match.Prefix prefix) {
            if (kind != IndexKind.PREFIX) throw undeclared(path, "prefix", "prefix");
            value = prefix.text();
        }
        switch (kind) {
            case PREFIX -> {
                if (!(value instanceof String)) {
                    throw new IllegalArgumentException("PREFIX search value must be a String at " + path);
                }
            }
            case RANGE -> {
                if (value instanceof ValueRange<?>) return value;
                if (!(value instanceof Comparable<?> point)) {
                    throw new IllegalArgumentException("RANGE search value must be a ValueRange or Comparable at " + path);
                }
                return ValueRange.between((Comparable) point, (Comparable) point);
            }
            default -> {
                if (value instanceof ValueRange<?>) {
                    throw new IllegalArgumentException("EXACT search value cannot be a ValueRange at " + path);
                }
            }
        }
        return value;
    }
}
