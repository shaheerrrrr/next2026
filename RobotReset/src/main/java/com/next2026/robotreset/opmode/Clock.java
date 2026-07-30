package com.next2026.robotreset.opmode;

/**
 * Seam for {@link OpModeSelector}'s overall deadline check. Production code
 * uses {@link System#currentTimeMillis()} (see {@code OpModeSelector}'s
 * default constructors); unit tests inject a fake so elapsed time can be
 * advanced deterministically without {@code Thread.sleep}.
 */
public interface Clock {
    long now();
}
