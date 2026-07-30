package com.next2026.robotreset;

import android.view.KeyEvent;

/**
 * Pure decoding of the Ctrl+Alt+F1..F8 trigger chords into commands. No
 * Android framework dependency beyond the {@link KeyEvent} keycode/meta
 * constants, so this is unit-testable on the plain JVM without
 * instrumentation.
 *
 * <p>Chords were deliberately chosen (see docs/design-accessibility-tap.md)
 * to be implausible as genuine driver input. That guarantee only holds if the
 * decoder requires an *exact* modifier match — Ctrl+Alt and nothing else. A
 * decoder that also fires with Shift or Meta held would fire on a superset of
 * real-world key combinations and weaken the guarantee, so exact match is
 * required here, not "at least Ctrl+Alt".
 */
public final class ChordDecoder {

    private ChordDecoder() {
    }

    public enum Command { NONE, INIT, START, STOP, OPMODE_SLOT }

    public static final class Decoded {
        public final Command command;
        public final int slotIndex; // meaningful only when command == OPMODE_SLOT

        public Decoded(Command command, int slotIndex) {
            this.command = command;
            this.slotIndex = slotIndex;
        }

        static Decoded none() {
            return new Decoded(Command.NONE, -1);
        }

        static Decoded of(Command command) {
            return new Decoded(command, -1);
        }

        static Decoded opmodeSlot(int slotIndex) {
            return new Decoded(Command.OPMODE_SLOT, slotIndex);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Decoded)) {
                return false;
            }
            Decoded other = (Decoded) o;
            return command == other.command && slotIndex == other.slotIndex;
        }

        @Override
        public int hashCode() {
            return 31 * command.hashCode() + slotIndex;
        }

        @Override
        public String toString() {
            return "Decoded{command=" + command + ", slotIndex=" + slotIndex + "}";
        }
    }

    public static Decoded decode(int keyCode, int metaState) {
        // Require exactly Ctrl+Alt: no Shift, no Meta, and both Ctrl and Alt
        // present. KeyEvent.META_*_ON are the generic (left-or-right) bits,
        // already set regardless of which physical Ctrl/Alt key was pressed.
        boolean shiftHeld = (metaState & KeyEvent.META_SHIFT_ON) != 0;
        boolean metaHeld = (metaState & KeyEvent.META_META_ON) != 0;
        boolean ctrlHeld = (metaState & KeyEvent.META_CTRL_ON) != 0;
        boolean altHeld = (metaState & KeyEvent.META_ALT_ON) != 0;

        if (shiftHeld || metaHeld || !ctrlHeld || !altHeld) {
            return Decoded.none();
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_F1:
                return Decoded.of(Command.INIT);
            case KeyEvent.KEYCODE_F2:
                return Decoded.of(Command.START);
            case KeyEvent.KEYCODE_F3:
                return Decoded.of(Command.STOP);
            case KeyEvent.KEYCODE_F5:
                return Decoded.opmodeSlot(0);
            case KeyEvent.KEYCODE_F6:
                return Decoded.opmodeSlot(1);
            case KeyEvent.KEYCODE_F7:
                return Decoded.opmodeSlot(2);
            case KeyEvent.KEYCODE_F8:
                return Decoded.opmodeSlot(3);
            default:
                return Decoded.none();
        }
    }
}
