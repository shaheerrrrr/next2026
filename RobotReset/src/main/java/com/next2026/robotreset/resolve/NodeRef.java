package com.next2026.robotreset.resolve;

/**
 * Platform-agnostic view over a single node in a UI tree. A real implementation
 * wraps an Android {@code AccessibilityNodeInfo}; a fake implementation (built
 * in a later phase) wraps a plain in-memory tree so the resolution engine can
 * be unit-tested on the JVM without an emulator or device.
 */
public interface NodeRef {

    boolean isClickable();

    boolean isEnabled();

    boolean isScrollable();

    CharSequence getText();

    /** Resource-id string form, e.g. "com.qualcomm.ftcdriverstation:id/init". May return null. */
    String getViewId();

    int getChildCount();

    NodeRef getChild(int index);

    /** Returns null at the root. */
    NodeRef getParent();

    /** Performs the click. Returns whether the underlying action succeeded. */
    boolean click();

    /** Performs ACTION_SCROLL_FORWARD. Returns whether it succeeded. */
    boolean scrollForward();

    /** Releases underlying platform resources. No-op for fakes. */
    void recycle();
}
