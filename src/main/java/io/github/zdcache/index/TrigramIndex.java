package io.github.zdcache.index;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class TrigramIndex<ID> implements DimensionIndex<ID> {
    public static final int LENGTH = 3;

    private final Map<String, Object> idsByTrigram = new HashMap<>();
    private Object shortValues;

    public static Set<String> trigrams(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        Set<String> trigrams = new LinkedHashSet<>();
        for (int i = 0; i + LENGTH <= lower.length(); i++) trigrams.add(lower.substring(i, i + LENGTH));
        return trigrams;
    }

    @Override public void add(ID id, Object value) {
        if (((String) value).length() < LENGTH) { shortValues = Ids.add(shortValues, id); return; }
        for (String trigram : trigrams((String) value)) idsByTrigram.compute(trigram, (ignored, ids) -> Ids.add(ids, id));
    }

    @Override public void remove(ID id, Object value) {
        if (((String) value).length() < LENGTH) { shortValues = Ids.remove(shortValues, id); return; }
        for (String trigram : trigrams((String) value)) idsByTrigram.computeIfPresent(trigram, (ignored, ids) -> Ids.remove(ids, id));
    }

    @Override public void replace(ID id, ID replacement, Object value) {
        if (((String) value).length() < LENGTH) { shortValues = Ids.replace(shortValues, id, replacement); return; }
        for (String trigram : trigrams((String) value)) idsByTrigram.computeIfPresent(trigram, (ignored, ids) -> Ids.replace(ids, id, replacement));
    }

    @Override public boolean isEmpty() { return idsByTrigram.isEmpty() && shortValues == null; }

    /** The ids of the values holding every piece of the text, smallest group first; empty when a piece is missing. */
    public List<List<ID>> holding(String text) {
        List<List<ID>> groups = new ArrayList<>();
        for (String trigram : trigrams(text)) {
            Object ids = idsByTrigram.get(trigram);
            if (ids == null) return List.of();
            List<ID> group = new ArrayList<>(Ids.size(ids));
            Ids.<ID>forEach(ids, group::add);
            groups.add(group);
        }
        groups.sort((a, b) -> Integer.compare(a.size(), b.size()));
        return groups;
    }

    /** Every id of a value with a piece containing this short text, possibly more than once. */
    public void forEachHoldingShort(String text, Consumer<? super ID> action) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Object> entry : idsByTrigram.entrySet())
            if (entry.getKey().contains(lower)) Ids.forEach(entry.getValue(), action);
    }

    /** The ids of values shorter than a piece, which no piece holds. */
    public void forEachShortValue(Consumer<? super ID> action) { Ids.forEach(shortValues, action); }

    @Override public void forEachMatch(Object query, Consumer<? super ID> action) {
        Set<String> wanted = trigrams((String) query);
        if (wanted.isEmpty()) throw new IllegalArgumentException("A trigram search needs at least " + LENGTH + " characters");
        List<Object> sets = new ArrayList<>();
        for (String trigram : wanted) {
            Object ids = idsByTrigram.get(trigram);
            if (ids == null) return;
            sets.add(ids);
        }
        sets.sort((a, b) -> Integer.compare(Ids.size(a), Ids.size(b)));
        Set<ID> found = new LinkedHashSet<>();
        Ids.<ID>forEach(sets.getFirst(), found::add);
        for (int i = 1; i < sets.size() && !found.isEmpty(); i++) {
            Set<ID> next = new LinkedHashSet<>();
            Object ids = sets.get(i);
            for (ID id : found) if (Ids.contains(ids, id)) next.add(id);
            found = next;
        }
        found.forEach(action);
    }
}
