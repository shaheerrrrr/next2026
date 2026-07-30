package com.next2026.robotreset.opmode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.next2026.robotreset.resolve.NodeRef;
import com.next2026.robotreset.resolve.UiTree;

/**
 * Plain in-memory {@link UiTree} test double: a real (depth-first) scan of a
 * fixed tree of {@link FakeNodeRef}, so {@code findByViewId}/{@code
 * findByText} behave like the production tree scan for the scroll-container
 * lookup path in {@code OpModeSelector} (the path that does not go through
 * the faked {@code Resolver}).
 */
final class FakeUiTree implements UiTree {

    private final FakeNodeRef root;

    FakeUiTree(FakeNodeRef root) {
        this.root = root;
    }

    @Override
    public NodeRef getRoot() {
        return root;
    }

    @Override
    public List<NodeRef> findByViewId(String viewId) {
        List<NodeRef> out = new ArrayList<>();
        collect(root, out, node -> viewId != null && viewId.equals(node.viewId));
        return out;
    }

    @Override
    public List<NodeRef> findByText(CharSequence text) {
        List<NodeRef> out = new ArrayList<>();
        collect(root, out, node -> node.text != null
                && Objects.equals(node.text.toString(), text == null ? null : text.toString()));
        return out;
    }

    private interface Matcher {
        boolean matches(FakeNodeRef node);
    }

    private static void collect(FakeNodeRef node, List<NodeRef> out, Matcher matcher) {
        if (node == null) {
            return;
        }
        if (matcher.matches(node)) {
            out.add(node);
        }
        for (FakeNodeRef child : node.children) {
            collect(child, out, matcher);
        }
    }
}
