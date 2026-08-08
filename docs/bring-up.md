# Bring-Up Runbook: `robot-reset-app` on Fable's Driver Station Phone

Procedural checklist for taking this app from source to a working chord-triggered
INIT/START/STOP/OpMode-select on a real phone. Everything routine (build, unit tests,
emulator E2E) is covered by `e2e-harness.md`; this document covers what only real
hardware answers, plus everything discovered doing that for the first time.

**Status: all of Step 0 and full bring-up (Sections 4 and 8) have been completed and
verified on real hardware** — a Samsung Galaxy S20 FE (Android 13 / API 33), a real
Adafruit Feather M0 running `fable.ino`, a real LoRa link, and the real FTC Driver
Station app talking to a real Robot Controller. This document was rewritten after that
session to fold in what was learned, so a fresh setup does not have to rediscover it.

Read `robot-reset-app-brief.md` section 7 for why Step 0 exists. Read `e2e-harness.md`
for what the emulator already proved and what it structurally cannot.

---

## 0. System overview — what you need before starting

This repo (branch `robot-reset-app`) builds **one piece** of a larger system: the
Android app that turns a USB HID keyboard chord into a click on the Driver Station
app's UI. It does not, by itself, produce a chord. To get an actual chord onto the
phone you also need the **transmitter side**, which lives on the `ftc-lora` branch of
this same repo (a separate git worktree, not this one):

```
Operator / dashboard (driver_station_flask.py, Flask on :8765)
  -> USB serial 115200
  -> transmitter board running driverstation.ino
  -> LoRa 915 MHz broadcast
  -> Feather M0 running fable.ino, plugged into the Driver Station phone via OTG
       -> presents a USB gamepad HID interface (tele-op, always on)
       -> presents a SECOND USB HID interface: a boot-layout keyboard, used only
          for Driver Station command chords (this is what this app intercepts)
  -> this app (RobotResetService, an AccessibilityService) intercepts the chord
     and clicks the corresponding element in the FTC Driver Station app
```

`fable.ino` is a **dumb chord emitter**: it has no concept of "INIT" or "START", only
a raw HID modifier byte + key usage decoded out of the LoRa frame's `lx` field. The
chord-to-meaning mapping lives in two places that must agree with each other:

- **What chord means what command** — `ui_commands.json` / the dashboard's "Chords..."
  panel, on the `ftc-lora` branch. See `ftc-lora:docs/protocol.md` ("Driver Station
  Command Injection") and `ftc-lora:docs/procedures.md` ("Driver Station Command
  Chords") for the transmitter-side reference.
- **What UI element that chord should click** — `ChordDecoder.java` (fixed mapping,
  F1/F2/F3 → INIT/START/STOP, F5-F8 → OpMode slots 0-3) and `TargetConfig` (which
  view-id or text each command resolves to), both in this repo.

If you are setting this up from scratch, get both sides going in this order:

1. Build and install this app (Sections 1-3, 6-7 below).
2. On the `ftc-lora` worktree: flash `fable.ino` onto the robot's Feather M0 (see
   `ftc-lora:docs/deployment.md`), and get `driver_station_flask.py` running with a
   transmitter board attached (see `ftc-lora:docs/procedures.md`).
3. Do Step 0 (Section 4) to confirm the chord physically reaches the phone.
4. Discover and apply the real Driver Station app ids (Sections 6-7).
5. Do full bring-up (Section 8) with a live Robot Controller connection.

## What is verified, and how

