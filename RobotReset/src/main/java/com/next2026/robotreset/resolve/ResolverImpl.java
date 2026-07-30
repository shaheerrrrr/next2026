package com.next2026.robotreset.resolve;

import java.util.List;

/**
 * Default {@link Resolver} implementation.
 *
 * <p>Resolution walks the {@link TargetSpec} chain (primary spec, then {@code
 * fallback}, then its fallback, and so on, bounded so a pathological/cyclic
 * spec chain cannot hang the resolver). For each spec in the chain, every
 * matching node returned by {@link UiTree#findByViewId} / {@link
 * UiTree#findByText} is tried in turn (not just the first) because a text or
 * id match is frequently ambiguous and only one of several matches may lead
 * to a usable clickable ancestor.
 *
 * <p>For each candidate match, the parent chain is walked upward (bounded by
 * {@link #MAX_ANCESTOR_HOPS}) looking for the first ancestor with {@code
 * isClickable() == true} &mdash; see the design doc's Gotcha 2, a matched
 * node (e.g. a text label) is frequently not itself clickable. If a
 * clickable ancestor is found but {@code isEnabled() == false} (Gotcha 3,
 * e.g. INIT before an OpMode is selected), that candidate is a dead end:
 * it is logged as a no-op if nothing else works out, but resolution is not
 * retried or polled &mdash; this method makes exactly one pass over the
 * tree it is given.
 *
 * <p>This class never throws for a tree with no match anywhere; that is a
 * normal, expected outcome (an off-screen or not-yet-rendered target) and is
 * reported as a {@code false} return plus a logged reason, not an exception.
 */
public final class ResolverImpl implements Resolver {

    /**
     * Maximum number of ancestor hops to walk from a matched node while
     * looking for a clickable ancestor. Bounds the walk so a malformed or
     * (in the pathological case) cyclic parent chain cannot hang the
     * resolver. In a normal Android view tree a clickable container is
     * within a couple of levels of its label; 10 is generous headroom.
     */
    private static final int MAX_ANCESTOR_HOPS = 10;

    /**
     * Maximum number of specs to walk down the {@code fallback} chain.
     * {@link TargetSpec} is normally a short, straight chain (e.g. VIEW_ID
     * falling back to TEXT), but this bounds the walk defensively in case a
     * caller ever builds a longer or accidentally-cyclic chain.
     */
    private static final int MAX_FALLBACK_HOPS = 20;

    @Override
    public boolean resolveAndClick(UiTree tree, TargetSpec spec, ActionLog log) {
        if (spec == null) {
            if (log != null) {
                log.logResolved("<null-spec>", false, "invalid spec: null");
            }
            return false;
        }
        String label = labelFor(spec);
        if (tree == null) {
            log.logResolved(label, false, "invalid tree: null");
            return false;
        }
        try {
            return resolveInternal(tree, spec, log, label);
        } catch (RuntimeException e) {
            // Must never throw: an unexpected exception from the platform
            // layer (or a misbehaving fake in tests) is reported the same
            // way as any other failed resolution, not propagated.
            log.logResolved(label, false, "exception during resolution: " + e);
            return false;
        }
    }

    private boolean resolveInternal(UiTree tree, TargetSpec spec, ActionLog log, String label) {
        boolean sawDisabled = false;
        boolean sawClickFailure = false;

        TargetSpec current = spec;
        int fallbackHops = 0;
        while (current != null && fallbackHops <= MAX_FALLBACK_HOPS) {
            List<NodeRef> matches = findMatches(tree, current);
            if (matches != null) {
                for (int i = 0; i < matches.size(); i++) {
                    NodeRef match = matches.get(i);
                    if (match == null) {
                        continue;
                    }
                    NodeRef ancestor = findClickableAncestor(match);
                    if (ancestor == null) {
                        // No clickable ancestor within the bound: dead end
                        // for this candidate, already recycled internally.
                        continue;
                    }
                    if (!ancestor.isEnabled()) {
                        sawDisabled = true;
                        ancestor.recycle();
                        continue;
                    }
                    boolean clicked = ancestor.click();
                    ancestor.recycle();
                    if (clicked) {
                        recycleRemaining(matches, i + 1);
                        log.logResolved(label, true, "clicked");
                        return true;
                    }
                    // performAction reported failure; try the next
                    // candidate rather than giving up on the whole spec.
                    sawClickFailure = true;
                }
            }
            current = current.fallback;
            fallbackHops++;
        }

        String reason = sawDisabled
                ? "found but disabled (no-op)"
                : sawClickFailure
                        ? "found but click action failed"
                        : "not found";
        log.logResolved(label, false, reason);
        return false;
    }

    private List<NodeRef> findMatches(UiTree tree, TargetSpec spec) {
        if (spec.kind == TargetSpec.Kind.VIEW_ID) {
            return tree.findByViewId(spec.value);
        }
        return tree.findByText(spec.value);
    }

    /**
     * Walks from {@code start} up through {@code getParent()} looking for
     * the first node with {@code isClickable() == true}, per Gotcha 2 (a
     * matched label/text node is frequently nested inside the actual
     * clickable button/container). Bounded by {@link #MAX_ANCESTOR_HOPS} so
     * a malformed or cyclic parent chain terminates instead of hanging.
     *
     * <p>Every node walked past (i.e. found non-clickable and not returned)
     * is recycled here, since the caller never sees or revisits it. The
     * node that is returned (the clickable ancestor) is deliberately left
     * un-recycled; it is either about to be clicked or the caller is about
     * to inspect/recycle it after deciding it's disabled.
     */
    private NodeRef findClickableAncestor(NodeRef start) {
        NodeRef current = start;
        int hops = 0;
        while (current != null) {
            if (current.isClickable()) {
                return current;
            }
            if (hops >= MAX_ANCESTOR_HOPS) {
                current.recycle();
                return null;
            }
            NodeRef parent = current.getParent();
            current.recycle();
            current = parent;
            hops++;
        }
        return null;
    }

    private void recycleRemaining(List<NodeRef> matches, int fromIndexInclusive) {
        for (int j = fromIndexInclusive; j < matches.size(); j++) {
            NodeRef unused = matches.get(j);
            if (unused != null) {
                unused.recycle();
            }
        }
    }

    private static String labelFor(TargetSpec spec) {
        return spec.kind + ":" + spec.value;
    }
}
