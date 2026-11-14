package io.github.zdcache;

import java.util.Objects;

/** A search result together with how well it matched; see {@link RankLevel}. */
public record Ranked<T>(T result, RankLevel rankLevel) {
    public Ranked {
        Objects.requireNonNull(result);
        Objects.requireNonNull(rankLevel);
    }
}
