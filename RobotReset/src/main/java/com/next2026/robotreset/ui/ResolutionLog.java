package com.next2026.robotreset.ui;

import com.next2026.robotreset.resolve.ActionLog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Tiny static in-process ring buffer of recent resolver outcomes ("clicked
 * INIT", "STOP not found", "OpMode slot 2 disabled"), structurally mirroring
 * {@link KeyEventLog}: last {@value #MAX_ENTRIES} entries, plus a listener
 * registry so {@code StatusActivity} can update live. Never needs to survive
 * process death; this is a bench/observability instrument, not durable
 * state.
 *
 * <p>Distinct from {@link KeyEventLog}: that class logs raw key events
 * ("a chord arrived"), this one logs what the resolver/selector did about it
 * ("and here's what got clicked and why, or why not") — the two are shown as
 * separate lists so a "chord arrived but nothing happened" case is visible
 * as a resolution-log entry with {@code clicked=false} even though the key
 * event itself was consumed.
 *
 * <p><b>Usage as the {@link ActionLog} implementation:</b> this class is a
 * singleton by construction (all state is static), so a single shared
 * instance — e.g. {@code new ResolutionLog()}, or any instance, since they're
 * all backed by the same static buffer — can be handed to the resolver
 * wherever an {@code ActionLog} is required. Do not read anything from
 * instance fields; there are none. Instance methods only exist to satisfy
 * the {@link ActionLog} interface.
 */
public final class ResolutionLog implements ActionLog {

    public static final class Entry {
        public final long timestamp;
        public final String label;
        public final boolean clicked;
        public final String reason;

        public Entry(long timestamp, String label, boolean clicked, String reason) {
            this.timestamp = timestamp;
            this.label = label;
            this.clicked = clicked;
            this.reason = reason;
        }
    }

    public interface Listener {
        void onEntryAdded(Entry entry);
    }

    private static final int MAX_ENTRIES = 50;
    private static final Deque<Entry> ENTRIES = new ArrayDeque<>(MAX_ENTRIES);
    private static final List<Listener> LISTENERS = new ArrayList<>();

    @Override
    public void logResolved(String label, boolean clicked, String reason) {
        add(label, clicked, reason);
    }

    public static synchronized void add(String label, boolean clicked, String reason) {
        // Mirror to logcat as well as the in-process buffer: the buffer serves
        // the on-device Status screen (no cable needed), logcat serves bench
        // debugging over a cable. Both matter — see docs/robot-reset-app-brief.md.
        android.util.Log.d("RobotReset",
                "resolve label=" + label + " clicked=" + clicked + " reason=" + reason);

        Entry entry = new Entry(System.currentTimeMillis(), label, clicked, reason);
        if (ENTRIES.size() >= MAX_ENTRIES) {
            ENTRIES.removeLast();
        }
        ENTRIES.addFirst(entry);
        for (Listener listener : new ArrayList<>(LISTENERS)) {
            listener.onEntryAdded(entry);
        }
    }

    /** Newest entry first. */
    public static synchronized List<Entry> getAll() {
        return new ArrayList<>(ENTRIES);
    }

    public static synchronized void addListener(Listener listener) {
        LISTENERS.add(listener);
    }

    public static synchronized void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }
}
