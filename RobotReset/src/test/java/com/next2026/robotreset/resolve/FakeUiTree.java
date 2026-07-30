package com.next2026.robotreset.resolve;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain in-memory {@link UiTree} for JVM unit tests. Scans the tree fresh
 * from the root on every call, mirroring {@code AccessibilityUiTree}'s "no
 * stale tree, every call is a live re-scan" semantics.
 *
 * <p>{@link #findByText} does a case-insensitive substring match, matching
 * the real behavior of Android's {@code findAccessibilityNodeInfosByText}
 * (see {@code AccessibilityUiTree}) so tests exercise the resolver the same
 * way the production adapter would.
 */
public final class FakeUiTree implements UiTree {

    private final FakeNodeRef root;

    public FakeUiTree(FakeNodeRef root) {
        this.root = root;
    }

    @Override
    public NodeRef getRoot() {
        return root;
    }

    @Override
    public List<NodeRef> findByViewId(String viewId) {
        List<NodeRef> result = new ArrayList<>();
        if (viewId != null) {
            collectByViewId(root, viewId, result);
        }
        return result;
    }

    @Override
    public List<NodeRef> findByText(CharSequence text) {
        List<NodeRef> result = new ArrayList<>();
        if (text != null && root != null) {
            collectByText(root, text.toString().toLowerCase(), result);
        }
        return result;
    }

    private void collectByViewId(FakeNodeRef node, String viewId, List<NodeRef> out) {
        if (node == null) {
            return;
        }
        if (viewId.equals(node.getViewId())) {
            out.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectByViewId((FakeNodeRef) node.getChild(i), viewId, out);
        }
    }

    private void collectByText(FakeNodeRef node, String needleLower, List<NodeRef> out) {
        if (node == null) {
            return;
        }
        CharSequence t = node.getText();
        if (t != null && t.toString().toLowerCase().contains(needleLower)) {
            out.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectByText((FakeNodeRef) node.getChild(i), needleLower, out);
        }
    }
}
