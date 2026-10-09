package io.github.zdcache.index;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Prefix Trie. Per-node reference counts support multiple matching values for one root ID. */
public final class PrefixTrieIndex<ID> implements DimensionIndex<ID> {
    private final Node<ID> root = new Node<>();

    private static final class Node<ID> {
        final Map<Character, Node<ID>> children = new HashMap<>();
        final Map<ID, Integer> counts = new HashMap<>();
    }

    private static String normalized(Object value) {
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException("Prefix indexes require strings");
        }
        return s.toLowerCase(Locale.ROOT);
    }

    @Override public void add(ID id, Object value) {
        String text = normalized(value);
        Node<ID> node = root;
        increment(node, id);
        for (int i = 0; i < text.length(); i++) {
            node = node.children.computeIfAbsent(text.charAt(i), ignored -> new Node<>());
            increment(node, id);
        }
    }

    private void increment(Node<ID> node, ID id) {
        node.counts.merge(id, 1, Integer::sum);
    }

    @Override public void remove(ID id, Object value) {
        String text = normalized(value);
        Node<ID> node = root;
        if (!node.counts.containsKey(id)) return;
        decrement(node, id);
        for (int i = 0; i < text.length(); i++) {
            Node<ID> child = node.children.get(text.charAt(i));
            if (child == null) return;
            decrement(child, id);
            if (child.counts.isEmpty()) {
                node.children.remove(text.charAt(i));
                return;
            }
            node = child;
        }
    }

    private void decrement(Node<ID> node, ID id) {
        node.counts.computeIfPresent(id, (key, count) -> count == 1 ? null : count - 1);
    }

    @Override public boolean isEmpty() { return root.counts.isEmpty(); }

    @Override public Set<ID> search(Object query) {
        String text = normalized(query);
        Node<ID> node = root;
        for (int i = 0; i < text.length(); i++) {
            node = node.children.get(text.charAt(i));
            if (node == null) return Set.of();
        }
        return new LinkedHashSet<>(node.counts.keySet());
    }
}
