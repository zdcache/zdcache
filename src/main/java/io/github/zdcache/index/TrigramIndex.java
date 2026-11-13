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

    public static Set<String> trigrams(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        Set<String> trigrams = new LinkedHashSet<>();
        for (int i = 0; i + LENGTH <= lower.length(); i++) trigrams.add(lower.substring(i, i + LENGTH));
        return trigrams;
    }

    @Override public void add(ID id, Object value) {
        for (String trigram : trigrams((String) value)) idsByTrigram.compute(trigram, (ignored, ids) -> Ids.add(ids, id));
    }

    @Override public void remove(ID id, Object value) {
        for (String trigram : trigrams((String) value)) idsByTrigram.computeIfPresent(trigram, (ignored, ids) -> Ids.remove(ids, id));
    }

    @Override public void replace(ID id, ID replacement, Object value) {
        for (String trigram : trigrams((String) value)) idsByTrigram.computeIfPresent(trigram, (ignored, ids) -> Ids.replace(ids, id, replacement));
    }

    @Override public boolean isEmpty() { return idsByTrigram.isEmpty(); }

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
