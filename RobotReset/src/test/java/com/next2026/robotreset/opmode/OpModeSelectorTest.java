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
     * Nothing scrollable exists in the tree at all, and the target isn't
     * found: goes idle without hanging or crashing (no scroll attempted,
     * no exception).
     */
    @Test
    public void noScrollableAnywhere_goesIdleImmediately() {
        FakeNodeRef root = FakeNodeRef.plain(); // not scrollable, no children
        FakeUiTree tree = new FakeUiTree(root);
        TargetSpec missingSpec = TargetSpec.viewId("no_such_id");

        FakeResolver resolver = new FakeResolver(true, false);
        FakeActionLog log = new FakeActionLog();
        OpModeSelector selector = new OpModeSelector(resolver, log);

        selector.begin(tree, DROPDOWN_SPEC, missingSpec, "Teleop Main");
        selector.onContentChanged(tree); // fail, no scrollable anywhere -> idle immediately

        int callsAfterGivingUp = resolver.callCount;
        assertEquals(2, callsAfterGivingUp); // dropdown open + one failed match, then gave up

        // Idle: a further event must not throw and must not call the resolver again.
        selector.onContentChanged(tree);
        assertEquals(callsAfterGivingUp, resolver.callCount);
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
