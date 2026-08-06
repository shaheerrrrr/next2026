package com.next2026.robotreset.launch;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import com.next2026.robotreset.resolve.ActionLog;

/**
 * Launches the FTC Driver Station app onto the screen, forcing a fresh task
 * rather than merely resuming whatever back stack it may already have.
 *
 * <p>This is a different kind of action than every other command this app
 * drives: INIT/START/STOP/OPMODE_SLOT resolve and click an element that is
 * already on screen inside the DS app, and fail safe (do nothing) if it
 * isn't. This class instead brings the DS app itself onto the screen from
 * <em>any</em> state -- home screen, a different app, or a DS sub-screen --
 * which is why it goes through {@link PackageManager#getLaunchIntentForPackage}
 * rather than {@link com.next2026.robotreset.resolve.Resolver}.
 *
 * <p><b>This is not {@code adb shell am force-stop}.</b> No public Android API
 * lets one app kill another app's process; that requires shell/system
 * privilege this app does not have and should not try to acquire. What this
 * class does instead is discard the DS app's existing task back stack via
 * {@link Intent#FLAG_ACTIVITY_CLEAR_TASK} and rebuild it from the launcher
 * activity -- equivalent to swiping the app out of Recents and re-tapping its
 * icon. A genuinely wedged process (see docs/bring-up.md's "Driver Station
 * app has its own pre-existing crash bug" section) is not cured by this; only
 * its visible task state is reset.
 */
public final class DriverStationLauncher {

    /**
     * The FTC Driver Station app's package name. Hardcoded rather than a
     * {@code TargetConfig} override: unlike view-ids and button text (which
     * are genuinely uncertain per-phone guesses, per
     * docs/robot-reset-app-brief.md Risk 3), this has been directly confirmed
     * identical across Fable, Flash, and Sol's phones (docs/bring-up.md) --
     * Sol's "NEXT-A-DS" skin is a reskin of the same APK, not a different
     * package.
     */
    public static final String DS_PACKAGE = "com.qualcomm.ftcdriverstation";

    private DriverStationLauncher() {
    }

    /**
     * Attempts to bring the DS app to the foreground in a fresh task. Never
     * throws: a missing/renamed package is reported through {@code log}
     * exactly like a failed element resolution, not as an exception, so a
     * bad chord never crashes the accessibility service.
     *
     * @return true if the launch intent was actually dispatched (not proof
     *         the DS app successfully rendered -- this app has no way to
     *         observe that, same limitation as every other command here).
     */
    public static boolean launch(Context context, ActionLog log) {
        String label = "LAUNCH_DS:" + DS_PACKAGE;
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(DS_PACKAGE);
            if (intent == null) {
                log.logResolved(label, false, "package not found or has no launcher activity");
                return false;
            }
            // NEW_TASK: required when starting an Activity from a Service/
            // non-Activity context. CLEAR_TASK: the force-relaunch -- discards
            // whatever back stack/sub-screen the DS app was already on and
            // rebuilds from its launcher activity, so this always lands on
            // the DS app's top-level screen regardless of where it was left.
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(intent);
            log.logResolved(label, true, "launched");
            return true;
        } catch (RuntimeException e) {
            // Must never throw out of a chord handler -- mirrors
            // ResolverImpl.resolveAndClick's same guarantee.
            log.logResolved(label, false, "exception during launch: " + e);
            return false;
        }
    }
}
