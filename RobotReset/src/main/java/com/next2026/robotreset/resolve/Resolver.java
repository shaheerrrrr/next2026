package com.next2026.robotreset.resolve;

/**
 * Resolves a {@link TargetSpec} against a {@link UiTree} and clicks the
 * matched element. Not implemented in Phase 1 — the concrete implementation
 * (including the OpMode-list scroll/match sequence) is Phase 3 scope.
 */
public interface Resolver {

    /**
     * Returns true iff a clickable, enabled ancestor was found for spec (or
     * its fallback chain) and successfully clicked. Must never throw on a
     * tree with no match — that case returns false and calls
     * {@code log.logResolved(..., false, "not found")} or similar.
     */
    boolean resolveAndClick(UiTree tree, TargetSpec spec, ActionLog log);
}
