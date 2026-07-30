package com.next2026.robotreset.resolve;

import java.util.List;

/**
 * Platform-agnostic view over an entire on-screen UI tree at a point in time.
 */
public interface UiTree {

    /** Returns the root node, or null if there is no active window. */
    NodeRef getRoot();

    List<NodeRef> findByViewId(String viewId);

    List<NodeRef> findByText(CharSequence text);
}
