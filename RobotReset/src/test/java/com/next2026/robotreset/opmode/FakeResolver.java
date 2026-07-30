package com.next2026.robotreset.opmode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.next2026.robotreset.resolve.ActionLog;
import com.next2026.robotreset.resolve.Resolver;
import com.next2026.robotreset.resolve.TargetSpec;
import com.next2026.robotreset.resolve.UiTree;

/**
 * Controllable {@link Resolver} test double. Test cases queue up a fixed
 * sequence of results: the first result answers the {@code begin()} call
 * that opens the dropdown, and each subsequent result answers one {@code
 * onContentChanged} target-text match attempt, in call order. Once the
 * queue is drained, further calls return {@code false} (matching "target
 * never found") rather than throwing, so tests proving termination past the
 * attempt bound don't need to over-provision the queue.
 */
final class FakeResolver implements Resolver {

    private final Deque<Boolean> queuedResults = new ArrayDeque<>();
    final List<TargetSpec> specsSeen = new ArrayList<>();
    int callCount;

    FakeResolver(boolean... results) {
        for (boolean r : results) {
            queuedResults.add(r);
        }
    }

    @Override
    public boolean resolveAndClick(UiTree tree, TargetSpec spec, ActionLog log) {
        callCount++;
        specsSeen.add(spec);
        if (queuedResults.isEmpty()) {
            return false;
        }
        return queuedResults.poll();
    }
}
