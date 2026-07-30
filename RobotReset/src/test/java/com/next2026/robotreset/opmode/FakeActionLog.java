package com.next2026.robotreset.opmode;

import java.util.ArrayList;
import java.util.List;

import com.next2026.robotreset.resolve.ActionLog;

/** Records every call for assertions instead of forwarding anywhere. */
final class FakeActionLog implements ActionLog {

    final List<String> entries = new ArrayList<>();

    @Override
    public void logResolved(String label, boolean clicked, String reason) {
        entries.add(label + "|" + clicked + "|" + reason);
    }
}
