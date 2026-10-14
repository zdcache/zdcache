package io.github.zdcache.index;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;

/** Compressed prefix trie; IDs are stored only at the node where a value ends. */
public final class PrefixTrieIndex<ID> implements DimensionIndex<ID> {
    private static final char[] NO_KEYS = new char[0];
    private static final Node[] NO_CHILDREN = new Node[0];

    private static final class Node {
        String edge;
        char[] keys = NO_KEYS;
        Node[] children = NO_CHILDREN;
        Object ids;

        Node(String edge) { this.edge = edge; }

        int slot(char key) { return Arrays.binarySearch(keys, key); }

        void insert(Node child) {
            int at = -slot(child.edge.charAt(0)) - 1;
            char[] nextKeys = new char[keys.length + 1];
            Node[] nextChildren = new Node[children.length + 1];
            System.arraycopy(keys, 0, nextKeys, 0, at);
            System.arraycopy(children, 0, nextChildren, 0, at);
            nextKeys[at] = child.edge.charAt(0);
            nextChildren[at] = child;
            System.arraycopy(keys, at, nextKeys, at + 1, keys.length - at);
            System.arraycopy(children, at, nextChildren, at + 1, children.length - at);
            keys = nextKeys;
            children = nextChildren;
        }

        void delete(int at) {
            if (children.length == 1) {
                keys = NO_KEYS;
                children = NO_CHILDREN;
                return;
            }
            char[] nextKeys = new char[keys.length - 1];
            Node[] nextChildren = new Node[children.length - 1];
            System.arraycopy(keys, 0, nextKeys, 0, at);
            System.arraycopy(children, 0, nextChildren, 0, at);
            System.arraycopy(keys, at + 1, nextKeys, at, keys.length - at - 1);
            System.arraycopy(children, at + 1, nextChildren, at, children.length - at - 1);
            keys = nextKeys;
            children = nextChildren;
        }

        void absorbOnlyChild() {
            Node child = children[0];
            edge = edge + child.edge;
            keys = child.keys;
            children = child.children;
            ids = child.ids;
        }
    }

    private final Node root = new Node("");

    private static String normalized(Object value) {
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException("Prefix indexes require strings");
        }
        return s.toLowerCase(Locale.ROOT);
    }

    private static int common(String edge, String text, int from) {
        int max = Math.min(edge.length(), text.length() - from);
        int i = 0;
        while (i < max && edge.charAt(i) == text.charAt(from + i)) i++;
        return i;
    }

    @Override public void add(ID id, Object value) {
        String text = normalized(value);
        Node node = root;
        int i = 0;
        while (i < text.length()) {
            int slot = node.slot(text.charAt(i));
            if (slot < 0) {
                Node leaf = new Node(text.substring(i));
                leaf.ids = id;
                node.insert(leaf);
                return;
            }
            Node child = node.children[slot];
            int shared = common(child.edge, text, i);
            if (shared < child.edge.length()) {
                Node split = new Node(child.edge.substring(0, shared));
                child.edge = child.edge.substring(shared);
                split.insert(child);
                node.children[slot] = split;
                child = split;
            }
            node = child;
            i += shared;
        }
        node.ids = Ids.add(node.ids, id);
    }

    @Override public void remove(ID id, Object value) {
        String text = normalized(value);
        Node parent = null;
        int parentSlot = -1;
        Node node = root;
        int i = 0;
        while (i < text.length()) {
            int slot = node.slot(text.charAt(i));
            if (slot < 0) return;
            Node child = node.children[slot];
            if (!text.startsWith(child.edge, i)) return;
            parent = node;
            parentSlot = slot;
            node = child;
            i += child.edge.length();
        }
        node.ids = Ids.remove(node.ids, id);
        if (node == root || node.ids != null) return;
        if (node.children.length == 0) {
            parent.delete(parentSlot);
            if (parent != root && parent.ids == null && parent.children.length == 1) parent.absorbOnlyChild();
        } else if (node.children.length == 1) {
            node.absorbOnlyChild();
        }
    }

    @Override public void replace(ID id, ID replacement, Object value) {
        String text = normalized(value);
        Node node = root;
        int i = 0;
        while (i < text.length()) {
            int slot = node.slot(text.charAt(i));
            Node child = slot < 0 ? null : node.children[slot];
            if (child == null || !text.startsWith(child.edge, i)) {
                add(replacement, value);
                return;
            }
            node = child;
            i += child.edge.length();
        }
        node.ids = Ids.replace(node.ids, id, replacement);
    }

    @Override public boolean isEmpty() { return root.ids == null && root.children.length == 0; }

    @Override public void forEachMatch(Object query, Consumer<? super ID> action) {
        String text = normalized(query);
        Node node = root;
        int i = 0;
        while (i < text.length()) {
            int slot = node.slot(text.charAt(i));
            if (slot < 0) return;
            Node child = node.children[slot];
            int shared = common(child.edge, text, i);
            if (i + shared == text.length()) {
                node = child;
                break;
            }
            if (shared < child.edge.length()) return;
            node = child;
            i += shared;
        }
        ArrayDeque<Node> pending = new ArrayDeque<>();
        pending.push(node);
        while (!pending.isEmpty()) {
            Node next = pending.pop();
            Ids.forEach(next.ids, action);
            for (Node child : next.children) pending.push(child);
        }
    }
}
