package io.github.zdcache.index;

public final class EditDistance {
    private EditDistance() { }

    public static int between(String a, String b) {
        int[] beforePrevious = null, previous = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] row = next(previous, beforePrevious, i, a.charAt(i - 1), i > 1 ? a.charAt(i - 2) : 0, b);
            beforePrevious = previous;
            previous = row;
        }
        return previous[b.length()];
    }

    static int[] next(int[] previous, int[] beforePrevious, int position, char c, char before, String query) {
        int[] row = new int[query.length() + 1];
        row[0] = position;
        for (int j = 1; j <= query.length(); j++) {
            char wanted = query.charAt(j - 1);
            int edits = Math.min(Math.min(previous[j] + 1, row[j - 1] + 1), previous[j - 1] + (wanted == c ? 0 : 1));
            if (beforePrevious != null && j > 1 && wanted == before && query.charAt(j - 2) == c) edits = Math.min(edits, beforePrevious[j - 2] + 1);
            row[j] = edits;
        }
        return row;
    }
}
