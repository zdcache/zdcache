package io.github.zdcache.index;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdsTest {
    record Colliding(int value) {
        @Override public int hashCode() { return value % 3; }
    }

    private static Set<Object> members(Object holder) {
        List<Object> all = new ArrayList<>();
        Ids.forEach(holder, all::add);
        Set<Object> unique = new HashSet<>(all);
        assertEquals(all.size(), unique.size(), "a member was visited twice");
        return unique;
    }

    private static void matchesASetUnderRandomChanges(java.util.function.IntFunction<Object> id, int distinct) {
        Random random = new Random(11);
        Object holder = null;
        Set<Object> model = new HashSet<>();
        for (int step = 0; step < 20_000; step++) {
            Object chosen = id.apply(random.nextInt(distinct));
            switch (random.nextInt(3)) {
                case 0 -> {
                    holder = Ids.add(holder, chosen);
                    model.add(chosen);
                }
                case 1 -> {
                    holder = Ids.remove(holder, chosen);
                    model.remove(chosen);
                }
                default -> {
                    Object replacement = id.apply(random.nextInt(distinct));
                    if (model.contains(chosen)) {
                        holder = Ids.replace(holder, chosen, replacement);
                        model.remove(chosen);
                        model.add(replacement);
                    }
                }
            }
            assertEquals(model, members(holder), "step " + step);
            if (model.isEmpty()) assertNull(holder);
            if (model.size() == 1) assertEquals(model.iterator().next(), holder);
        }
    }

    @Test void behavesLikeASetAsGroupsGrowAndShrink() {
        matchesASetUnderRandomChanges(Integer::valueOf, 300);
    }

    @Test void behavesLikeASetWhenEveryIdCollides() {
        matchesASetUnderRandomChanges(Colliding::new, 60);
    }

    @Test void membersAreComparedByEquality() {
        Object holder = Ids.add(null, new Colliding(1));
        holder = Ids.add(holder, new Colliding(2));
        holder = Ids.add(holder, new Colliding(1));
        assertEquals(Set.of(new Colliding(1), new Colliding(2)), members(holder));
        holder = Ids.remove(holder, new Colliding(2));
        assertEquals(new Colliding(1), holder);
    }

    @Test void largeGroupsKeepEveryMember() {
        Object holder = null;
        for (int i = 0; i < 10_000; i++) holder = Ids.add(holder, i);
        for (int i = 0; i < 10_000; i += 2) holder = Ids.remove(holder, i);
        Set<Object> expected = new HashSet<>();
        for (int i = 1; i < 10_000; i += 2) expected.add(i);
        assertEquals(expected, members(holder));
    }
}
