package com.next2026.robotreset.resolve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Coverage for {@link ResolverImpl} against the gotchas called out in
 * docs/design-accessibility-tap.md and docs/robot-reset-app-brief.md:
 * a matched node usually isn't the clickable one (Gotcha 2), a disabled
 * clickable ancestor must be a logged no-op rather than a retry (Gotcha 3),
 * multiple matches must all be tried, fallback specs must be honored, and a
 * pathological ancestor chain must not hang the resolver.
 */
public class ResolverImplTest {

    private final ResolverImpl resolver = new ResolverImpl();

    // ---- Target not present anywhere -------------------------------------

    @Test
    public void targetNotPresent_returnsFalseWithoutThrowing() {
        FakeNodeRef root = new FakeNodeRef("root");
        root.addChild(new FakeNodeRef("unrelated").text("SOMETHING ELSE").clickable(true));
        FakeUiTree tree = new FakeUiTree(root);
        FakeActionLog log = new FakeActionLog();

        boolean result = resolver.resolveAndClick(tree, TargetSpec.text("INIT"), log);

        assertFalse("no node matches INIT anywhere in the tree", result);
        assertEquals(1, log.entries.size());
        FakeActionLog.Entry entry = log.last();
        assertFalse(entry.clicked);
        assertTrue("reason should indicate the target wasn't found: " + entry.reason,
                entry.reason.toLowerCase().contains("not found"));
    }

    // ---- Target present but disabled --------------------------------------

    @Test
    public void targetPresentButDisabled_isLoggedNoOpNotRetried() {
        FakeNodeRef root = new FakeNodeRef("root");
        FakeNodeRef init = new FakeNodeRef("init")
                .text("INIT")
                .clickable(true)
                .enabled(false); // INIT disabled until an OpMode is selected
        root.addChild(init);
        FakeUiTree tree = new FakeUiTree(root);
        FakeActionLog log = new FakeActionLog();

        boolean result = resolver.resolveAndClick(tree, TargetSpec.text("INIT"), log);

        assertFalse("disabled target must not be treated as a successful click", result);
        assertEquals("resolver must not retry a disabled target", 0, init.clickCount);
        assertEquals(1, log.entries.size());
        FakeActionLog.Entry entry = log.last();
        assertFalse(entry.clicked);
        assertTrue("reason should mention disabled/no-op: " + entry.reason,
                entry.reason.toLowerCase().contains("disabled"));
    }

    // ---- Matched node is a non-clickable leaf several levels deep ---------

    @Test
    public void nonClickableLeafThreeLevelsDeep_clicksClickableAncestorNotTheLeaf() {
        FakeNodeRef ancestorButton = new FakeNodeRef("ancestorButton").clickable(true).enabled(true);
        FakeNodeRef containerB = new FakeNodeRef("containerB").clickable(false);
        FakeNodeRef containerC = new FakeNodeRef("containerC").clickable(false);
        FakeNodeRef leafLabel = new FakeNodeRef("leafLabel").text("START").clickable(false);

        ancestorButton.addChild(containerB);
        containerB.addChild(containerC);
        containerC.addChild(leafLabel);
        // leafLabel -> containerC -> containerB -> ancestorButton is 3 hops up.

        FakeUiTree tree = new FakeUiTree(ancestorButton);
        FakeActionLog log = new FakeActionLog();

        boolean result = resolver.resolveAndClick(tree, TargetSpec.text("START"), log);

        assertTrue(result);
        assertTrue("the clickable ancestor should have been clicked", ancestorButton.wasClicked());
        assertFalse("the matched leaf itself must not be clicked", leafLabel.wasClicked());
        assertFalse(containerB.wasClicked());
        assertFalse(containerC.wasClicked());
        FakeActionLog.Entry entry = log.last();
        assertTrue(entry.clicked);
    }

    // ---- Multiple matches: first is a dead end, a later one succeeds ------

