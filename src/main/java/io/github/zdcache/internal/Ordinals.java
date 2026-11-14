package io.github.zdcache.internal;

import java.util.Arrays;

final class Ordinals {
    private int next;
    private int[] free = new int[16];
    private int freeCount;

    synchronized int take() { return freeCount > 0 ? free[--freeCount] : next++; }

    synchronized void give(int ordinal) {
        if (freeCount == free.length) free = Arrays.copyOf(free, freeCount * 2);
        free[freeCount++] = ordinal;
    }

    synchronized int bound() { return next; }
}
