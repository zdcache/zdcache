package io.github.zdcache.internal;

import io.github.zdcache.Match;
import io.github.zdcache.RankLevel;
import io.github.zdcache.SearchDimension;
import io.github.zdcache.Split;
import io.github.zdcache.index.EditDistance;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class Ranker<T> {
    private final SearchDimension<T> dimension;
    private final Match query;
    private final Split split;
    private final String lower;
    private final String wanted;
    private final List<String> words;
    private final RankLevel worst;

    Ranker(SearchDimension<T> dimension, Object query) {
        this.dimension = dimension;
        this.query = query instanceof Match match ? match : Match.prefix((String) query);
        this.split = dimension.split() == null ? Split.WHITESPACE : dimension.split();
        this.lower = this.query.text().toLowerCase(Locale.ROOT);
        this.wanted = normalized(this.query.text());
        this.words = List.copyOf(split.words(this.query.text()));
        this.worst = this.query instanceof Match.Fuzzy ? RankLevel.TWO_EDITS
            : this.query instanceof Match.Contains ? RankLevel.CONTAINS : RankLevel.WORDS_IN_ANY_ORDER;
    }

    public RankLevel rank(T entity) {
        RankLevel best = worst;
        for (Object value : dimension.values(entity)) {
            RankLevel level = rank((String) value);
            if (level != null && level.compareTo(best) < 0) best = level;
        }
        return best;
    }

    private RankLevel rank(String value) {
        if (query instanceof Match.Contains) {
            if (!value.toLowerCase(Locale.ROOT).contains(lower)) return null;
            RankLevel level = withoutTypos(value);
            return level == RankLevel.WORDS_IN_ANY_ORDER ? RankLevel.CONTAINS : level;
        }
        if (!(query instanceof Match.Fuzzy fuzzy)) return withoutTypos(value);
        int edits = dimension.split() == null ? wholeEdits(value, fuzzy) : wordEdits(value, fuzzy);
        if (edits == 0) return withoutTypos(value);
        if (edits == 1) return RankLevel.ONE_EDIT;
        return edits == 2 ? RankLevel.TWO_EDITS : null;
    }

    private RankLevel withoutTypos(String value) {
        String whole = normalized(value);
        if (whole.equals(wanted)) return RankLevel.EXACT;
        if (whole.startsWith(wanted)) return RankLevel.STARTS_WITH;
        List<String> found = List.copyOf(split.words(value));
        int next = 0;
        for (String word : words) {
            while (next < found.size() && !found.get(next).startsWith(word)) next++;
            if (next == found.size()) return RankLevel.WORDS_IN_ANY_ORDER;
            next++;
        }
        return RankLevel.WORDS_IN_ORDER;
    }

    private int wholeEdits(String value, Match.Fuzzy fuzzy) {
        int edits = EditDistance.between(value.toLowerCase(Locale.ROOT), lower);
        return edits <= fuzzy.editsFor(lower) ? edits : Integer.MAX_VALUE;
    }

    private int wordEdits(String value, Match.Fuzzy fuzzy) {
        Set<String> found = split.words(value);
        int most = 0;
        for (String word : words) {
            int closest = Integer.MAX_VALUE;
            for (String candidate : found) closest = Math.min(closest, EditDistance.between(candidate, word));
            if (closest > fuzzy.editsFor(word)) return Integer.MAX_VALUE;
            most = Math.max(most, closest);
        }
        return most;
    }

    static String normalized(String text) {
        StringBuilder normalized = new StringBuilder(text.length());
        boolean space = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                space = !normalized.isEmpty();
                continue;
            }
            if (space) normalized.append(' ');
            space = false;
            normalized.append(c);
        }
        return normalized.toString().toLowerCase(Locale.ROOT);
    }
}
