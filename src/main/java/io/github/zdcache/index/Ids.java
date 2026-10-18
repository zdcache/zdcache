package io.github.zdcache.index;

import java.util.Arrays;
import java.util.function.Consumer;

final class Ids {
    private static final class Many {
        private Object[] items = new Object[4];
        private int[] slots = new int[8];
        private int size;

        Many(Object first, Object second) {
            add(first);
            add(second);
        }

        int size() { return size; }

        Object only() { return items[0]; }

        void add(Object id) {
            if (find(id) >= 0) return;
            if (size == items.length) items = Arrays.copyOf(items, size * 2);
            if ((size + 1) * 2 > slots.length) rehash(slots.length * 2);
            items[size] = id;
            slots[free(id)] = ++size;
        }

        void remove(Object id) {
            int slot = find(id);
            if (slot < 0) return;
            int position = slots[slot] - 1;
            delete(slot);
            int last = --size;
            if (position != last) {
                Object moved = items[last];
                items[position] = moved;
                slots[find(moved)] = position + 1;
            }
            items[last] = null;
        }

        <ID> void forEach(Consumer<? super ID> action) {
            Object[] all = items;
            for (int i = 0, n = size; i < n; i++) {
                @SuppressWarnings("unchecked")
                ID id = (ID) all[i];
                action.accept(id);
            }
        }

        private int find(Object id) {
            int mask = slots.length - 1;
            for (int slot = hash(id) & mask; slots[slot] != 0; slot = (slot + 1) & mask) {
                if (items[slots[slot] - 1].equals(id)) return slot;
            }
            return -1;
        }

        private int free(Object id) {
            int mask = slots.length - 1;
            int slot = hash(id) & mask;
            while (slots[slot] != 0) slot = (slot + 1) & mask;
            return slot;
        }

        private void delete(int slot) {
            int mask = slots.length - 1;
            int gap = slot;
            for (int next = (gap + 1) & mask; slots[next] != 0; next = (next + 1) & mask) {
                int home = hash(items[slots[next] - 1]) & mask;
                if (((next - home) & mask) >= ((next - gap) & mask)) {
                    slots[gap] = slots[next];
                    gap = next;
                }
            }
            slots[gap] = 0;
        }

        private void rehash(int capacity) {
            slots = new int[capacity];
            for (int i = 0; i < size; i++) slots[free(items[i])] = i + 1;
        }

        private static int hash(Object id) {
            int h = id.hashCode() * 0x9E3779B9;
            return h ^ (h >>> 16);
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

    static Object replace(Object holder, Object id, Object replacement) {
        if (holder instanceof Many many) {
            many.remove(id);
            many.add(replacement);
            return many;
        }
        return holder != null && holder.equals(id) ? replacement : add(holder, replacement);
    }

    static Object remove(Object holder, Object id) {
        if (holder instanceof Many many) {
            many.remove(id);
            return many.size() == 1 ? many.only() : many;
        }
        return holder != null && holder.equals(id) ? null : holder;
    }

    @SuppressWarnings("unchecked")
    static <ID> void forEach(Object holder, Consumer<? super ID> action) {
        if (holder == null) return;
        if (holder instanceof Many many) {
            many.forEach(action);
        } else {
            action.accept((ID) holder);
        }
    }
}
