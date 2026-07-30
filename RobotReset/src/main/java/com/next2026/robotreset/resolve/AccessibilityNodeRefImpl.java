package com.next2026.robotreset.resolve;

import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Adapts a real Android {@link AccessibilityNodeInfo} to the {@link NodeRef}
 * contract. This is the production implementation the real on-device
 * {@code Resolver} (built in a later phase) runs against.
 */
final class AccessibilityNodeRefImpl implements NodeRef {

    private final AccessibilityNodeInfo node;

    AccessibilityNodeRefImpl(AccessibilityNodeInfo node) {
        this.node = node;
    }

    static NodeRef wrap(AccessibilityNodeInfo node) {
        return node == null ? null : new AccessibilityNodeRefImpl(node);
    }

    @Override
    public boolean isClickable() {
        return node.isClickable();
    }

    @Override
    public boolean isEnabled() {
        return node.isEnabled();
    }

    @Override
    public boolean isScrollable() {
        return node.isScrollable();
    }

    @Override
    public CharSequence getText() {
        return node.getText();
    }

    @Override
    public String getViewId() {
        return node.getViewIdResourceName();
    }

    @Override
    public int getChildCount() {
        return node.getChildCount();
    }

    @Override
    public NodeRef getChild(int index) {
        return wrap(node.getChild(index));
    }

    @Override
    public NodeRef getParent() {
        return wrap(node.getParent());
    }

    @Override
    public boolean click() {
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    @Override
    public boolean scrollForward() {
        return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
    }

    @Override
    public void recycle() {
        node.recycle();
    }
}
