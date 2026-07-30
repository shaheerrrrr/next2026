package com.next2026.robotreset.resolve;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain in-memory {@link NodeRef} for JVM unit tests. No Android framework
 * dependency. Supports building parent/child trees via {@link #addChild},
 * and (for the pathological-chain test) directly wiring an arbitrary parent
 * via {@link #setParent}, which allows constructing a cyclic ancestor chain
 * that could never occur in a real Android view tree but which the resolver
 * must still not hang on.
 */
public final class FakeNodeRef implements NodeRef {

    private final String label;
    private String viewId;
    private CharSequence text;
    private boolean clickable;
    private boolean enabled = true;
    private boolean scrollable;
    private boolean clickReturnValue = true;
    private boolean scrollReturnValue = true;

    private FakeNodeRef parent;
    private final List<FakeNodeRef> children = new ArrayList<>();

    public int clickCount = 0;
    public boolean scrolledForward = false;
    public boolean recycled = false;

    public FakeNodeRef(String label) {
        this.label = label;
    }

    public FakeNodeRef viewId(String viewId) {
        this.viewId = viewId;
        return this;
    }

    public FakeNodeRef text(String text) {
        this.text = text;
        return this;
    }

    public FakeNodeRef clickable(boolean value) {
        this.clickable = value;
        return this;
    }

    public FakeNodeRef enabled(boolean value) {
        this.enabled = value;
        return this;
    }

    public FakeNodeRef scrollable(boolean value) {
        this.scrollable = value;
        return this;
    }

    public FakeNodeRef clickReturns(boolean value) {
        this.clickReturnValue = value;
        return this;
    }

    /** Adds {@code child} to this node's children and sets its parent to this node. */
    public FakeNodeRef addChild(FakeNodeRef child) {
        child.parent = this;
        children.add(child);
        return this;
    }

    /** Directly wires this node's parent pointer, bypassing addChild's bookkeeping. */
    public void setParent(FakeNodeRef parent) {
        this.parent = parent;
    }

    public boolean wasClicked() {
        return clickCount > 0;
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
        return parent;
    }

    @Override
    public boolean click() {
        clickCount++;
        return clickReturnValue;
    }

    @Override
    public boolean scrollForward() {
        scrolledForward = true;
        return scrollReturnValue;
    }

    @Override
    public void recycle() {
        recycled = true;
    }

    @Override
    public String toString() {
        return "FakeNodeRef{" + label + "}";
    }
}
