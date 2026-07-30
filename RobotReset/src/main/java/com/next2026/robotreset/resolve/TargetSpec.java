package com.next2026.robotreset.resolve;

/**
 * Describes how to locate a target UI element: either by resource-id or by
 * visible text, with an optional fallback spec to try if the primary match
 * fails (e.g. a VIEW_ID spec whose fallback is a TEXT spec, per the design
 * doc's resolution order: prefer view-id, fall back to text).
 */
public final class TargetSpec {

    public enum Kind { VIEW_ID, TEXT }

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

    @Override
    public String toString() {
        return "TargetSpec{kind=" + kind + ", value=" + value
                + ", fallback=" + fallback + "}";
    }
}
