package com.next2026.robotreset.launch;

import android.app.Activity;
import android.app.KeyguardManager;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;

import com.next2026.robotreset.ui.ResolutionLog;

/**
 * Transient, invisible relay activity: requests that the screen wake and an
 * insecure keyguard dismiss, then hands off to {@link DriverStationLauncher}
 * and finishes itself.
 *
 * <p>Why this exists as a separate Activity rather than being done directly
 * from {@code RobotResetService}: a {@code Service} has no window and cannot
 * show anything over an active keyguard or turn the screen on -- only an
 * Activity's own window flags can do that. This activity is themed
 * translucent/no-history (see the manifest entry) so it produces no visible
 * flash or Recents entry on the common case of an already-awake, unlocked
 * phone; it still runs the same code path every time rather than branching
 * on screen state first, so this logic is exercised on every real chord
 * instead of only being reachable once the phone happens to be locked.
 *
 * <h3>Confirmed on real hardware (Fable, Samsung Galaxy S20 FE, API 33)</h3>
 * <ul>
 *   <li>DS app backgrounded, or on a different screen/app, phone awake and
 *       unlocked: reliably works. The DS app's real activity becomes the
 *       foreground app every time.</li>
 *   <li>Phone genuinely asleep (screen off, keyguard showing): reliably
 *       works (4/4 across two sessions) <b>once RobotReset is granted
 *       Samsung's "Unrestricted" battery access</b> (Settings &rarr; Apps
 *       &rarr; Robot Reset &rarr; Battery &rarr; Unrestricted). Without it,
 *       the DS app's activity still becomes the {@code ActivityManager}'s
 *       {@code ResumedActivity} internally, but the physical display never
 *       turns on and the keyguard never dismisses -- confirmed reproducible
 *       across every code-level variation tried first (this class's current
 *       code, the same work in {@code onCreate()} instead of deferred to
 *       {@code onResume()}, an explicit {@code PowerManager.FULL_WAKE_LOCK}
 *       with {@code ACQUIRE_CAUSES_WAKEUP}, and the <em>stock AOSP</em> Doze
 *       whitelist via {@code dumpsys deviceidle whitelist}). That last point
 *       is the key finding: Samsung's OneUI battery management is a
 *       <b>separate, stricter layer on top of</b> stock Android Doze --
 *       exempting an app from AOSP's doze allowlist does not exempt it from
 *       Samsung's own restrictions, and only the Samsung-specific
 *       "Unrestricted" setting does. This is a one-time manual device setup
 *       step (not something this app can grant itself; there is no
 *       Samsung-specific public API for it), so it must be set on each
 *       Samsung phone in the fleet and is worth a bring-up checklist item.
 *       Flash (same hardware/OS as Fable) is expected to need the same
 *       setting; Sol (different OEM, older Android, no OneUI) is untested
 *       and may not need it at all, or may need an OEM-specific equivalent
 *       -- don't assume either way.</li>
 * </ul>
 *
 * <h3>Keyguard dismissal is fail-safe by construction</h3>
 * {@link KeyguardManager#requestDismissKeyguard} only dismisses an
 * <em>insecure</em> keyguard (screen lock set to "None" or "Swipe") --
 * exactly this phone's current configuration. If a PIN/pattern/password is
 * ever set, the same call cannot bypass it: Android shows the real unlock
 * prompt instead, and the DS app is not launched until a person satisfies
 * that. That is the same fail-safe property the rest of this app is built
 * around (see docs/design-accessibility-tap.md) -- this class does not
 * attempt to work around a real lock, only an absent one.
 *
 * <h3>SDK split</h3>
 * {@link Activity#setShowWhenLocked} and {@link Activity#setTurnScreenOn}
 * (the modern replacement for the deprecated window flags below) only exist
 * on API 27+. This app's {@code minSdk} is 24, and Sol's phone is API 26
 * (Android 8.0) -- so the pre-27 branch is Sol's actual production code
 * path, not just defensive coverage for hardware nobody uses. Neither
 * branch requires the {@code WAKE_LOCK} permission: that permission gates
 * {@code PowerManager.WakeLock}, not these window-level flags, so this stays
 * consistent with the app requesting no permissions beyond
 * {@code BIND_ACCESSIBILITY_SERVICE}.
 */
public final class DsLaunchActivity extends Activity {

    private boolean launched = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) { // API 27+: Fable, Flash
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            // API 24-26 (Sol is API 26): only the deprecated window-flag
            // path exists. FLAG_DISMISS_KEYGUARD here plus the explicit
            // requestDismissKeyguard() call below are redundant on API 26
            // but that redundancy is deliberate -- cheap, and either alone
            // has historically had version-specific quirks.
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // API 26+: all three phones
            KeyguardManager keyguardManager = getSystemService(KeyguardManager.class);
            if (keyguardManager != null) {
                keyguardManager.requestDismissKeyguard(this, null);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Deliberately deferred from onCreate(): finishing synchronously
        // inside onCreate(), before this translucent activity's window was
        // ever actually attached/shown, was the first hypothesis tried for
        // why the sleeping-phone case (see class doc comment above) wasn't
        // working. Waiting for onResume() -- which only fires once this
        // activity's own window has genuinely reached the foreground -- is
        // the technically correct place to rely on window-affecting flags
        // having taken effect, so it's kept even though real-hardware
        // testing showed it was NOT sufficient by itself to fix the
        // sleeping-phone case (see class doc comment): whatever is blocking
        // that is a separate, still-unresolved issue. This costs nothing for
        // the already-reliable awake/backgrounded case, at the price of this
        // relay activity living fractionally longer than a single Binder
        // call.
        if (launched) {
            return; // guard: onResume can in principle fire again before finish() completes
        }
        launched = true;
        // Reuses the same ResolutionLog seam RobotResetService hands to the
        // Resolver -- ResolutionLog is a singleton by construction (see its
        // class doc comment), so a fresh instance here is equivalent to the
        // service's own, and the outcome lands on the same Status screen /
        // logcat trace as every other command.
        DriverStationLauncher.launch(this, new ResolutionLog());
        finish();
    }
}
