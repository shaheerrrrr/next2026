# Bring-Up Runbook: `robot-reset-app` on Fable's Driver Station Phone

Procedural checklist for the first hardware session. Everything before this point was
verified on an emulator and in JVM unit tests; this document covers what only real
hardware can answer.

Read `robot-reset-app-brief.md` section 7 for why Step 0 exists. Read
`e2e-harness.md` for what the emulator already proved and what it structurally cannot.

## What is already verified, and what is not

| Claim | Status |
| --- | --- |
| Element resolution, clickable-ancestor walk, disabled-node no-op | Verified on emulator (7/7 E2E) + 29 JVM unit tests |
| OpMode dropdown open / wait / scroll / match, from a cold dropdown | Verified on emulator |
| Fail-safe: absent or disabled target produces no click | Verified on emulator, both negative controls |
| `FLAG_REQUEST_FILTER_KEY_EVENTS` took effect | Verified — `dumpsys accessibility` reports `KeyboardInterceptor` active |
| **A real USB HID chord reaches `onKeyEvent`** | **UNVERIFIED — this is Step 0** |
| **Consuming the chord hides it from the DS app** | **UNVERIFIED** |
| DS app resource-ids and button text | **GUESSED** — see "Capture the real ids" below |

`adb shell input keyevent` cannot substitute for a real keyboard here: it injects via
`InputManager`, which never reaches the accessibility `KeyboardInterceptor` stage. That is
why Step 0 requires physical hardware and cannot be automated.

## The constraint that shapes the whole session

**The phone has one USB port.** A keyboard on an OTG adapter and an `adb` cable cannot both
occupy it. This is the same class of role conflict that killed the `adb shell input tap`
approach (brief section 3) — but it is only an inconvenience here, not a blocker, because
the app reports Step 0's result **on its own screen**. The Key Monitor exists precisely so
the chord test needs no cable.

Sequence accordingly: install over USB → unplug → attach keyboard → read the result on
screen → unplug keyboard → reattach USB for the id capture.

Optional convenience for the later steps: `adb tcpip 5555` then
`adb connect <phone-ip>:5555` gives you wireless `adb` so you can watch `logcat` while the
keyboard is attached. It does not survive a reboot and must be re-armed, so treat it as a
bench convenience only.

## Setup

```powershell
. .\tools\env.ps1
$adb = "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe"
```

Make sure no emulator is running first, or `adb` commands become ambiguous:

```powershell
& $adb devices -l          # should list exactly one real device once connected
```

If an emulator is up, close it, or target the phone explicitly with `& $adb -s <serial> ...`.

## 1. Connect and identify the phone

Accept the "Allow USB debugging" prompt on the phone if it appears.

```powershell
& $adb devices -l
& $adb shell getprop ro.build.version.release   # Android version
& $adb shell getprop ro.build.version.sdk       # API level
& $adb shell getprop ro.product.model
```

Record the API level. It determines whether the Android 13+ "restricted setting" applies
(brief risk 2). The app's `minSdk` is 24, so anything from Android 7 up will install.

## 2. Install

```powershell
& $adb install -r RobotReset\build\outputs\apk\debug\RobotReset-debug.apk
```

## 3. Enable the accessibility service

Prefer `adb` over the Settings UI. It is more reliable and **bypasses the Android 13+
"restricted setting" block entirely**, since the shell UID already holds
`WRITE_SECURE_SETTINGS`.

**Read the current value first** — this key holds a colon-separated list, and blindly
overwriting it would silently disable any accessibility service already in use (TalkBack,
a switch-access tool, etc.):

```powershell
& $adb shell settings get secure enabled_accessibility_services
```

If that prints `null` or is empty, set it directly:

```powershell
& $adb shell settings put secure enabled_accessibility_services com.next2026.robotreset/com.next2026.robotreset.RobotResetService
& $adb shell settings put secure accessibility_enabled 1
```

If it printed an existing service, append instead, preserving what was there:

```powershell
& $adb shell settings put secure enabled_accessibility_services "<existing>:com.next2026.robotreset/com.next2026.robotreset.RobotResetService"
& $adb shell settings put secure accessibility_enabled 1
```

Confirm it bound:

```powershell
& $adb shell dumpsys accessibility | Select-String "Bound services|KeyboardInterceptor"
```

You want to see `Robot Reset` among the bound services and `KeyboardInterceptor` in the
enabled features. `KeyboardInterceptor` is the direct evidence that
`FLAG_REQUEST_FILTER_KEY_EVENTS` took effect — without it, `onKeyEvent` will never fire and
Step 0 fails for a reason unrelated to the design.

If you enable it through Settings → Accessibility instead and Android blocks it, clear the
restriction via Settings → Apps → Robot Reset → ⋮ → **Allow restricted settings**, then
retry the toggle.

## 4. STEP 0 — the hard gate

Open **Robot Reset** from the app drawer. It launches straight into Key Monitor. Confirm the
header reads **"Accessibility service: RUNNING"** and leave **"Consume matched chords"**
switched **on**.

Now unplug USB and attach a USB keyboard through an OTG adapter.

