package io.github.zdcache;

import java.util.List;
import java.util.Objects;

/** The best results of a {@code searchBest}, best first, and how many results there are in total. */
public record RankedPage<T>(List<Ranked<T>> results, int total) {
    public RankedPage {
        results = List.copyOf(Objects.requireNonNull(results));
    }
}
