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
            String lowerValue = value.toLowerCase(Locale.ROOT);
            if (!lowerValue.contains(lower)) return null;
            RankLevel level = withoutTypos(lowerValue);
            return level == RankLevel.WORDS_IN_ANY_ORDER ? RankLevel.CONTAINS : level;
        }
        if (!(query instanceof Match.Fuzzy fuzzy)) return withoutTypos(value.toLowerCase(Locale.ROOT));
        int edits = dimension.split() == null ? wholeEdits(value, fuzzy) : wordEdits(value, fuzzy);
        if (edits == 0) return withoutTypos(value.toLowerCase(Locale.ROOT));
        if (edits == 1) return RankLevel.ONE_EDIT;
        return edits == 2 ? RankLevel.TWO_EDITS : null;
    }

    private RankLevel withoutTypos(String lowerValue) {
        RankLevel whole = whole(lowerValue);
        if (whole != null) return whole;
        int[] starts = new int[8], ends = new int[8];
        int count = 0;
        int start = -1;
        for (int i = 0; i <= lowerValue.length(); ) {
            int codePoint = i < lowerValue.length() ? lowerValue.codePointAt(i) : ' ';
            boolean separator = i == lowerValue.length() || split.separates(codePoint);
            if (separator && start >= 0) {
                if (!repeated(lowerValue, starts, ends, count, start, i)) {
                    if (count == starts.length) { starts = java.util.Arrays.copyOf(starts, count * 2); ends = java.util.Arrays.copyOf(ends, count * 2); }
                    starts[count] = start;
                    ends[count++] = i;
                }
                start = -1;
            } else if (!separator && start < 0) start = i;
            i += i < lowerValue.length() ? Character.charCount(codePoint) : 1;
        }
        int next = 0;
        for (String word : words) {
            while (next < count && !(ends[next] - starts[next] >= word.length() && lowerValue.startsWith(word, starts[next]))) next++;
            if (next == count) return RankLevel.WORDS_IN_ANY_ORDER;
            next++;
        }
        return RankLevel.WORDS_IN_ORDER;
    }

    private static boolean repeated(String text, int[] starts, int[] ends, int count, int start, int end) {
        for (int i = 0; i < count; i++)
            if (ends[i] - starts[i] == end - start && text.regionMatches(starts[i], text, start, end - start)) return true;
        return false;
    }

    private RankLevel whole(String lowerValue) {
        int at = 0, length = lowerValue.length();
        while (at < length && Character.isWhitespace(lowerValue.charAt(at))) at++;
        for (int i = 0; i < wanted.length(); i++) {
            char c = wanted.charAt(i);
            if (c == ' ') {
                if (at >= length || !Character.isWhitespace(lowerValue.charAt(at))) return null;
                while (at < length && Character.isWhitespace(lowerValue.charAt(at))) at++;
                if (at >= length) return null;
            } else {
                if (at >= length || lowerValue.charAt(at) != c) return null;
                at++;
            }
        }
        while (at < length && Character.isWhitespace(lowerValue.charAt(at))) at++;
        return at == length ? RankLevel.EXACT : RankLevel.STARTS_WITH;
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
