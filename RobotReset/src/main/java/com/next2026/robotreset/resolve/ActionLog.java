package com.next2026.robotreset.resolve;

/**
 * Sink for resolver outcomes. A real implementation might forward into
 * {@code com.next2026.robotreset.ui.KeyEventLog} or android.util.Log; a fake
 * implementation used in unit tests can just record calls for assertions.
 */
public interface ActionLog {

    void logResolved(String label, boolean clicked, String reason);
}
