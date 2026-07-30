package com.next2026.robotreset.ui;

import com.next2026.robotreset.ChordDecoder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Tiny static in-process ring buffer of recent key events seen by
 * {@code RobotResetService}, plus a listener registry so
 * {@code KeyMonitorActivity} can update live. Never needs to survive process
 * death; this is a bench/observability instrument, not durable state.
 */
public final class KeyEventLog {

    private KeyEventLog() {
    }

    public static final class Entry {
        public final long timestamp;
        public final int keyCode;
        public final int metaState;
        public final ChordDecoder.Command command;
        public final boolean consumed;

        public Entry(long timestamp, int keyCode, int metaState,
                     ChordDecoder.Command command, boolean consumed) {
            this.timestamp = timestamp;
            this.keyCode = keyCode;
            this.metaState = metaState;
            this.command = command;
            this.consumed = consumed;
        }
    }

    public interface Listener {
        void onEntryAdded(Entry entry);
    }

    private static final int MAX_ENTRIES = 50;
    private static final Deque<Entry> ENTRIES = new ArrayDeque<>(MAX_ENTRIES);
    private static final List<Listener> LISTENERS = new ArrayList<>();

    public static synchronized void add(int keyCode, int metaState,
                                         ChordDecoder.Command command, boolean consumed) {
        Entry entry = new Entry(System.currentTimeMillis(), keyCode, metaState, command, consumed);
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
