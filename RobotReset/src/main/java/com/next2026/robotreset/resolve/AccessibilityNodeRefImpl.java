package com.next2026.robotreset.resolve;

import android.graphics.Rect;
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
    public int[] getBoundsInScreen() {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        return new int[] { bounds.left, bounds.top, bounds.right, bounds.bottom };
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
        // Real touches (and TalkBack) always place accessibility focus on a
        // node before clicking it. On some Android versions/OEM widget
        // implementations -- observed on Android 8.0 -- a list row's real
        // click/selection logic is wired to focus state rather than purely
        // to the AdapterView position lookup ACTION_CLICK triggers on its
        // own, so ACTION_CLICK alone can report success (and even dismiss a
        // dialog) without the app's underlying selection actually taking
        // effect. Focusing first costs nothing on devices that don't need
        // it (a harmless no-op / already-focused false return) and matches
        // what a real touch does everywhere else.
        node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
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