| Claim | Status |
| --- | --- |
| Element resolution, clickable-ancestor walk, disabled-node no-op | Verified on emulator (8/8 E2E) + JVM unit tests |
| OpMode dropdown open / wait / scroll / match, from a cold dropdown | Verified on emulator |
| Fail-safe: absent or disabled target produces no click | Verified on emulator, both negative controls |
| `FLAG_REQUEST_FILTER_KEY_EVENTS` took effect | Verified — `dumpsys accessibility` reports `KeyboardInterceptor` active |
| **A real USB HID chord (from the real Feather M0 trigger box, over the real LoRa link) reaches `onKeyEvent`** | **VERIFIED on real hardware** — see Section 4 |
| Consuming the chord vs. pass-through toggle | Verified — `consumed=true`/`false` behaves correctly per chord match |
| Real DS app resource-ids (this team's build) | **Verified** — see Section 7 for the discovered ids and how to redo this for a different DS app build |
| Full chord → real click on the real Driver Station app, with a live Robot Controller connection | **Verified** — OpMode select, INIT, START, STOP all confirmed working end-to-end |
| `LAUNCH_DS` brings the DS app to the foreground from a backgrounded/wrong-screen state, phone awake | **Verified on real hardware, all three robots (Fable, Flash, Sol)**, **with a real `Ctrl+Alt+F4` chord** over the real LoRa link, not just the debug broadcast — see "Opening the DS app itself" below. Sol has its own multi-second delay quirk, not seen on Fable/Flash. |
| `LAUNCH_DS` wakes the screen / dismisses the keyguard on a genuinely sleeping, locked phone | **Verified on real hardware, Fable and Flash** (Fable 4/4, Flash confirmed first attempt) — **requires Samsung's "Unrestricted" battery access to be granted first**. **Confirmed NOT working on Sol** (no OneUI, no equivalent setting found yet) — open problem, see "Opening the DS app itself" below |

`adb shell input keyevent` (or `keycombination`) cannot substitute for a real keyboard
here: it injects via `InputManager`, which never reaches the accessibility
`KeyboardInterceptor` stage. This is true on the emulator **and** on real hardware —
it is not an emulator limitation. That is why Step 0 requires physical hardware and a
real HID device, and cannot be automated with `adb input`.

## The constraints that shape the whole session

**The phone has one USB port.** A keyboard/Feather-M0 on an OTG adapter and an `adb`
cable cannot both occupy it at the same time. This is the same class of role conflict
that killed the `adb shell input tap` approach (brief section 3) — but it is only an
inconvenience here, not a blocker, because the app reports Step 0's result **on its
own screen** (the Key Monitor exists precisely so the chord test needs no cable), and
because wireless `adb` covers most of the rest.

**Wireless `adb` needs re-arming after any full USB disconnect, and it is tied to
whatever Wi-Fi network the phone is on at the time.** Concretely:

- `adb tcpip 5555` + `adb connect <ip>:5555` only works while the phone is on the
  same Wi-Fi network/subnet as the machine running `adb`. If the phone switches
  networks (e.g. from your bench Wi-Fi to the robot's own access point), the old
  wireless connection goes stale and a fresh `adb connect` to the phone's new IP
  will time out — you must plug in via USB again to re-arm it (`adb tcpip 5555`),
  then reconnect wirelessly on the new IP.
- It does not survive the phone fully losing its USB connection either (not just a
  reboot) — swapping the cable for an OTG keyboard/Feather and back can be enough to
  need `adb tcpip 5555` run again.
- Practical consequence: **the app's own on-screen Key Monitor / Status screens are
  the reliable fallback, not remote `logcat`.** If you cannot get wireless `adb`
  connected to the phone (e.g. because it is on the robot's Wi-Fi AP and your laptop
  isn't), read the results directly off the phone screen instead of fighting the
  network. This is not a workaround — it's a legitimate primary verification path,
  and it's why the Key Monitor / Status screens exist.
- The RobotReset app itself has **no dependency at all** on which Wi-Fi network (if
  any) the phone is on. It is a local `AccessibilityService` intercepting local USB
  HID input — no `INTERNET` permission, no network calls. Wi-Fi only matters for (a)
  your own `adb` debugging convenience, and (b) the Driver Station app's own
  connection to the Robot Controller, which is unrelated to this app's function.

Sequence accordingly: install over USB → unplug → attach keyboard/Feather M0 → read
the result on screen (or over wireless `adb` if it's cooperating) → swap back to USB
when you need to push a new build or pull an id capture.

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

Record the API level. It determines whether the Android 13+ "restricted setting"
applies (Section 3). The app's `minSdk` is 24, so anything from Android 7 up will
install. (Confirmed working on Android 13 / API 33, SM-G781U.)

## 2. Build and install

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot"
.\gradlew.bat :RobotReset:assembleDebug
& $adb install -r RobotReset\build\outputs\apk\debug\RobotReset-debug.apk
```

`gradlew.bat` needs a JVM on `PATH` or `JAVA_HOME` set just to *start* — the
`org.gradle.java.home` in `gradle.properties` only controls which JDK the Gradle
daemon itself uses once it's running, it doesn't help the wrapper launch. AGP 8.7 /
Gradle 8.13 require JDK 21; newer JDKs (17 works too) are not compatible with this
AGP version.

`adb install -r` (reinstall, keep data) preserves the accessibility service's enabled
state and `TargetConfig`'s `SharedPreferences` overrides across rebuilds — you do not
need to redo Sections 3, 6, 7 after every code change, only after a fresh install.

## 3. Enable the accessibility service

Prefer `adb` over the Settings UI. It is more reliable and **bypasses the Android 13+
"restricted setting" block entirely**, since the shell UID already holds
`WRITE_SECURE_SETTINGS`.

**Read the current value first** — this key holds a colon-separated list, and blindly
overwriting it would silently disable any accessibility service already in use
(TalkBack, a remote-support tool like AnyDesk/RustDesk, etc.):

```powershell
& $adb shell settings get secure enabled_accessibility_services
```

If that prints `null` or is empty, set it directly:

```powershell
& $adb shell settings put secure enabled_accessibility_services com.next2026.robotreset/com.next2026.robotreset.RobotResetService
& $adb shell settings put secure accessibility_enabled 1
```

If it printed existing services, append instead, preserving what was there:

```powershell
& $adb shell settings put secure enabled_accessibility_services "<existing>:com.next2026.robotreset/com.next2026.robotreset.RobotResetService"
& $adb shell settings put secure accessibility_enabled 1
```

Confirm it bound:

```powershell
& $adb shell dumpsys accessibility | Select-String "Bound services|KeyboardInterceptor"
```

You want to see `Robot Reset` among the bound services and `KeyboardInterceptor` in
the enabled features. `KeyboardInterceptor` is the direct evidence that
`FLAG_REQUEST_FILTER_KEY_EVENTS` took effect — without it, `onKeyEvent` will never
fire and Step 0 fails for a reason unrelated to the design.

If you enable it through Settings → Accessibility instead and Android blocks it, clear
the restriction via Settings → Apps → Robot Reset → ⋮ → **Allow restricted settings**,
then retry the toggle.

### On Samsung phones: also grant "Unrestricted" battery access

Settings → Apps → Robot Reset → Battery → **Unrestricted**. This is required for
`LAUNCH_DS` (`Ctrl+Alt+F4`) to reliably wake the screen and dismiss the keyguard when
the phone is genuinely asleep/locked — without it, that specific case silently fails
even though everything else in this app (including `LAUNCH_DS` itself when the phone
is already awake) works fine. It is a one-time manual step, cannot be granted by the
app itself, and is separate from the stock Android Doze allowlist. See "Opening the DS
app itself" below for the full finding. Confirm this on every Samsung phone in the
fleet (Fable, Flash); Sol has no OneUI and this step may not apply to it at all.

## 4. STEP 0 — the hard gate (confirmed passing)

Open **Robot Reset** from the app drawer. It launches straight into Key Monitor. A
**"Status / Config"** button at the top of that screen opens Status, and from Status,
**Config** — that's where you'll go in Section 7. Confirm the header reads
**"Accessibility service: RUNNING"** and leave **"Consume matched chords"** switched
**on**.

Now attach the real trigger hardware: unplug USB and plug in the Feather M0 (running
`fable.ino` from the `ftc-lora` branch) via OTG. It should enumerate as a keyboard —
you can confirm this from another machine with `adb shell dumpsys input` showing a
device named `Adafruit Feather M0` with `Classes: KEYBOARD`. If the trigger box has
just been plugged in, give it a moment; a swap that lands on a bad connection can fail
to enumerate at all (zero devices, not just zero events) — reseat the OTG connection
and check again before assuming a software problem.

**Fire `Ctrl+Alt+F1`** from the real trigger chain — either the dashboard's INIT
button (`ftc-lora:driver_station_flask.py`, see its "Driver Station Command Chords"
procedures) or an actual USB keyboard's Ctrl+Alt+F1, whichever hardware you have.

Expected: a new line appears at the top of the key event log, roughly

```
HH:MM:SS.mmm  keyCode=131  meta=0x...  cmd=INIT  consumed=y
```

Then run two controls:

- **Fire bare `F1`** (no modifiers). Expect either no line, or a line with `cmd=NONE`.
  This confirms interception is chord-specific and will not fire on ordinary input.
- **Fire `Ctrl+Alt+F5`.** Expect `cmd=OPMODE_SLOT`.

### Decision point

| Outcome | Meaning |
| --- | --- |
| Chord lines appear | Step 0 **passes**. The load-bearing assumption holds. Continue. |
| Nothing appears at all, and the raw device isn't even enumerating (`dumpsys input` shows no `Adafruit Feather M0` / no HID device) | Not a software problem — check the physical OTG connection first. |
| Device enumerates, but literally zero raw kernel events appear even at `getevent` level when firing a chord | The trigger box isn't actually sending anything. For `fable.ino`-based triggers, check its **own serial console** (open it on a PC, with `DTR`/`RTS` asserted if using a raw serial terminal — some boards gate `Serial` output on DTR) for `"Keyboard HID unavailable; CFG_TUD_HID may be 1."`, and confirm the LoRa link is actually delivering frames (the same serial console prints one line per received frame with an RSSI value). |
| Nothing appears in `onKeyEvent`, but raw kernel events **do** appear (`getevent` shows `KEY_LEFTCTRL`/`KEY_LEFTALT`/`KEY_F1` etc.) | The trigger mechanism itself is fine; the problem is between the kernel input layer and this app. Re-check Section 3 — `KeyboardInterceptor` must be active. |
| Lines appear but `cmd=NONE` for a chord you expected to match | The chord arrives but modifiers decode differently on this phone. Note the exact `meta=0x...` value; `ChordDecoder`'s modifier match may need adjusting. |

Note the keycode and meta values in all cases — they are the raw truth about what
this phone's keyboard actually sends.

### If the trigger is a `fable.ino`-based Feather M0, not an off-the-shelf keyboard

Read `fable.ino`'s own comments carefully: it is explicitly "a dumb chord emitter"
with **no local button input at all** (verified — no `digitalRead` for anything but
the status LED). It only emits a keyboard HID report when it decodes a valid LoRa
frame with `BTN_UI_CMD` set. This means:

- You cannot trigger a chord by pressing anything on the Feather M0 board itself.
- You need the full transmitter chain live: a second board running
  `driverstation.ino`, wired via USB serial to a computer, running
  `driver_station_flask.py` with `--port <that board's COM port>`, and the dashboard
  open in a browser (default `http://127.0.0.1:8765`).
- The dashboard's INIT/START/STOP/OPMODE buttons (see Section 8) are the practical
  way to fire specific chords for this test, once `ui_commands.json` has real chords
  configured (see `ftc-lora:docs/protocol.md`).

## 5. Confirm consumption

With **"Consume matched chords" on**, foreground the FTC Driver Station app and fire
`Ctrl+Alt+F1`. The DS app should show no sign of the keystroke. Toggling the switch
**off** and repeating is the comparison case: the event is still logged, but is no
longer consumed, so anything that does respond to F1 will now see it.

## 6. Capture the real DS app ids

There are two ways to find real resource-ids. **Use both** — the live dump is
authoritative for what's actually rendered right now, but it can only show you
elements that are currently visible (an OpMode list that requires a live Robot
Controller connection to populate will dump as empty, even though the dialog itself
is real). Static APK analysis works with **no robot connected at all** and is how the
real START/STOP ids were actually found in practice, since the OpMode list needed a
live robot pairing to populate.

### Method A: live `uiautomator` dump

Reattach USB. Foreground the Driver Station app, then:

```powershell
& $adb shell pm list packages | Select-String qualcomm     # confirm the real package name
.\tools\uiautomator-dump.ps1                                # dumps + pulls window_dump.xml
```

Do this once on the main DS screen, and once with each dropdown/dialog open you care
about (list contents only exist in the tree while the dialog is open).

In the resulting XML, find nodes by `text`/`content-desc`, and record `resource-id`,
`class`, `clickable`, and `enabled`. **Watch for Gotcha 2 here**: the node matching
your target *text* is frequently not the clickable one — e.g. this team's real DS app
has the "INIT" text as a sibling `TextView` next to the actual clickable
`ImageButton`, not an ancestor/descendant of it. A `TEXT("INIT")` override would never
resolve here; you need the `VIEW_ID` of the sibling `ImageButton` directly. Always
check whether the visually-associated text node is *itself* clickable, or whether you
need a different node's `resource-id` and/or `content-desc` instead.

### Method B: static APK analysis (works without any robot connection)

```powershell
$pkgPath = (& $adb shell pm path com.qualcomm.ftcdriverstation) -replace '^package:',''
& $adb pull $pkgPath.Trim() .\ds_app.apk

$aapt = "$env:ANDROID_SDK_ROOT\build-tools\34.0.0\aapt.exe"
$aapt2 = "$env:ANDROID_SDK_ROOT\build-tools\34.0.0\aapt2.exe"

# List every id/layout resource name the app declares -- release builds often
# obfuscate/shorten the actual res/*.xml filenames, so names alone don't map to files.
& $aapt dump resources .\ds_app.apk | Select-String ":id/" | Select-String "button|opmode|stop|start|init"

# Find the real (possibly renamed) file backing a given layout resource:
& $aapt2 dump resources .\ds_app.apk | Select-String -Context 0,1 "layout/activity_ftc_driver_station"
# -> e.g. "res/Rz.xml type=XML"

# Dump that file's raw view tree, with hex resource-id references:
& $aapt dump xmltree .\ds_app.apk res/Rz.xml > ds_layout_dump.txt
```

`aapt dump xmltree` prints `android:id(...)=@0x7fXXXXXX` rather than symbolic names —
cross-reference the hex values against the `aapt dump resources` id list from the
first command to translate. This reveals the **full static structure**, including
`onClick` handler names and `contentDescription` strings, and — critically — sibling
elements that a snapshot-in-time `uiautomator` dump won't show if they're not
currently visible (e.g. the STOP button, which is hidden until an OpMode is running).

Delete `ds_app.apk` and any dump files afterward; they're bench artifacts, not part
of the app.

### What this team found (FTC Driver Station app, this build)

Recorded here as a worked example, **not** a guarantee for a different season's DS
app build — always re-verify with the methods above. That said, the FTC Driver
Station app is the same shared Qualcomm/REV APK across every team, so these are
likely to still be correct or very close:

| Target | Real id found |
| --- | --- |
| INIT | `VIEW_ID` `com.qualcomm.ftcdriverstation:id/buttonInitImageButton` (a sibling `ImageButton`, not an ancestor, of the "INIT" text) |
| START | `VIEW_ID` `com.qualcomm.ftcdriverstation:id/buttonStartImage` (`content-desc="StartButton"`, `onClick="onClickButtonStart"`) |
| STOP | `VIEW_ID` `com.qualcomm.ftcdriverstation:id/buttonStop` — a plain top-level `ImageButton`, directly clickable, no wrapper (`onClick="onClickButtonStop"`) |
| OpMode dropdown | The DS app does **not** have one unified "Select OpMode" control — it has two separate buttons, `buttonAutonomous` and `buttonTeleOp` (`content-desc="AutonomousDropdown"`/`"TeleopDropdown"`). Pick whichever category your chord-selectable OpModes are in; this team used `VIEW_ID` `com.qualcomm.ftcdriverstation:id/buttonTeleOp` |
| OpMode list rows | Standard system `AlertDialog` list (`android:id/select_dialog_listview`), default row layout — text-matching (the built-in fallback) works fine here, no override needed |

## 7. Apply the real ids

Every target currently defaults to a text match, which is the more robust guess when
nothing better is known:

| Target | Hardcoded default in `TargetConfig` |
| --- | --- |
| INIT / START / STOP | `TEXT("INIT")` / `TEXT("START")` / `TEXT("STOP")` |
| OpMode dropdown | `TEXT("Select OpMode")` |
| OpMode scroll container | `VIEW_ID("opmode_list_container")` — not overridable via Config; tolerated by design (see below) |
| OpMode slot 0..3 names | `"OpMode Slot 0".."OpMode Slot 3"` |

The scroll container being wrong is tolerated by design: `OpModeSelector` falls back
to scanning for any node with `isScrollable()`, so a miss costs nothing — this worked
correctly against the real app's standard `AlertDialog` list without any override.

Where the dump disagrees with a default, fix it in the app rather than rebuilding:

1. Open **Robot Reset** → **Status / Config** button → **Status** → **Config**.
2. In the override section: pick the target from the spinner (`init`, `start`,
   `stop`, `opmode_dropdown`), select the **`VIEW_ID`** radio button, paste the real
   resource-id, tap **Save Override**.
3. In the OpMode slot section: type the real registered OpMode name(s) you want
   reachable by chord into the slot fields (`Ctrl+Alt+F5`=slot 0 through
   `Ctrl+Alt+F8`=slot 3). You do not need to fill in all four — an unused slot's
   default placeholder text simply won't match anything, which is a harmless no-op,
   not an error.
4. Tap **Save Slots**.

`TargetConfig` persists all of this in `SharedPreferences`, which is the whole reason
a wrong guess is a settings change instead of a new APK. These overrides survive
`adb install -r` (reinstall preserving data) but **not** an uninstall or a full data
clear.

**View-ids are case-sensitive and there's no validation on the Config screen** — a
single mistyped character (e.g. `buttonTeleop` instead of `buttonTeleOp`) fails
silently as `reason=not found` in the resolution log, which looks identical to a
genuinely wrong id. If a chord that should work produces `not found`, re-check the
override value character-by-character against the actual dump/APK analysis output
before assuming the id itself is wrong. This has already happened once (Flash's
`opmode_dropdown` override) and cost real debugging time before the typo was spotted
in the resolve log.

## 8. Full bring-up (confirmed working)

With the trigger chain live (transmitter running `driver_station_flask.py`, Feather M0
attached to the phone) and the DS app foregrounded and actually connected to a live
Robot Controller (OpMode selection needs a real pairing — a disconnected DS app shows
an empty OpMode list and the selector will correctly time out finding nothing, which
is fail-safe behavior, not a bug):

The dashboard needs **four** buttons for full coverage — the three named commands
(INIT/START/STOP) plus a dedicated OpMode-select button, since OpMode selection isn't
one of the three named `UI_COMMAND_KEYS` in `driver_station_flask.py` by default. See
`ftc-lora:docs/procedures.md` for adding this if it isn't already there.

Confirmed chord table (this team's final configuration):

| Command | Chord | Result |
| --- | --- | --- |
| OPMODE (select slot 0) | `Ctrl+Alt+F5` | Opens the OpMode dropdown, finds and clicks the configured slot-0 OpMode name |
| INIT | `Ctrl+Alt+F1` | Clicks INIT (only enabled once an OpMode is selected) |
| START | `Ctrl+Alt+F2` | Clicks START |
| STOP | `Ctrl+Alt+F3` | Clicks STOP |

Order matters for a cold start: OpMode select → INIT → START → STOP, same as
operating the DS app by hand. A fifth command, `LAUNCH_DS` (`Ctrl+Alt+F4`), opens the
DS app itself rather than clicking inside it, including from a fully asleep/locked
phone — see the dedicated subsection below, which also covers the one-time device
setting it needs on Samsung hardware. It is not part of this table because it is not
element-resolution and needs that separate setup step, not because it is less
verified.

Then verify the fail-safe property on real hardware: navigate the phone **away** from
the DS app and fire `Ctrl+Alt+F1`. **Nothing should happen.** That inversion —
nothing, rather than something wrong — is the entire justification for this design
over the reverted coordinate tap, and it is worth confirming with your own eyes on
the real app.

Robot Reset's **Status** screen shows the resolution log (what was clicked, and the
reason when it was not), which is the no-cable way to inspect all of the above. Over
a cable, `tools\logcat-robotreset.ps1` shows the same trace plus per-key detail — but
per the networking note above, do not assume the cable/wireless link will be
available once the phone is on the robot's own Wi-Fi AP; the on-screen Status log is
the dependable path in that case.

### Opening the DS app itself: LAUNCH_DS (`Ctrl+Alt+F4`)

Every command above assumes the DS app is already the foreground app and clicks an
element inside it — by design, they fail safe (do nothing) otherwise. `LAUNCH_DS`
(`ChordDecoder.Command.LAUNCH_DS`, chord `Ctrl+Alt+F4`) is different in kind: it
brings the DS app itself onto the screen from any state — home screen, a different
app, or a DS sub-screen — via `DriverStationLauncher`
(`com.next2026.robotreset.launch`), which resolves and starts the DS app's launcher
activity with `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK` (a **force-relaunch**:
discards the DS app's existing task back stack, equivalent to swiping it from Recents
and re-tapping its icon — this is *not* `adb shell am force-stop`, which kills a
process outright; no public Android API lets one app do that to another, so a
genuinely wedged DS app process, see the crash-bug notes below, is not cured by this).
The launch always routes through `DsLaunchActivity`, a transient translucent relay
activity, because only an Activity's own window (not a Service) can affect keyguard/
screen-wake state.

**Real-hardware finding — package visibility (Android 11+ / API 30+):** an app
targeting API 30+ cannot see another app via `PackageManager` at all, including
`getLaunchIntentForPackage()`, unless the target package is declared in a `<queries>`
manifest element. This bit on the very first real-device test: `LAUNCH_DS` reported
`package not found` even though `com.qualcomm.ftcdriverstation` was genuinely
installed, because `adb shell monkey`/`pm` (used for an earlier feasibility check)
run as the `shell` UID, which is exempt from this filtering — an ordinary installed
app is not. Fixed by adding
`<queries><package android:name="com.qualcomm.ftcdriverstation" /></queries>` to
`RobotReset/src/main/AndroidManifest.xml`. If `LAUNCH_DS` ever regresses to
`package not found` again with the DS app genuinely present, check this element
before suspecting anything else.

**Confirmed on real hardware for both Fable and Flash (both Samsung Galaxy S20 FE
variants, Android 13/API 33), with a real `Ctrl+Alt+F4` chord over the real LoRa
link** (transmitter -> Uno -> `fable.ino`/`flash.ino` -> USB HID keyboard -> phone
`onKeyEvent`, not the debug broadcast): with the DS app backgrounded, on a different
app, or on a DS sub-screen, and the phone already awake and unlocked, firing OPEN DS
from the dashboard reliably brings `FtcDriverStationActivity` to the foreground.
Fable's session recorded Key Monitor showing `cmd=LAUNCH_DS consumed=y` and the
Status screen's resolution log showing
`LAUNCH_DS:com.qualcomm.ftcdriverstation clicked=y launched`; Flash's session was
confirmed by direct observation of the DS app opening on screen. Both worked on the
first attempt for Flash, with no repeat of Fable's earlier troubleshooting -- see the
gamepad-connection note below for why that mattered.

**Sol (Moto E5 Cruise, Android 8.0/API 26, `sol.ino`'s Report-ID-multiplexed HID
transport) is also confirmed for this same awake/backgrounded case, with a real
chord** -- direct observation of the DS app opening, same as Flash. Sol has its own
delay quirk (see the dedicated subsection below) and its asleep/locked case does not
currently work at all, unlike Fable and Flash.

Bring-up note from this session: getting to that confirmation took real hardware
debugging unrelated to any code in this feature, and the root cause was upstream of
everything this repo's own docs usually point at first. **The real cause was that no
gamepad controller was connected (via Bluetooth) to the desktop machine running
`driver_station_flask.py`.** `run_transmitter()`'s main loop
(`driver_station_flask.py`, around `if joystick is None or ser is None:`) skips its
entire frame-building and send logic -- which includes the UI-command chord
injection block -- whenever no controller is detected, and `continue`s straight back
to the top of the loop. With no gamepad connected, **no frame of any kind is ever
sent over serial**, so nothing reaches the Uno, nothing reaches the LoRa link, and
nothing reaches the Feather, no matter which dashboard button is clicked or what the
Feather/phone/OTG connection state is. Once a controller was connected, the exact
same OPEN DS button click worked immediately.

**This is a real, worth-knowing gap in the dashboard's own feedback, not just a
one-off bench mistake:** `POST /api/ui-command/<key>` returns `{"ok": true, ...}`
unconditionally -- it only records the pulse in `SharedState`, and does not check
whether a controller is connected before reporting success. The dashboard's own
`/api/status` `error` field does say `"No controller detected."` in this state (and
the gamepad status pill in the UI reflects it too), but nothing about the UI-command
buttons themselves signals that a click did nothing. **Before spending time on
phone/OTG/Feather diagnostics for a chord that isn't landing, check the dashboard's
gamepad indicator first** -- if it shows "Gamepad Down" / no controller, that alone
explains total silence on the phone side, regardless of how healthy the rest of the
chain is. A useful control test either way: fire a chord that has worked in a
previous session (e.g. INIT) through the exact same setup -- if that also produces
nothing, suspect the transmitter's controller/serial state before the phone.

**Screen wake from a genuinely asleep/locked phone — confirmed working on both Fable
and Flash, but needs a one-time manual device setting.** With the screen off and the
keyguard showing, `LAUNCH_DS` reliably wakes the screen, dismisses the keyguard, and
brings the DS app to the foreground (4/4 on Fable across two separate sessions, with
adequate settle time between test cycles — see below; confirmed again on Flash by
direct observation, first attempt) **once RobotReset is granted Samsung's
"Unrestricted" battery access**: Settings → Apps → Robot Reset → Battery →
**Unrestricted**. This is **not** the same as Samsung's usual "sleeping apps" list
(which didn't exist as an option on this phone) or the stock Android Doze allowlist
(`dumpsys deviceidle whitelist`) — that stock allowlist was tried first and made no
difference at all, which is the key finding here: **Samsung's OneUI battery
management is a separate, stricter layer on top of stock Android Doze.** Exempting an
app from AOSP's doze restrictions does not exempt it from Samsung's own restrictions;
only the Samsung-specific "Unrestricted" setting does. Every other approach tried
before finding this — the code exactly as shipped, the same work in `onCreate()`
instead of deferred to `onResume()`, and an explicit `PowerManager.FULL_WAKE_LOCK`
with `ACQUIRE_CAUSES_WAKEUP` — reproducibly failed without this setting (screen stayed
off, keyguard stayed up, even though the DS app's activity became the
`ActivityManager`'s `ResumedActivity` internally the whole time). None of that other
code was kept beyond the `onResume()` deferral, which does no harm either way.

**Practical consequence: add "grant Robot Reset Unrestricted battery access" to the
one-time per-phone setup checklist**, alongside enabling the accessibility service
(Section 3). This app cannot grant this to itself — there is no public API for it,
Samsung or otherwise — so it must be set by hand once per phone, the same way the
accessibility service itself must be enabled once per phone. **A short settle time
between the phone going to sleep and firing the chord matters**: an initial rapid-fire
test loop (force-stop DS app → sleep → fire chord within ~1 second) produced 1
success and 2 failures out of 3, but the *identical* sequence with a few extra
seconds of settle time after each transition produced 4/4 successes across two
sessions — treat a failure under rapid/back-to-back testing as inconclusive, not as
evidence the fix doesn't work, and prefer a few seconds of slack in the field too.
Flash (same hardware/OS as Fable, same OneUI) needed the identical setting and is now
**confirmed** the same way, first attempt, no further troubleshooting required.

**Sol (Moto E5 Cruise, Android 8.0/API 26, no OneUI): the asleep/locked case does
not currently work, and remains an open problem.** Confirmed on real hardware: with
the screen off and settled, firing OPEN DS produced no wake, no keyguard dismiss,
and no DS app launch at all, across a patient 10-second observation window (`mWakefulness`
stayed `Dozing` the entire time). Motorola has no OneUI, so Samsung's "Unrestricted"
battery access setting has no direct equivalent here; a different device setting was
tried on this phone during bring-up and did not resolve either this or the unrelated
activity-start delay documented below. This is being treated as a known limitation
for now, not actively worked around -- if this matters operationally, the
full-screen-intent-notification approach outlined below (drafted, then deliberately
not kept, for a different Sol problem) is the most likely path, but would need its
own investigation specifically for the wake/keyguard case, which is a separate
mechanism from the activity-start issue it was drafted for.

### Sol-specific: activity starts are sometimes deferred by a few seconds

Real-hardware testing on Sol surfaced a finding neither Fable nor Flash exhibit:
firing `LAUNCH_DS` while the DS app is backgrounded and the phone is **awake**
reliably works, but sometimes only after a delay of roughly 2-4 seconds, not the
near-instant response seen on the other two phones. `logcat` shows `ActivityManager:
Activity start request from <uid> stopped` (confirmed via `dumpsys package
com.next2026.robotreset | grep userId=` to be genuinely RobotReset's own UID, not an
unrelated system message) at the moment of the original attempt, followed by the
activity actually appearing a few seconds later with a **new** `ActivityRecord`
identity -- consistent with the platform deferring/retrying the start rather than
permanently denying it. This is not the Android 10+ background-activity-launch
restriction (Sol's API level, 26, predates that mechanism); it is most likely a
Motorola-specific policy, though this is not confirmed. A device setting change on
Sol's phone during this session did not resolve it.

A full-screen-intent notification (the platform-sanctioned mechanism for bringing an
Activity to the foreground from a background trigger, used by call/alarm apps) was
drafted as a fix and does work as a technique in general, but was deliberately **not
kept**: given the delay is bounded (observed consistently in the 2-4 second range,
never open-ended) and `LAUNCH_DS` is a recovery/setup action rather than a
time-critical control input, the added complexity -- a new file, a new
`POST_NOTIFICATIONS`-gated code path relevant only on API 33+ (Fable/Flash, where it
isn't needed since the direct path already works there), and its own testing burden
-- was judged not worth it for a delay this small. If Sol's delay is ever observed to
be substantially longer or unbounded in the field, revisit this decision; the
approach (fire a full-screen-intent notification targeting `DsLaunchActivity`
alongside the existing direct `startActivity()` call, unconditionally on all three
phones since this app has no concept of which robot it's installed on) is still the
right one, it was simply not needed yet.

**Flash bring-up note:** unlike Fable's session, Flash's real-hardware confirmation
required no troubleshooting at all -- both the awake/backgrounded and asleep/locked
cases worked on the first attempt. The only difference in process was checking
`/api/status`'s `serial_ok`/`gamepad_ok` fields *before* firing anything, rather than
discovering a disconnected gamepad partway through a debugging session (see the
gamepad-connection note above). That single check upfront eliminated the entire class
of failure that consumed most of Fable's session.

## 9. Pre-session check, every session afterward

Service enablement is not remotely observable, and nobody is at this phone once the
robot is out. Before relying on it: open Robot Reset and confirm **"Accessibility
service: RUNNING"**. The service does survive reboot once enabled, unlike `adb tcpip`
or Shizuku — but confirm, do not assume.

## 10. Multi-robot findings: Flash and Sol

Flash (Feather M0, same `fable.ino`-derived firmware/architecture) and Sol (Feather
32u4, architecturally different USB stack) have both since been through full bring-up
too. Flash's session did not surface anything Fable's hadn't already covered above.
Sol's did — several real findings, some general (apply to any robot/DS app build),
some specific to the 32u4's different USB stack.

### Gotcha 5: a resolved node can report `clicked=true` and still do nothing

On Sol's phone (Android 8.0/Oreo), clicking a resolved, enabled, clickable node via
plain `ACTION_CLICK` sometimes returned `true` and even visibly dismissed a dialog,
while the app's real underlying selection logic never ran — reproducible specifically
on the OpMode list's `AlertDialog` row. A real touch (and TalkBack) always place
`ACTION_ACCESSIBILITY_FOCUS` on a node before `ACTION_CLICK`; some widget/list click
paths are apparently wired to care about focus state on this Android version, not
purely the `AdapterView` position lookup `ACTION_CLICK` triggers on its own. Fix:
`AccessibilityNodeRefImpl.click()` now always performs `ACTION_ACCESSIBILITY_FOCUS`
immediately before `ACTION_CLICK`. This is unconditional for every click in the app
now (not Sol-specific) — it is a no-op on devices that don't need it, and it is what
actually fixed Sol's OpMode selection.

### Gotcha 6: DS app resource-ids can drift across an in-session app update, silently

Mid-session, Sol's Driver Station app was updated (it had been showing an "obsolete
app" warning). After the update, INIT/START/STOP's `VIEW_ID` overrides — which had
been copied from Fable's Section 6 findings and worked fine before the update — all
started resolving as `not found`, indefinitely, regardless of how long you wait after
OpMode selection. The new DS app build had simply renamed/removed those specific
view-ids. Diagnosis path that actually worked: confirm the target **is** genuinely
present and functional by testing a real manual tap first (see Gotcha 2's original
form of this same idea) — if manual tap works but automated resolution reports
`not found` no matter the timing, suspect a stale id/text override before suspecting a
timing or resolver bug. Fix was a config change (switch INIT/START to plain
`TEXT("INIT")`/`TEXT("START")` overrides), not a rebuild — exactly what Section 7's
override system exists for. **Re-run Section 6/7 after any DS app update**, not just
once at initial setup.

### New capability: `TargetSpec.Kind.TEXT_SIBLING`, for overlapping decorative controls

Sol's DS app skin ("NEXT-A-DS") renders its combined INIT/START/STOP control as a
single circular touch target that relabels itself per state — but once running, the
STOP state is a bare icon with **no** text, content-description, or view-id at all.
Worse: static analysis of the live tree found **two different clickable `ImageButton`s
at the exact same screen bounds** — one carries `content-desc="StartButton"` (a
static label that does not change with the button's state, present at INIT/START/STOP
alike), the other has no accessible label whatsoever. Clicking the labeled one
reported `clicked=true` but had no real effect; clicking the *other* one (found only
by testing empirically, via a temporary debug hook that clicks the Nth clickable node
in the tree) actually worked.

The reason: Android hit-tests overlapping siblings in reverse child order — the
later-drawn (topmost) one receives real touches first. The labeled node here is a
decorative/accessibility-only layer; the real one, layered on top, has no label at
all.

`TargetSpec.Kind.TEXT_SIBLING` encodes this pattern generally: it resolves by text or
content-description exactly like `TEXT`, but then looks among the resolved node's
*siblings* (same parent) for another clickable, enabled node at the exact same bounds,
and clicks the **last** one found in child order instead — falling back to the
originally-matched node if no such sibling exists. It's a genuine `Resolver`
capability now (`ResolverImpl.preferTopmostSiblingAtSameBounds`), not a one-off hack,
but it is opt-in only: an ordinary `TEXT`/`VIEW_ID` target never triggers it, so it
cannot change behavior anywhere it isn't explicitly configured. Set it the same way as
any other override, just choosing `TEXT_SIBLING` for the kind (not yet exposed in
`ConfigActivity`'s radio group as of this writing — set it via the debug-build
`SET_OVERRIDE` broadcast in the meantime: `adb shell am broadcast -a
com.next2026.robotreset.DEBUG_COMMAND -p com.next2026.robotreset --es cmd SET_OVERRIDE
--es key stop --es kind TEXT_SIBLING --es value StartButton`).

If a target reports `clicked=true` with a real, present, enabled node and still has no
effect, and a manual tap on the same visual control *does* work, suspect this exact
overlapping-sibling pattern next — dump the live tree's clickable nodes (see the
debug-build `DUMP_TREE` broadcast, same mechanism) and look for more than one
clickable node at identical bounds.

### Sol-specific: Feather 32u4's USB stack can block `loop()` for up to ~500ms

Fable/Flash (Feather M0, TinyUSB) send gamepad and keyboard reports over two fully
independent USB interfaces/endpoints, and gate every send on `usb_hid.ready()` — a
non-blocking check, so a slow/unresponsive host just means that cycle's report is
silently skipped. Sol (Feather 32u4, AVR `HID.h`) has neither: gamepad and keyboard
share one physical endpoint (the only option this stack has), and `HID_::SendReport()`
is a **blocking** call — verified directly in the installed Arduino AVR core
(`USBCore.cpp`): `USB_Send()` retries for up to 250ms per phase (~500ms for a full
report: ID byte + payload) if the endpoint isn't draining, and AVR's `HID.h` exposes no
non-blocking readiness check at the sketch level at all. If the host is briefly slow to
drain HID (plausible around the DS app's own crash/relaunch — see below), Sol's whole
`loop()` can stall for up to ~500ms per blocked send, starving LoRa reception and the
chord-release timer in a way that's architecturally impossible on the M0 boards.

Mitigation applied in `sol.ino` (see `ftc-lora:sol/sol.ino`, no vendored core files
touched): skip the routine 20ms gamepad send while a chord press is outstanding
(nothing is lost — controls are already forced neutral during a chord), and log when a
chord press's `sendReport()` actually fails, so a future silent drop is visible in
Serial instead of untraceable.

### The Driver Station app has its own pre-existing crash bug, unrelated to this app

`FATAL EXCEPTION` / `IllegalArgumentException: Receiver not registered:
...DriverStationAccessPointAssistant$1`, during `handleRelaunchActivity` → `onDestroy`
→ `shutdown`. Confirmed to be a genuine bug in Qualcomm's own app, not this app or the
firmware: it recurred on a freshly force-stopped-and-relaunched process (a brand new
PID, no accumulated state from any earlier crash), triggered by USB controller
connect/disconnect churn as well as by rapid successive chord firing. The app usually
self-recovers via its own relaunch. Practical mitigations:

- After plugging in the trigger hardware, **wait for the DS app to visibly settle**
  (confirm it's on a normal, stable screen) before firing any chord — firing
  immediately after connect risks catching the app mid-relaunch, when a target
  element genuinely isn't in the tree (not a resolver bug).
- Don't fire chords in rapid succession (e.g. OPMODE→INIT→START→STOP within a few
  seconds) — this alone was observed to trigger the crash.
- If the app is behaving strangely and simply waiting doesn't help, **force-stop it
  fully** (`adb shell am force-stop com.qualcomm.ftcdriverstation`) rather than
  relying on its own relaunch — an in-process Activity relaunch does not clear
  whatever leaked receiver state contributed to the crash in the first place, so
  repeated relaunches within the same process can compound. Reopen it from the home
  screen afterward (its main activity isn't exported, so `adb shell am start` cannot
  relaunch it).
- Unplugging the trigger Feather from the phone can itself cause the DS app to reset
  to "Select OpMode" (losing INIT/START progress) — this was observed to happen
  inconsistently (sometimes yes, sometimes no) across otherwise-identical swaps. Budget
  for re-running OpMode select after any Feather unplug/replug cycle during a bench
  session; this is a DS-app/USB-churn behavior, not something this app's resolution
  logic can detect or avoid.

### Sol: confirmed chord table, full bring-up

| Command | Chord | Result |
| --- | --- | --- |
| OPMODE (select slot 0) | `Ctrl+Alt+F5` | Opens the TeleOp dropdown, clicks the configured slot-0 OpMode name |
| INIT | `Ctrl+Alt+F1` | `TEXT("INIT")` |
| START | `Ctrl+Alt+F2` | `TEXT("START")` |
| STOP | `Ctrl+Alt+F3` | `TEXT_SIBLING("StartButton")` — see above |

All four confirmed working end-to-end against a live Robot Controller connection, real
Feather 32u4 hardware, real LoRa link.

## Still not solved

The driver gets **no acknowledgement** that a chord's click actually landed (only
that the chord was *sent* — the dashboard has no read path back from the phone). The
existing 5.8 GHz FPV camera path pointed at the phone screen is the practical
mitigation; the Status screen's resolution log is the fallback when the FPV feed
isn't available or convenient to check.