    @Test
    public void multipleMatches_firstDisabled_secondSucceeds() {
        FakeNodeRef root = new FakeNodeRef("root");

        FakeNodeRef disabledAncestor = new FakeNodeRef("disabledAncestor").clickable(true).enabled(false);
        FakeNodeRef disabledMatch = new FakeNodeRef("disabledMatch").text("OPMODE_A").clickable(false);
        disabledAncestor.addChild(disabledMatch);
        root.addChild(disabledAncestor);

        FakeNodeRef workingAncestor = new FakeNodeRef("workingAncestor").clickable(true).enabled(true);
        FakeNodeRef workingMatch = new FakeNodeRef("workingMatch").text("OPMODE_A").clickable(false);
        workingAncestor.addChild(workingMatch);
        root.addChild(workingAncestor);

        FakeUiTree tree = new FakeUiTree(root);
        FakeActionLog log = new FakeActionLog();

        boolean result = resolver.resolveAndClick(tree, TargetSpec.text("OPMODE_A"), log);

        assertTrue("resolution should keep trying and succeed via the second match", result);
        assertFalse("the disabled ancestor must never be clicked", disabledAncestor.wasClicked());
        assertTrue("the working ancestor from the later match should be clicked",
                workingAncestor.wasClicked());
    }

    @Test
    public void multipleMatches_firstHasNoClickableAncestor_secondSucceeds() {
        FakeNodeRef root = new FakeNodeRef("root");

        // First match: no clickable node anywhere in its ancestor chain.
        FakeNodeRef deadEndParent = new FakeNodeRef("deadEndParent").clickable(false);
        FakeNodeRef deadEndMatch = new FakeNodeRef("deadEndMatch").text("OPMODE_B").clickable(false);
        deadEndParent.addChild(deadEndMatch);
        root.addChild(deadEndParent);

        // Second match: has a clickable, enabled ancestor.
        FakeNodeRef workingAncestor = new FakeNodeRef("workingAncestor").clickable(true).enabled(true);
        FakeNodeRef workingMatch = new FakeNodeRef("workingMatch").text("OPMODE_B").clickable(false);
        workingAncestor.addChild(workingMatch);
        root.addChild(workingAncestor);

        FakeUiTree tree = new FakeUiTree(root);
        FakeActionLog log = new FakeActionLog();

        boolean result = resolver.resolveAndClick(tree, TargetSpec.text("OPMODE_B"), log);

        assertTrue(result);
        assertTrue(workingAncestor.wasClicked());
    }

    // ---- Fallback chain: primary finds nothing, fallback succeeds ---------

    @Test
    public void primaryViewIdNotFound_fallbackToTextSucceeds() {
        FakeNodeRef root = new FakeNodeRef("root");
        FakeNodeRef ancestor = new FakeNodeRef("ancestor").clickable(true).enabled(true);
        FakeNodeRef label = new FakeNodeRef("label").text("INIT").clickable(false);
        ancestor.addChild(label);
        root.addChild(ancestor);
        // Note: no node anywhere has the view-id below.

        FakeUiTree tree = new FakeUiTree(root);
        FakeActionLog log = new FakeActionLog();

        TargetSpec spec = TargetSpec.viewId(
                "com.qualcomm.ftcdriverstation:id/init",
                TargetSpec.text("INIT"));

        boolean result = resolver.resolveAndClick(tree, spec, log);

        assertTrue("fallback TEXT spec should succeed when the primary VIEW_ID spec finds nothing",
                result);
        assertTrue(ancestor.wasClicked());
    }

    @Test
    public void primaryViewIdFindsDisabledTarget_fallbackStillAttempted() {
        // Primary VIEW_ID match exists but is disabled; fallback TEXT match
        // to a different, enabled node should still be tried and succeed.
        FakeNodeRef root = new FakeNodeRef("root");

        FakeNodeRef disabledById = new FakeNodeRef("disabledById")
                .viewId("com.qualcomm.ftcdriverstation:id/init")
                .clickable(true)
                .enabled(false);
        root.addChild(disabledById);

        FakeNodeRef enabledAncestor = new FakeNodeRef("enabledAncestor").clickable(true).enabled(true);
        FakeNodeRef textLabel = new FakeNodeRef("textLabel").text("INIT").clickable(false);
        enabledAncestor.addChild(textLabel);
        root.addChild(enabledAncestor);

        FakeUiTree tree = new FakeUiTree(root);
        FakeActionLog log = new FakeActionLog();

        TargetSpec spec = TargetSpec.viewId(
                "com.qualcomm.ftcdriverstation:id/init",
                TargetSpec.text("INIT"));

        boolean result = resolver.resolveAndClick(tree, spec, log);

        assertTrue(result);
        assertFalse(disabledById.wasClicked());
        assertTrue(enabledAncestor.wasClicked());
    }

