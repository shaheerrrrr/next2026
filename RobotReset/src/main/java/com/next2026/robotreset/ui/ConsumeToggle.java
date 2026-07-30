package com.next2026.robotreset.ui;

/**
 * Static singleton flag toggled from {@code KeyMonitorActivity}'s
 * consume/pass-through {@code Switch} and read by
 * {@code RobotResetService.onKeyEvent}. Lets the bench test both halves of
 * Step 0 independently: "does the event arrive" (always logged regardless of
 * this flag) versus "does returning true actually stop the Driver Station app
 * from seeing it" (only true when this flag is on).
 *
 * <p>Defaults to true (consume matched chords) since that's the intended
 * production behavior; the toggle exists to let a bench tester flip it off to
 * confirm pass-through still works when a chord is not meant to be eaten.
 */
public final class ConsumeToggle {

    private ConsumeToggle() {
    }

    private static volatile boolean consumeOnMatch = true;

    public static boolean isConsumeOnMatch() {
        return consumeOnMatch;
    }

    public static void setConsumeOnMatch(boolean value) {
        consumeOnMatch = value;
    }
}
