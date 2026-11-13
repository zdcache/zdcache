package io.github.zdcache;

import java.util.Objects;

public record Ranked<T>(T result, RankLevel rankLevel) {
    public Ranked {
        Objects.requireNonNull(result);
        Objects.requireNonNull(rankLevel);
    }
}