    // ---- Pathological ancestor chain must not hang -------------------------

    @Test(timeout = 5000)
    public void cyclicAncestorChain_doesNotHang_returnsFalse() {
        // Two nodes whose parent pointers point at each other: a cycle that
        // could never occur in a real Android tree, but the bounded walk
        // must still terminate instead of looping forever.
        FakeNodeRef a = new FakeNodeRef("a").text("STOP").clickable(false);
        FakeNodeRef b = new FakeNodeRef("b").clickable(false);
        a.setParent(b);
        b.setParent(a);

        FakeUiTree tree = new FakeUiTree(a);
        FakeActionLog log = new FakeActionLog();

        boolean result = resolver.resolveAndClick(tree, TargetSpec.text("STOP"), log);

        assertFalse("a cyclic ancestor chain has no clickable node and must resolve to false", result);
        assertEquals(1, log.entries.size());
    }

    @Test(timeout = 5000)
    public void veryDeepAncestorChain_beyondBound_doesNotHangAndReturnsFalse() {
        // A long straight chain (not cyclic) with the only clickable node
        // far beyond the resolver's hop bound. The walk must give up
        // instead of walking indefinitely, and correctly fail to find it.
        FakeNodeRef top = new FakeNodeRef("top-clickable").clickable(true).enabled(true);
        FakeNodeRef current = top;
        List<FakeNodeRef> chain = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            FakeNodeRef next = new FakeNodeRef("mid-" + i).clickable(false);
            current.addChild(next);
            chain.add(next);
            current = next;
        }
        FakeNodeRef leaf = new FakeNodeRef("leaf").text("STOP").clickable(false);
        current.addChild(leaf);

        FakeUiTree tree = new FakeUiTree(top);
        FakeActionLog log = new FakeActionLog();

        boolean result = resolver.resolveAndClick(tree, TargetSpec.text("STOP"), log);

        assertFalse("the clickable ancestor is far beyond the bounded walk, so it must not be found",
                result);
        assertFalse(top.wasClicked());
    }

    @Test(timeout = 5000)
    public void ancestorWithinBound_isStillFound() {
        // Sanity check alongside the bound test above: a clickable ancestor
        // within the bound (well under 10 hops) must still resolve normally.
        FakeNodeRef top = new FakeNodeRef("top-clickable").clickable(true).enabled(true);
        FakeNodeRef mid1 = new FakeNodeRef("mid1").clickable(false);
        FakeNodeRef mid2 = new FakeNodeRef("mid2").clickable(false);
        FakeNodeRef leaf = new FakeNodeRef("leaf").text("STOP").clickable(false);
        top.addChild(mid1);
        mid1.addChild(mid2);
        mid2.addChild(leaf);

        FakeUiTree tree = new FakeUiTree(top);
        FakeActionLog log = new FakeActionLog();

        boolean result = resolver.resolveAndClick(tree, TargetSpec.text("STOP"), log);

        assertTrue(result);
        assertTrue(top.wasClicked());
    }

    // ---- Defensive: must never throw on null inputs ------------------------

    @Test
    public void nullTree_returnsFalseWithoutThrowing() {
        FakeActionLog log = new FakeActionLog();
        boolean result = resolver.resolveAndClick(null, TargetSpec.text("INIT"), log);
        assertFalse(result);
        assertEquals(1, log.entries.size());
    }

    /** Minimal in-test {@link ActionLog} recording every call for assertions. */
    private static final class FakeActionLog implements ActionLog {
        static final class Entry {
            final String label;
            final boolean clicked;
            final String reason;

            Entry(String label, boolean clicked, String reason) {
                this.label = label;
                this.clicked = clicked;
                this.reason = reason;
            }
        }

        final List<Entry> entries = new ArrayList<>();

        @Override
        public void logResolved(String label, boolean clicked, String reason) {
            entries.add(new Entry(label, clicked, reason));
        }

        Entry last() {
            return entries.get(entries.size() - 1);
        }
    }
}
