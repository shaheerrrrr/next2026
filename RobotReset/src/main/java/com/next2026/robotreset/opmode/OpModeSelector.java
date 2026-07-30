package com.next2026.robotreset.opmode;

import android.view.accessibility.AccessibilityEvent;

import java.util.List;

import com.next2026.robotreset.resolve.ActionLog;
import com.next2026.robotreset.resolve.NodeRef;
import com.next2026.robotreset.resolve.Resolver;
import com.next2026.robotreset.resolve.TargetSpec;
import com.next2026.robotreset.resolve.UiTree;

/**
 * Drives the "select an OpMode by name" sequence described in
 * docs/design-accessibility-tap.md ("Selecting An OpMode By Name") and
 * docs/robot-reset-app-brief.md (section 6, "OpMode slot selection"):
 *
 * <ol>
 *   <li>{@link #begin} opens the OpMode dropdown via {@link Resolver}.</li>
 *   <li>Every subsequent content-changed event re-scans for the target text.</li>
 *   <li>If not found, scroll the list forward and wait for the next event.</li>
 *   <li>Bounded by {@code maxAttempts} so a name that is never in the list
 *       terminates instead of looping forever.</li>
 * </ol>
 *
 * This class never sleeps. It is purely reactive: each public entry point
 * does exactly one round of work and then either finishes (goes idle) or
 * waits for the next {@link #onContentChanged} call triggered by the next
 * accessibility event.
 *
 * Thin wrapper split: {@link #onAccessibilityEvent} exists only to filter
 * event types and unwrap the framework type; all real logic lives in
 * {@link #onContentChanged}, which takes a {@link UiTree} and no
 * {@code AccessibilityEvent}, so it can be exercised directly from a plain
 * JVM unit test with a fake {@code UiTree} (this project has no Robolectric
 * set up, so constructing a real {@code AccessibilityEvent} is impractical).
 */
public final class OpModeSelector {

    /** Used when a caller doesn't specify a bound. */
    public static final int DEFAULT_MAX_ATTEMPTS = 8;

    private enum State { IDLE, WAITING }

    private final Resolver resolver;
    private final ActionLog log;
    private final int maxAttempts;

    private State state = State.IDLE;
    private TargetSpec scrollContainerSpec;
    private String targetText;
    private int attempts;

    public OpModeSelector(Resolver resolver, ActionLog log) {
        this(resolver, log, DEFAULT_MAX_ATTEMPTS);
    }

    public OpModeSelector(Resolver resolver, ActionLog log, int maxAttempts) {
        this.resolver = resolver;
        this.log = log;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Starts the sequence: clicks {@code dropdownSpec} to open the OpMode
     * list. If that click fails (control not found or disabled), logs and
     * stays idle — nothing further happens, and subsequent
     * {@link #onContentChanged} calls are no-ops until {@code begin} is
     * called again.
     *
     * @param tree                 current UI tree, used only for the initial click
     * @param dropdownSpec         spec for the control that opens the OpMode list
     * @param scrollContainerSpec  spec for the scrollable list container, tried
     *                             before the "any scrollable node" fallback
     * @param targetText           the already-resolved slot name to look for; the
     *                             caller owns config lookup, this class just
     *                             matches the final string
     */
    public void begin(UiTree tree, TargetSpec dropdownSpec, TargetSpec scrollContainerSpec, String targetText) {
        boolean opened = resolver.resolveAndClick(tree, dropdownSpec, log);
        if (!opened) {
            log.logResolved("opmode-dropdown", false, "failed to open OpMode dropdown");
            state = State.IDLE;
            return;
        }

        this.scrollContainerSpec = scrollContainerSpec;
        this.targetText = targetText;
        this.attempts = 0;
        this.state = State.WAITING;
        // Do not scan synchronously here — wait for the content-changed event
        // the dropdown's own inflation will trigger.
    }

    /**
     * Thin wrapper: filters to the two event types that can indicate the
     * OpMode list changed, then delegates to {@link #onContentChanged}.
     */
    public void onAccessibilityEvent(AccessibilityEvent event, UiTree tree) {
        if (event == null) {
            return;
        }
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        onContentChanged(tree);
    }

    /**
     * All the real logic, with no {@code AccessibilityEvent} parameter so it
     * is trivially unit-testable. No-op unless a {@link #begin} call is
     * currently waiting on a result.
     */
    public void onContentChanged(UiTree tree) {
        if (state != State.WAITING) {
            return;
        }

        boolean clicked = resolver.resolveAndClick(tree, TargetSpec.text(targetText), log);
        if (clicked) {
            state = State.IDLE;
            return;
        }

        attempts++;
        if (attempts > maxAttempts) {
            log.logResolved("opmode-slot:" + targetText, false,
                    "not found after " + attempts + " attempts, giving up");
            state = State.IDLE;
            return;
        }

        NodeRef scrollable = findScrollContainer(tree);
        if (scrollable == null) {
            log.logResolved("opmode-slot:" + targetText, false,
                    "no scrollable container found, giving up");
            state = State.IDLE;
            return;
        }

        scrollable.scrollForward();
        // Stay WAITING; the scroll itself will fire the next content-changed
        // event that drives the next call to this method. Never sleep.
    }

    /**
     * Locates the scrollable list container: first via {@code
     * scrollContainerSpec} (a plain id/text tree scan, not walked to a
     * clickable ancestor — we want the raw scrollable node). Falls back to
     * scanning the whole tree for the first node with {@code isScrollable()
     * == true} if the spec yields nothing, so this works against apps that
     * don't share a hardcoded resource-id with production config.
     */
    private NodeRef findScrollContainer(UiTree tree) {
        NodeRef bySpec = findRaw(tree, scrollContainerSpec);
        if (bySpec != null) {
            return bySpec;
        }
        return findFirstScrollable(tree.getRoot());
    }

    private static NodeRef findRaw(UiTree tree, TargetSpec spec) {
        TargetSpec current = spec;
        while (current != null) {
            List<NodeRef> found = current.kind == TargetSpec.Kind.VIEW_ID
                    ? tree.findByViewId(current.value)
                    : tree.findByText(current.value);
            if (found != null && !found.isEmpty()) {
                return found.get(0);
            }
            current = current.fallback;
        }
        return null;
    }

    private static NodeRef findFirstScrollable(NodeRef node) {
        if (node == null) {
            return null;
        }
        if (node.isScrollable()) {
            return node;
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            NodeRef found = findFirstScrollable(node.getChild(i));
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
