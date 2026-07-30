package com.next2026.robotreset.opmode;

import org.junit.Test;

import com.next2026.robotreset.resolve.TargetSpec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class OpModeSelectorTest {

    private static final TargetSpec DROPDOWN_SPEC = TargetSpec.viewId("opmode_dropdown");
    private static final TargetSpec SCROLL_SPEC = TargetSpec.viewId("opmode_list");

    /**
     * Target text present on the very first onContentChanged call: no scroll
     * needed, matched and clicked, state goes idle (proved by a further
     * onContentChanged call being a no-op afterwards).
     */
    @Test
    public void foundOnFirstContentChanged_noScrollNeeded_goesIdle() {
        FakeNodeRef root = FakeNodeRef.plain();
        FakeUiTree tree = new FakeUiTree(root);
        FakeResolver resolver = new FakeResolver(true /* dropdown opens */, true /* found immediately */);
        FakeActionLog log = new FakeActionLog();
        OpModeSelector selector = new OpModeSelector(resolver, log);

        selector.begin(tree, DROPDOWN_SPEC, SCROLL_SPEC, "Auto Blue Left");
        selector.onContentChanged(tree);

        assertEquals("dropdown open + one successful match", 2, resolver.callCount);
        assertEquals(0, root.scrollForwardCalls);

        // Idle now: further events are no-ops (resolver isn't called again).
        selector.onContentChanged(tree);
        assertEquals(2, resolver.callCount);
    }

    /**
     * Target text absent initially, appears after two simulated scrolls.
     */
    @Test
    public void foundAfterSeveralScrolls_eventuallyMatched() {
        FakeNodeRef scrollContainer = FakeNodeRef.scrollableContainer("opmode_list");
        FakeUiTree tree = new FakeUiTree(scrollContainer);
        // dropdown opens, 2 failed matches (each triggers a scroll), 3rd succeeds.
        FakeResolver resolver = new FakeResolver(true, false, false, true);
        FakeActionLog log = new FakeActionLog();
        OpModeSelector selector = new OpModeSelector(resolver, log);

        selector.begin(tree, DROPDOWN_SPEC, SCROLL_SPEC, "Auto Red Right");
        selector.onContentChanged(tree); // fail -> scroll
        selector.onContentChanged(tree); // fail -> scroll
        selector.onContentChanged(tree); // success -> idle

        assertEquals(2, scrollContainer.scrollForwardCalls);
        assertEquals(4, resolver.callCount);

        selector.onContentChanged(tree);
        assertEquals("idle after success, no further work", 4, resolver.callCount);
        assertEquals(2, scrollContainer.scrollForwardCalls);
    }

    /**
     * Target text never appears: attempts exceed the bound, state goes
     * idle, and no further scroll calls happen after that point. This is
     * the termination proof, not just a limit constant existing.
     */
    @Test
    public void neverFound_exceedsBound_terminatesAndStopsScrolling() {
        int maxAttempts = 3;
        FakeNodeRef scrollContainer = FakeNodeRef.scrollableContainer("opmode_list");
        FakeUiTree tree = new FakeUiTree(scrollContainer);
        FakeResolver resolver = new FakeResolver(true /* dropdown opens */); // all later calls default false
        FakeActionLog log = new FakeActionLog();
        OpModeSelector selector = new OpModeSelector(resolver, log, maxAttempts);

        selector.begin(tree, DROPDOWN_SPEC, SCROLL_SPEC, "Does Not Exist");

        // Attempts 1..maxAttempts each fail and scroll.
        for (int i = 0; i < maxAttempts; i++) {
            selector.onContentChanged(tree);
        }
        assertEquals(maxAttempts, scrollContainer.scrollForwardCalls);

        // One more failing attempt pushes the counter past the bound: gives up, no scroll.
        selector.onContentChanged(tree);
        assertEquals("no scroll once the bound is exceeded", maxAttempts, scrollContainer.scrollForwardCalls);

        int callsSoFar = resolver.callCount;

        // Further events after giving up must be complete no-ops.
        selector.onContentChanged(tree);
        selector.onContentChanged(tree);
        assertEquals("idle: resolver not called again", callsSoFar, resolver.callCount);
        assertEquals("idle: no more scrolling", maxAttempts, scrollContainer.scrollForwardCalls);

        boolean sawGiveUpLog = false;
        for (String entry : log.entries) {
            if (entry.contains("giving up")) {
                sawGiveUpLog = true;
            }
        }
        assertTrue("expected a give-up log entry", sawGiveUpLog);
    }

    /**
     * scrollContainerSpec doesn't match anything, but some other node in
     * the tree has isScrollable() == true: the structural fallback finds
     * and scrolls it.
     */
    @Test
    public void scrollContainerSpecMisses_fallsBackToAnyScrollableNode() {
        FakeNodeRef fallbackScrollable = FakeNodeRef.plain();
        fallbackScrollable.scrollable = true; // no matching id/text, purely structural

        FakeNodeRef root = FakeNodeRef.plain();
        root.addChild(fallbackScrollable);
        FakeUiTree tree = new FakeUiTree(root);

        // scrollContainerSpec below matches nothing in this tree.
        TargetSpec missingSpec = TargetSpec.viewId("no_such_id");

        FakeResolver resolver = new FakeResolver(true, false, true);
        FakeActionLog log = new FakeActionLog();
        OpModeSelector selector = new OpModeSelector(resolver, log);

        selector.begin(tree, DROPDOWN_SPEC, missingSpec, "Teleop Main");
        selector.onContentChanged(tree); // fail -> fallback scroll
        selector.onContentChanged(tree); // success -> idle

        assertEquals(1, fallbackScrollable.scrollForwardCalls);
    }

    /**
     * Regression test for Bug 1 (a cold OpMode dropdown): clicking the
     * dropdown fires a content-changed event almost immediately, before the
     * list has inflated -- at that instant there is no target text AND no
     * scrollable node anywhere in the tree. Under the old code, "no
     * scrollable container found" was treated as terminal and the selector
     * went IDLE right there, so the OpMode was never selected once the real
     * list appeared ~900ms later. This test fails at the first event under
     * the old code; the fix must stay WAITING through the cold event and
     * pick up the target on the later, populated event.
     */
    @Test
    public void coldDropdown_nothingScrollableOnFirstEvent_targetFoundOnLaterEvent() {
        FakeNodeRef coldRoot = FakeNodeRef.plain(); // not scrollable, no children: dropdown mid-inflation
        FakeUiTree coldTree = new FakeUiTree(coldRoot);

        FakeNodeRef populatedList = FakeNodeRef.scrollableContainer("opmode_list");
        FakeUiTree populatedTree = new FakeUiTree(populatedList);

        // dropdown opens; first target search (against the cold tree) misses;
        // second target search (against the populated tree) hits.
        FakeResolver resolver = new FakeResolver(true, false, true);
        FakeActionLog log = new FakeActionLog();
        OpModeSelector selector = new OpModeSelector(resolver, log);

        selector.begin(coldTree, DROPDOWN_SPEC, SCROLL_SPEC, "OpMode Slot 3");
        selector.onContentChanged(coldTree); // cold: no target, nothing scrollable -> must stay WAITING
        selector.onContentChanged(populatedTree); // list has inflated -> found and clicked

        assertEquals("dropdown open + cold miss + successful match", 3, resolver.callCount);
        assertEquals("no scroll performed: target found on the next event, not via scrolling",
                0, populatedList.scrollForwardCalls);
        assertEquals("cold root has no scrollable node to have scrolled anyway",
                0, coldRoot.scrollForwardCalls);

        // Idle now: further events are no-ops.
        selector.onContentChanged(populatedTree);
        assertEquals(3, resolver.callCount);
    }

    /**
     * Several consecutive events where nothing is scrollable yet must NOT
     * consume the scroll-attempt budget. Proven with a maxAttempts of 1: if
     * the cold events wrongly consumed budget, the one real scroll needed
     * once the list actually appears would already be over budget and the
     * selector would give up instead of finding the target.
     */
    @Test
    public void repeatedNothingScrollableEvents_doNotConsumeScrollBudget() {
        int maxAttempts = 1;
        FakeNodeRef coldRoot = FakeNodeRef.plain();
        FakeUiTree coldTree = new FakeUiTree(coldRoot);

        FakeNodeRef scrollContainer = FakeNodeRef.scrollableContainer("opmode_list");
        FakeUiTree populatedTree = new FakeUiTree(scrollContainer);

        // dropdown opens; three cold misses (nothing scrollable); then one
        // miss against the populated (now scrollable) tree that must still
        // be allowed to scroll; then a hit.
        FakeResolver resolver = new FakeResolver(true, false, false, false, false, true);
        FakeActionLog log = new FakeActionLog();
        OpModeSelector selector = new OpModeSelector(resolver, log, maxAttempts);

        selector.begin(coldTree, DROPDOWN_SPEC, SCROLL_SPEC, "Auto Red Right");
        selector.onContentChanged(coldTree); // cold miss 1: no budget consumed
        selector.onContentChanged(coldTree); // cold miss 2: no budget consumed
        selector.onContentChanged(coldTree); // cold miss 3: no budget consumed
        selector.onContentChanged(populatedTree); // real miss: scrollable now exists -> consumes budget, scrolls
        selector.onContentChanged(populatedTree); // hit -> idle

        assertEquals("exactly one real scroll, budget untouched by the cold events",
                1, scrollContainer.scrollForwardCalls);
        assertEquals(6, resolver.callCount);
    }

    /**
     * The overall deadline (not the scroll-attempt bound) is what terminates
     * a selection where the list never appears at all. Checked on event
     * arrival via the injected {@link Clock}, never by sleeping or polling:
     * advancing the fake clock past the deadline and then delivering one
     * more event must go IDLE, log the reason, and make further events
     * no-ops -- without ever consuming the resolver again after that point.
     */
    @Test
    public void deadlineElapses_listNeverAppears_goesIdleAndStopsRespondingToEvents() {
        FakeNodeRef coldRoot = FakeNodeRef.plain();
        FakeUiTree coldTree = new FakeUiTree(coldRoot);
        TargetSpec missingSpec = TargetSpec.viewId("no_such_id");

        FakeResolver resolver = new FakeResolver(true /* dropdown opens */); // no further calls expected
        FakeActionLog log = new FakeActionLog();
        FakeClock clock = new FakeClock(1_000_000L);
        long deadlineMillis = 5000L;
        OpModeSelector selector = new OpModeSelector(resolver, log, OpModeSelector.DEFAULT_MAX_ATTEMPTS,
                deadlineMillis, clock);

        selector.begin(coldTree, DROPDOWN_SPEC, missingSpec, "Teleop Main");
        assertEquals(1, resolver.callCount);

        clock.advance(deadlineMillis + 1); // list still never showed up
        selector.onContentChanged(coldTree);

        assertEquals("deadline check happens before any resolve attempt on this event",
                1, resolver.callCount);
        assertEquals(0, coldRoot.scrollForwardCalls);

        boolean sawDeadlineLog = false;
        for (String entry : log.entries) {
            if (entry.contains("deadline")) {
                sawDeadlineLog = true;
            }
        }
        assertTrue("expected a deadline give-up log entry", sawDeadlineLog);

        // Idle: further events (even more time passing) must not call the resolver again.
        clock.advance(10_000L);
        selector.onContentChanged(coldTree);
        assertEquals(1, resolver.callCount);
    }

    /**
     * begin() itself fails to open the dropdown: the waiting loop is never
     * entered, so a subsequent onContentChanged call is a no-op.
     */
    @Test
    public void beginFailsToOpenDropdown_neverEntersWaitingLoop() {
        FakeNodeRef root = FakeNodeRef.plain();
        FakeUiTree tree = new FakeUiTree(root);
        FakeResolver resolver = new FakeResolver(false /* dropdown click fails */);
        FakeActionLog log = new FakeActionLog();
        OpModeSelector selector = new OpModeSelector(resolver, log);

        selector.begin(tree, DROPDOWN_SPEC, SCROLL_SPEC, "Auto Blue Left");
        assertEquals(1, resolver.callCount);

        selector.onContentChanged(tree);
        assertEquals("no-op: never entered the waiting state", 1, resolver.callCount);
        assertEquals(0, root.scrollForwardCalls);

        boolean sawFailureLog = false;
        for (String entry : log.entries) {
            if (entry.contains("opmode-dropdown")) {
                sawFailureLog = true;
            }
        }
        assertTrue(sawFailureLog);
    }
}
