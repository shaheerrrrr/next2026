package com.next2026.robotreset.opmode;

/**
 * Controllable {@link Clock} test double: starts at a fixed instant and only
 * advances when the test explicitly calls {@link #advance}, so deadline
 * behavior can be exercised deterministically without {@code Thread.sleep}.
 */
final class FakeClock implements Clock {

    private long millis;

    FakeClock(long start) {
        this.millis = start;
    }

    void advance(long deltaMillis) {
        millis += deltaMillis;
    }

    @Override
    public long now() {
        return millis;
    }
}
