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
                    if (current.kind == TargetSpec.Kind.TEXT_SIBLING) {
                        ancestor = preferTopmostSiblingAtSameBounds(ancestor);
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

    /**
     * See {@link TargetSpec.Kind#TEXT_SIBLING}'s doc comment for why this
     * exists. Looks at {@code node}'s parent's children (not {@code node}
     * itself) for another clickable, enabled node whose bounds exactly match
     * {@code node}'s, and returns the LAST one found in child order (Android
     * hit-tests later-drawn/topmost siblings first, so this is the one a
     * real touch actually reaches). Returns {@code node} unchanged if bounds
     * are unavailable, there's no parent, or no such sibling exists -- this
     * is a preference, not a requirement, so it degrades to ordinary
     * behavior rather than failing resolution outright.
     */
    private NodeRef preferTopmostSiblingAtSameBounds(NodeRef node) {
        int[] targetBounds = node.getBoundsInScreen();
        if (targetBounds == null) {
            return node;
        }
        NodeRef parent = node.getParent();
        if (parent == null) {
            return node;
        }
        // node is not recycled here yet: it stays a live candidate for best
        // until something replaces it below (it is always its own bounds
        // match), and the eventual winner is left un-recycled for the
        // caller, exactly like findClickableAncestor's contract.
        NodeRef best = node;
        int count = parent.getChildCount();
        for (int i = 0; i < count; i++) {
            NodeRef sibling = parent.getChild(i);
            if (sibling == null) {
                continue;
            }
            if (sibling == best) {
                // Same underlying node as the current best (e.g. node's own
                // slot in its parent's children): nothing to compare or
                // recycle, just move on.
                continue;
            }
            if (sibling.isClickable() && sibling.isEnabled()
                    && sameBounds(targetBounds, sibling.getBoundsInScreen())) {
                if (best != node) {
                    best.recycle();
                }
                best = sibling;
            } else {
                sibling.recycle();
            }
        }
        parent.recycle();
        if (best != node) {
            node.recycle();
        }
        return best;
    }

    private boolean sameBounds(int[] a, int[] b) {
        if (a == null || b == null || a.length != 4 || b.length != 4) {
            return false;
        }
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2] && a[3] == b[3];
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
