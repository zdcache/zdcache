package io.github.zdcache.index;

import java.util.HashSet;
import java.util.function.Consumer;

final class Ids {
    private static final class Many extends HashSet<Object> {
        Many(Object first, Object second) {
            super(4);
            add(first);
            add(second);
        }
    }

    private Ids() { }

    static Object add(Object holder, Object id) {
        if (holder == null) return id;
        if (holder instanceof Many many) {
            many.add(id);
            return many;
        }
        return holder.equals(id) ? holder : new Many(holder, id);
    }

    static Object remove(Object holder, Object id) {
        if (holder instanceof Many many) {
            many.remove(id);
            return many.size() == 1 ? many.iterator().next() : many;
        }
        return holder != null && holder.equals(id) ? null : holder;
    }

    @SuppressWarnings("unchecked")
    static <ID> void forEach(Object holder, Consumer<? super ID> action) {
        if (holder == null) return;
        if (holder instanceof Many many) {
            for (Object id : many) action.accept((ID) id);
        } else {
            action.accept((ID) holder);
        }
    }
}
