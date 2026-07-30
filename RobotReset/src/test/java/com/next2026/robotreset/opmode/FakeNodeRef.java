package com.next2026.robotreset.opmode;

import java.util.ArrayList;
import java.util.List;

import com.next2026.robotreset.resolve.NodeRef;

/**
 * Minimal in-memory {@link NodeRef} test double. Not shared with other
 * lanes' test support on purpose (see brief) — this is intentionally small.
 */
final class FakeNodeRef implements NodeRef {

    String viewId;
    CharSequence text;
    boolean clickable;
    boolean enabled = true;
    boolean scrollable;
    final List<FakeNodeRef> children = new ArrayList<>();

    int clickCalls;
    int scrollForwardCalls;

    static FakeNodeRef scrollableContainer(String viewId) {
        FakeNodeRef n = new FakeNodeRef();
        n.viewId = viewId;
        n.scrollable = true;
        return n;
    }

    static FakeNodeRef plain() {
        return new FakeNodeRef();
    }

    FakeNodeRef addChild(FakeNodeRef child) {
        children.add(child);
        return this;
    }

    @Override
    public boolean isClickable() {
        return clickable;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean isScrollable() {
        return scrollable;
    }

    @Override
    public CharSequence getText() {
        return text;
    }

    @Override
    public String getViewId() {
        return viewId;
    }

    @Override
    public int getChildCount() {
        return children.size();
    }

    @Override
    public NodeRef getChild(int index) {
        return children.get(index);
    }

    @Override
    public NodeRef getParent() {
        return null;
    }

    @Override
    public boolean click() {
        clickCalls++;
        return true;
    }

    @Override
    public boolean scrollForward() {
        scrollForwardCalls++;
        return true;
    }

    @Override
    public void recycle() {
        // no-op
    }
}
