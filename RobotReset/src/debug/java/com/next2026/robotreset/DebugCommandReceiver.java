package com.next2026.robotreset;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.util.Log;

/**
 * DEBUG-BUILD-ONLY test seam. This class lives under {@code src/debug/},
 * not {@code src/main/}, and is declared only in {@code src/debug/AndroidManifest.xml}
 * -- it is folded into debug builds by AGP's manifest merger and is
 * physically absent from the release APK's classes and manifest. That is a
 * hard requirement, not a style choice: a broadcast that can press a robot's
 * INIT/START/STOP must not exist in a build a user could install. See the
 * comment at the top of {@code src/debug/AndroidManifest.xml} for the merge
 * mechanics.
 *
 * <p>Purpose: let {@code tools/e2e.ps1} (the emulator E2E harness) drive the
 * exact same {@link RobotResetService} resolution path a real
 * Ctrl+Alt+F1..F8 USB HID keyboard chord drives, without needing a real
 * hardware key event. {@code adb shell input keyevent/keycombination}
 * injects through {@code InputManager}, which never reaches
 * {@code onKeyEvent} on an AccessibilityService's KeyboardInterceptor stage
 * -- that stage only observes real hardware input devices, on an emulator or
 * otherwise. See the finding recorded at the top of {@code tools/e2e.ps1}
 * and in {@code docs/e2e-harness.md}. This receiver is the only way to
 * exercise element resolution / OpMode selection end-to-end outside a bench
 * session with a real keyboard.
 *
 * <p>This deliberately forwards to {@link RobotResetService}'s existing
 * {@code handleCommand(ChordDecoder.Decoded)} rather than reimplementing any
 * part of it -- a test trigger that ran different code than production would
 * prove nothing about production behavior.
 *
 * <p>Usage from adb:
 * <pre>
 *   adb shell am broadcast -a com.next2026.robotreset.DEBUG_COMMAND \
 *       -p com.next2026.robotreset --es cmd INIT
 *   adb shell am broadcast -a com.next2026.robotreset.DEBUG_COMMAND \
 *       -p com.next2026.robotreset --es cmd OPMODE_SLOT --ei slot 3
 * </pre>
 */
public final class DebugCommandReceiver extends BroadcastReceiver {

    private static final String TAG = RobotResetService.TAG;

    public static final String ACTION = "com.next2026.robotreset.DEBUG_COMMAND";
    public static final String EXTRA_CMD = "cmd";   // one of ChordDecoder.Command's names
    public static final String EXTRA_SLOT = "slot"; // int, only meaningful for OPMODE_SLOT

    @Override
    public void onReceive(Context context, Intent intent) {
        // Threading: this receiver is registered with no Handler and never
        // calls goAsync(), so onReceive runs on the app process's main
        // thread -- the same Looper accessibility callbacks (onKeyEvent,
        // onAccessibilityEvent) run on. That is required, not incidental:
        // OpModeSelector is a single-threaded state machine with no internal
        // synchronization (see RobotResetService's class doc comment), so
        // driving handleCommand() from any other thread would be a data
        // race with a real chord's dispatch. Do not add a background
        // thread, an Executor, or goAsync() here.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.e(TAG, "DebugCommandReceiver.onReceive() off main thread -- refusing to dispatch, "
                    + "this would race OpModeSelector's state");
            return;
        }

        String cmd = intent.getStringExtra(EXTRA_CMD);
        if (cmd == null) {
            Log.w(TAG, "DEBUG_COMMAND broadcast missing string extra '" + EXTRA_CMD + "'");
            return;
        }

        // Not a ChordDecoder.Command: a separate diagnostic path (see
        // RobotResetService.dispatchDebugDumpTree()'s doc comment) that logs
        // the current window's clickable nodes instead of driving a chord.
        if ("DUMP_TREE".equals(cmd)) {
            RobotResetService.dispatchDebugDumpTree();
            return;
        }

        // Diagnostic-only: click the Nth clickable node (see
        // RobotResetService.dispatchDebugClickIndex()'s doc comment).
        if ("CLICK_INDEX".equals(cmd)) {
            int index = intent.getIntExtra(EXTRA_SLOT, -1);
            if (index < 0) {
                Log.w(TAG, "CLICK_INDEX requires a non-negative --ei slot <index>");
                return;
            }
            RobotResetService.dispatchDebugClickIndex(index);
            return;
        }

        // Debug-only convenience: set a TargetConfig override without going
        // through ConfigActivity's UI (which only has VIEW_ID/TEXT radio
        // buttons -- this is how TEXT_SIBLING overrides get set today).
        // Goes through the exact same TargetConfig.setOverride() a real
        // ConfigActivity save would call, so the persisted state is
        // identical either way.
        if ("SET_OVERRIDE".equals(cmd)) {
            String key = intent.getStringExtra("key");
            String kindStr = intent.getStringExtra("kind");
            String value = intent.getStringExtra("value");
            if (key == null || kindStr == null || value == null) {
                Log.w(TAG, "SET_OVERRIDE requires --es key <key> --es kind <kind> --es value <value>");
                return;
            }
            com.next2026.robotreset.resolve.TargetSpec.Kind kind;
            try {
                kind = com.next2026.robotreset.resolve.TargetSpec.Kind.valueOf(kindStr);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "SET_OVERRIDE unknown kind='" + kindStr + "'");
                return;
            }
            com.next2026.robotreset.config.TargetConfig.setOverride(context, key, kind, value);
            Log.i(TAG, "SET_OVERRIDE key=" + key + " kind=" + kind + " value=" + value);
            return;
        }

        ChordDecoder.Command command;
        try {
            command = ChordDecoder.Command.valueOf(cmd);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "DEBUG_COMMAND unknown cmd='" + cmd + "'");
            return;
        }
        if (command == ChordDecoder.Command.NONE) {
            Log.w(TAG, "DEBUG_COMMAND cmd=NONE is not dispatchable");
            return;
        }

        // -1 is fine for non-OPMODE_SLOT commands; OpModeSelector only reads
        // slotIndex when command == OPMODE_SLOT, mirroring ChordDecoder's own
        // Decoded.of(...) factory which uses -1 for the same case.
        int slot = intent.getIntExtra(EXTRA_SLOT, -1);
        ChordDecoder.Decoded decoded = new ChordDecoder.Decoded(command, slot);

        Log.i(TAG, "DebugCommandReceiver dispatching " + decoded);
        RobotResetService.dispatchDebugCommand(decoded);
    }
}
