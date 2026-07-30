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
 * <h3>Two independent bounds</h3>
 * A cold dropdown fires a {@code TYPE_WINDOW_CONTENT_CHANGED} almost
 * immediately after being clicked (~40ms), well before it has inflated: at
 * that instant there is no target text AND no scrollable container in the
 * tree at all. That is the <em>normal</em> ordering, not an edge case, and it
 * must not be treated as terminal -- the real list can still show up in a
 * later event. So this class tracks two separate bounds that answer two
 * different questions:
 * <ul>
 *   <li><b>Scroll-attempt bound</b> ({@link #maxAttempts}): incremented only
 *       when a scroll is actually performed. This is what guarantees "a name
 *       that isn't in the list terminates instead of looping forever" --
 *       the most important correctness property of this class. An event
 *       where nothing scrollable exists yet does not consume this budget,
 *       because no scroll happened.</li>
 *   <li><b>Overall deadline</b> ({@link #deadlineMillis}): recorded when
 *       {@link #begin} succeeds, checked on each event arrival (not polled,
 *       not slept on -- purely a staleness check evaluated only when an
 *       event happens to arrive). This bounds the "list never appears at
 *       all" case, so the selector can't sit WAITING forever holding stale
 *       state.</li>
 * </ul>
 * Staying WAITING while nothing is scrollable yet is safe specifically
 * because of the deadline: {@link #onContentChanged} already no-ops unless
 * state is WAITING, and the deadline bounds how long WAITING can persist, so
 * a stale selection can't fire minutes later on an unrelated window change.
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

    /**
     * Default overall deadline for a selection sequence, from a successful
     * {@link #begin} to the target being found. Generous relative to
     * observed cold-dropdown inflation (~900ms on the emulator) so it does
     * not fire under normal conditions, while still bounding the "list never
     * appears" case.
     */
    public static final long DEFAULT_DEADLINE_MILLIS = 5000L;

    private static final Clock SYSTEM_CLOCK = new Clock() {
        @Override
        public long now() {
            return System.currentTimeMillis();
        }
    };

    private enum State { IDLE, WAITING }

    private final Resolver resolver;
    private final ActionLog log;
    private final int maxAttempts;
    private final long deadlineMillis;
    private final Clock clock;

    private State state = State.IDLE;
    private TargetSpec scrollContainerSpec;
    private String targetText;
    private int attempts;
    private long deadlineAt;

    public OpModeSelector(Resolver resolver, ActionLog log) {
        this(resolver, log, DEFAULT_MAX_ATTEMPTS);
    }

    public OpModeSelector(Resolver resolver, ActionLog log, int maxAttempts) {
        this(resolver, log, maxAttempts, DEFAULT_DEADLINE_MILLIS, SYSTEM_CLOCK);
    }

    /**
     * Full constructor. {@code deadlineMillis} and {@code clock} are the
     * injectable seam that lets unit tests advance time deterministically
     * (no {@code Thread.sleep}) to exercise the "list never appears"
     * termination path.
     */
    public OpModeSelector(Resolver resolver, ActionLog log, int maxAttempts, long deadlineMillis, Clock clock) {
        this.resolver = resolver;
        this.log = log;
        this.maxAttempts = maxAttempts;
        this.deadlineMillis = deadlineMillis;
        this.clock = clock;
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
        this.deadlineAt = clock.now() + deadlineMillis;
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

        // Deadline check happens on every event arrival, not on a timer or
        // poll loop -- this is a staleness check, not a sleep. Bounds the
        // "list never appears at all" case so WAITING can't persist forever.
        if (clock.now() >= deadlineAt) {
            log.logResolved("opmode-slot:" + targetText, false,
                    "deadline of " + deadlineMillis + "ms exceeded, giving up");
            state = State.IDLE;
            return;
        }

        boolean clicked = resolver.resolveAndClick(tree, TargetSpec.text(targetText), log);
        if (clicked) {
            state = State.IDLE;
            return;
        }

        NodeRef scrollable = findScrollContainer(tree);
        if (scrollable == null) {
            // Nothing scrollable on screen yet. This is the NORMAL state
            // while a cold dropdown is still inflating (a click can fire a
            // content-changed event ~40ms later, long before the list
            // renders) -- not a terminal condition. Stay WAITING for the
            // next event and do NOT consume scroll-attempt budget, since no
            // scroll happened. The deadline above is what eventually bounds
            // this if the list genuinely never appears.
            log.logResolved("opmode-slot:" + targetText, false,
                    "not found, nothing scrollable yet, waiting for next event");
            return;
        }

        if (attempts >= maxAttempts) {
            log.logResolved("opmode-slot:" + targetText, false,
                    "not found after " + attempts + " scroll attempts, giving up");
            state = State.IDLE;
            return;
        }

        attempts++;
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
