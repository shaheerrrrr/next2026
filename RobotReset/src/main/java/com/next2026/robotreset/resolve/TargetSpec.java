package com.next2026.robotreset.resolve;

/**
 * Describes how to locate a target UI element: either by resource-id or by
 * visible text, with an optional fallback spec to try if the primary match
 * fails (e.g. a VIEW_ID spec whose fallback is a TEXT spec, per the design
 * doc's resolution order: prefer view-id, fall back to text).
 */
public final class TargetSpec {

    /**
     * {@code TEXT_SIBLING} is a narrow, opt-in-only variant of {@code TEXT}:
     * it locates a node the same way TEXT does, but then -- instead of
     * clicking that node's clickable ancestor directly -- looks among that
     * ancestor's siblings (same parent) for another clickable, enabled node
     * with the exact same screen bounds, and clicks the LAST such sibling
     * found instead, falling back to the originally-resolved ancestor if no
     * such sibling exists. This exists for UI built from overlapping views at
     * identical bounds where only one (often a decorative/label layer) has
     * any accessible text/description, and the other -- rendered on top, so
     * the one real touches actually hit, since Android hit-tests later
     * siblings first -- is the one truly wired to the click. Ordinary
     * VIEW_ID/TEXT resolution never triggers this: it is a fully separate
     * code path, so it cannot change behavior for a target that doesn't use
     * it.
     */
    public enum Kind { VIEW_ID, TEXT, TEXT_SIBLING }

    public final Kind kind;
    public final String value;
    public final TargetSpec fallback; // nullable

    public TargetSpec(Kind kind, String value, TargetSpec fallback) {
        this.kind = kind;
        this.value = value;
        this.fallback = fallback;
    }

    public static TargetSpec viewId(String value) {
        return new TargetSpec(Kind.VIEW_ID, value, null);
    }

    public static TargetSpec viewId(String value, TargetSpec fallback) {
        return new TargetSpec(Kind.VIEW_ID, value, fallback);
    }

    public static TargetSpec text(String value) {
        return new TargetSpec(Kind.TEXT, value, null);
    }

    public static TargetSpec text(String value, TargetSpec fallback) {
        return new TargetSpec(Kind.TEXT, value, fallback);
    }

    public static TargetSpec textSibling(String value) {
        return new TargetSpec(Kind.TEXT_SIBLING, value, null);
    }

    @Override
    public String toString() {
        return "TargetSpec{kind=" + kind + ", value=" + value
                + ", fallback=" + fallback + "}";
    }
}
