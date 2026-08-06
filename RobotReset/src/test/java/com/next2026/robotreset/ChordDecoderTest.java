package com.next2026.robotreset;

import android.view.KeyEvent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Plain JVM unit tests for {@link ChordDecoder}. Per its class doc comment,
 * {@code ChordDecoder} has no Android framework dependency beyond
 * {@link KeyEvent}'s keycode/meta constants, which are {@code public static
 * final int} fields inlined at compile time -- no instrumentation or
 * Robolectric is needed to exercise them.
 *
 * <p>Previously untested (noted as a gap in docs/e2e-harness.md); added here
 * alongside the {@code LAUNCH_DS}/F4 chord so the whole decode table gets
 * real coverage in one pass, not just the new case.
 */
public class ChordDecoderTest {

    private static final int CTRL_ALT =
            KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON;

    @Test
    public void f1WithCtrlAlt_decodesToInit() {
        ChordDecoder.Decoded decoded = ChordDecoder.decode(KeyEvent.KEYCODE_F1, CTRL_ALT);
        assertEquals(new ChordDecoder.Decoded(ChordDecoder.Command.INIT, -1), decoded);
    }

    @Test
    public void f2WithCtrlAlt_decodesToStart() {
        ChordDecoder.Decoded decoded = ChordDecoder.decode(KeyEvent.KEYCODE_F2, CTRL_ALT);
        assertEquals(new ChordDecoder.Decoded(ChordDecoder.Command.START, -1), decoded);
    }

    @Test
    public void f3WithCtrlAlt_decodesToStop() {
        ChordDecoder.Decoded decoded = ChordDecoder.decode(KeyEvent.KEYCODE_F3, CTRL_ALT);
        assertEquals(new ChordDecoder.Decoded(ChordDecoder.Command.STOP, -1), decoded);
    }

    @Test
    public void f4WithCtrlAlt_decodesToLaunchDs() {
        ChordDecoder.Decoded decoded = ChordDecoder.decode(KeyEvent.KEYCODE_F4, CTRL_ALT);
        assertEquals(new ChordDecoder.Decoded(ChordDecoder.Command.LAUNCH_DS, -1), decoded);
    }

    @Test
    public void f5ThroughF8WithCtrlAlt_decodeToOpmodeSlots0Through3() {
        assertEquals(new ChordDecoder.Decoded(ChordDecoder.Command.OPMODE_SLOT, 0),
                ChordDecoder.decode(KeyEvent.KEYCODE_F5, CTRL_ALT));
        assertEquals(new ChordDecoder.Decoded(ChordDecoder.Command.OPMODE_SLOT, 1),
                ChordDecoder.decode(KeyEvent.KEYCODE_F6, CTRL_ALT));
        assertEquals(new ChordDecoder.Decoded(ChordDecoder.Command.OPMODE_SLOT, 2),
                ChordDecoder.decode(KeyEvent.KEYCODE_F7, CTRL_ALT));
        assertEquals(new ChordDecoder.Decoded(ChordDecoder.Command.OPMODE_SLOT, 3),
                ChordDecoder.decode(KeyEvent.KEYCODE_F8, CTRL_ALT));
    }

    @Test
    public void bareF4NoModifiers_decodesToNone() {
        ChordDecoder.Decoded decoded = ChordDecoder.decode(KeyEvent.KEYCODE_F4, 0);
        assertEquals(ChordDecoder.Command.NONE, decoded.command);
    }

    @Test
    public void f4WithOnlyCtrl_decodesToNone() {
        // Partial modifier match must not fire -- see the exact-match
        // guarantee documented on ChordDecoder itself.
        ChordDecoder.Decoded decoded = ChordDecoder.decode(KeyEvent.KEYCODE_F4, KeyEvent.META_CTRL_ON);
        assertEquals(ChordDecoder.Command.NONE, decoded.command);
    }

    @Test
    public void f4WithCtrlAltShift_decodesToNone() {
        // A superset of Ctrl+Alt (extra Shift held) must not fire either --
        // this is what makes the chord implausible as genuine driver input.
        int meta = CTRL_ALT | KeyEvent.META_SHIFT_ON;
        ChordDecoder.Decoded decoded = ChordDecoder.decode(KeyEvent.KEYCODE_F4, meta);
        assertEquals(ChordDecoder.Command.NONE, decoded.command);
    }

    @Test
    public void f4WithCtrlAltMeta_decodesToNone() {
        int meta = CTRL_ALT | KeyEvent.META_META_ON;
        ChordDecoder.Decoded decoded = ChordDecoder.decode(KeyEvent.KEYCODE_F4, meta);
        assertEquals(ChordDecoder.Command.NONE, decoded.command);
    }

    @Test
    public void unmappedKeyWithCtrlAlt_decodesToNone() {
        ChordDecoder.Decoded decoded = ChordDecoder.decode(KeyEvent.KEYCODE_A, CTRL_ALT);
        assertEquals(ChordDecoder.Command.NONE, decoded.command);
    }
}