**Press `Ctrl+Alt+F1`.**

Expected: a new line appears at the top of the key event log, roughly

```
HH:MM:SS.mmm  keyCode=131  meta=0x...  cmd=INIT  consumed=y
```

Then run two controls:

- **Press bare `F1`** (no modifiers). Expect either no line, or a line with `cmd=NONE`.
  This confirms interception is chord-specific and will not fire on ordinary input.
- **Press `Ctrl+Alt+F5`.** Expect `cmd=OPMODE_SLOT`.

### Decision point

| Outcome | Meaning |
| --- | --- |
| Chord lines appear | Step 0 **passes**. The load-bearing assumption holds. Continue. |
| Nothing appears at all | The trigger mechanism is wrong. **Stop.** Per the brief, most of the design is void and the transport must be reconsidered — do not work around it. |
| Lines appear but `cmd=NONE` for a chord | The chord arrives but modifiers decode differently on this phone. Report the exact `meta=0x...` value; `ChordDecoder`'s modifier match needs adjusting, which is a small fix. |

Note the keycode and meta values in all cases — they are the raw truth about what this
phone's keyboard actually sends.

## 5. Confirm consumption

With **"Consume matched chords" on**, foreground the FTC Driver Station app and press
`Ctrl+Alt+F1`. The DS app should show no sign of the keystroke. Toggling the switch **off**
and repeating is the comparison case: the event is still logged, but is no longer consumed,
so anything that does respond to F1 will now see it.

This is the second half of Step 0 and the thing the emulator harness structurally could not
test.

## 6. Capture the real DS app ids

Reattach USB. Foreground the Driver Station app, then:

```powershell
& $adb shell pm list packages | Select-String qualcomm     # confirm the real package name
.\tools\uiautomator-dump.ps1                                # dumps + pulls window_dump.xml
```

Do this **twice**: once on the main DS screen, and once with the OpMode dropdown open (the
list contents only exist in the tree while it is open).

In the resulting XML, find the nodes whose `text` is `INIT`, `START`, `STOP`, and the OpMode
selection control, and record for each:

- `resource-id`
- `text`
- `class`
- `clickable` — note whether the *matched* node is clickable or whether a parent is
  (this is Gotcha 2; the resolver already walks up, but it is worth knowing)
- `enabled` — INIT should read `false` before an OpMode is selected

The brief *believes* the package is `com.qualcomm.ftcdriverstation` but says to verify rather
than assume. Verify.

## 7. Apply the real ids

Every target currently defaults to a text match, which is the more robust guess:

| Target | Current default |
| --- | --- |
| INIT / START / STOP | `TEXT("INIT")` / `TEXT("START")` / `TEXT("STOP")` |
| OpMode dropdown | `TEXT("Select OpMode")` |
| OpMode scroll container | `VIEW_ID("opmode_list_container")` — almost certainly wrong |
| OpMode slot 0..3 names | `"OpMode Slot 0".."OpMode Slot 3"` |

The scroll container being wrong is tolerated by design: `OpModeSelector` falls back to
scanning for any node with `isScrollable()`, so a miss costs nothing.

Where the dump disagrees with a default, fix it in the app rather than rebuilding — open
**Config** and set the override, or set the OpMode slot names to the real OpMode names you
want on slots 0-3. `TargetConfig` persists these in `SharedPreferences`, which is the whole
reason a wrong guess is a settings change instead of a new APK.

## 8. Full bring-up

With the keyboard attached and the DS app foregrounded:

1. `Ctrl+Alt+F5..F8` → the intended OpMode is selected by name.
2. `Ctrl+Alt+F1` → INIT is pressed (only possible once an OpMode is selected).
3. `Ctrl+Alt+F2` → START. `Ctrl+Alt+F3` → STOP.

Then verify the fail-safe property on real hardware: navigate the phone **away** from the DS
app and press `Ctrl+Alt+F1`. **Nothing should happen.** That inversion — nothing, rather than
something wrong — is the entire justification for this design over the reverted coordinate
tap, and it is worth confirming with your own eyes on the real app.

Robot Reset's **Status** screen shows the resolution log (what was clicked, and the reason
when it was not), which is the no-cable way to inspect all of the above. Over a cable,
`tools\logcat-robotreset.ps1` shows the same trace plus per-key detail.

## 9. Pre-session check, every session afterward

Service enablement is not remotely observable, and nobody is at this phone once the robot is
out. Before relying on it: open Robot Reset and confirm **"Accessibility service: RUNNING"**.
The service does survive reboot once enabled, unlike `adb tcpip` or Shizuku — but confirm,
do not assume.

## Still not solved after this session

The driver gets **no acknowledgement**. Failure is safe rather than dangerous, which is the
point, but the LoRa link is transmit-only so success cannot be confirmed remotely. The
existing 5.8 GHz FPV camera path pointed at the phone screen is the practical mitigation.

The LoRa path itself (`BTN_UI_CMD`, `fable/fable.ino`, `driver_station_flask.py`) is not part
of this app and is not built. Those files live on the `ftc-lora` / `remote-adb` branches.
