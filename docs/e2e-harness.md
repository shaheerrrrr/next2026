# E2E harness — `tools/e2e.ps1`

This is the automated emulator gate for `robot-reset-app`. It boots the
`RobotResetTest` AVD, builds and installs `RobotReset` and
`FakeDriverStation`, enables the accessibility service, fires a debug-only
broadcast trigger via `adb`, and asserts on the resulting `FakeDriverStation`
logcat markers (and, for one case, on the live UI tree via `uiautomator`).

This document explains how to run it, what each test case proves, what it
explicitly does **not** prove, and why. The design rationale it's built to
prove out — "fails safe" resolution by UI element, never a coordinate tap —
is in `docs/robot-reset-app-brief.md` and `docs/design-accessibility-tap.md`.
Those are the read-only spec; this file is just the harness runbook.

## Why the harness fires a broadcast instead of a real key-event chord

Earlier versions of this harness fired chords with
`adb shell input keycombination <ctrl> <alt> <Fn>` (and, before that, plain
`adb shell input keyevent`). Neither ever reached `onKeyEvent` — not even a
single, unmodified keycode — despite the accessibility service being
correctly bound and `FLAG_REQUEST_FILTER_KEY_EVENTS` confirmed active
(`adb shell dumpsys accessibility` showed
`Enabled features of Display [0] = [KeyboardInterceptor]`, and re-enabling
the service logged `service connected; key event filtering requested`).

**Root cause:** `adb shell input` injects events via
`InputManager.injectInputEvent`. That is synthetic input injection, not a
real hardware input device, and the accessibility service's
`KeyboardInterceptor` stage — the thing that feeds `onKeyEvent` — only
observes real hardware input devices. This holds on an emulator and on real
hardware alike; it is not an emulator quirk. `sendevent`/uinput (injecting at
the kernel evdev layer, faking a whole USB HID keyboard report descriptor)
were considered and rejected as out of scope and fragile.

**Consequence:** the Ctrl+Alt+F1..F8 chord trigger path itself — a real USB
HID keyboard producing a real `onKeyEvent` callback, and that callback's
`return true` actually hiding the keystroke from the DS app — is
**untestable via `adb`, on an emulator or on real hardware.** It is covered
only by the brief's "Step 0" real-hardware bench check
(`docs/robot-reset-app-brief.md` section 7), never by this script.

What *is* fully testable on the emulator — and the reason this harness exists
and is worth running on every change — is everything downstream of chord
decoding: element resolution by view-id/text, the walk up to a clickable
ancestor (Gotcha 2), the disabled-node no-op (Gotcha 3), and the OpMode
dropdown's open/wait/scroll/match state machine. That is the biggest and
most gotcha-prone part of this app, and none of it depends on how the
resolver was triggered.

To exercise that path without a real key event, `RobotResetService` exposes a
static `dispatchDebugCommand(ChordDecoder.Decoded)` seam, forwarded to by
`DebugCommandReceiver` — a `BroadcastReceiver` for action
`com.next2026.robotreset.DEBUG_COMMAND` that reads a `cmd` string extra
(`INIT`/`START`/`STOP`/`OPMODE_SLOT`) and an optional `slot` int extra. It
calls the *exact same* `handleCommand(...)` method a real chord's
`onKeyEvent` calls — it is not a reimplementation, so a pass here says
something real about production behavior. The harness's `Send-Chord` helper
fires this broadcast:

```powershell
adb shell am broadcast -a com.next2026.robotreset.DEBUG_COMMAND `
    -p com.next2026.robotreset --include-stopped-packages --es cmd INIT
adb shell am broadcast -a com.next2026.robotreset.DEBUG_COMMAND `
    -p com.next2026.robotreset --include-stopped-packages --es cmd OPMODE_SLOT --ei slot 3
```

**This receiver is debug-build-only and absent from release.** It lives
under `RobotReset/src/debug/` (Java class and `AndroidManifest.xml`), an AGP
source set that is folded into debug builds only; there is no `src/release`
counterpart declaring it, so it is not merely disabled in release, it does
not exist there. This matters because a broadcast that can press a robot's
INIT/START/STOP must not exist in a build a user could install. Verified by
diffing the merged manifests after `assembleDebug`/`processReleaseMainManifest`:

```
$ grep -c DebugCommandReceiver RobotReset/build/intermediates/packaged_manifests/debug/processDebugManifestForPackage/AndroidManifest.xml
2
$ grep -c DebugCommandReceiver RobotReset/build/intermediates/packaged_manifests/release/processReleaseManifestForPackage/AndroidManifest.xml
0
```

and by diffing the compiled `.class` output directly — `DebugCommandReceiver.class`
exists under `RobotReset/build/intermediates/javac/debug/...` and is absent
from the parallel `javac/release/...` directory entirely. Neither manifest
declares `INTERNET` (unaffected by this change; the app has never requested
it, per `docs/robot-reset-app-brief.md`).

## Running it

```powershell
. .\tools\env.ps1
.\tools\e2e.ps1
```

Useful flags for iterating without a full rebuild/reboot each time:

```powershell
.\tools\e2e.ps1 -SkipBuild                 # reuse previously built APKs
.\tools\e2e.ps1 -SkipBuild -SkipInstall    # reuse whatever's already installed
.\tools\e2e.ps1 -SkipEmulatorBoot          # target whatever device adb already sees
```

The script exits non-zero if any test case fails, so it's CI/gate-friendly.

## What FakeDriverStation is

`FakeDriverStation` (`FakeDriverStation/src/main/java/com/next2026/fakedriverstation/MainActivity.java`)
is a realistic stand-in for the real FTC Driver Station app, built so this
harness doesn't depend on the real DS app or a real robot being present:

- An **INIT** button that starts **disabled** and only becomes enabled after
  an OpMode is selected — this exists specifically to exercise Gotcha 3
  (clicking a disabled node must be a no-op, never retried).
- **START** and **STOP** buttons.
- A **Select OpMode** control that, after a deliberate ~900ms artificial
  delay (simulating unpredictable real dropdown inflation time), populates
  and reveals a scrollable list, firing `TYPE_WINDOW_CONTENT_CHANGED` on the
  list view. This exists to exercise "wait for the event, don't sleep
  blindly."
- List entries `OpMode Slot 0`..`OpMode Slot 3` interspersed with filler
  entries. Slots 0 and 1 are visible on first render; slots 2 and 3 are
  pushed off-screen (see `MainActivity.OPMODE_ENTRIES`), so matching them
  requires the resolver's scroll-and-match path, not just a first-screen
  scan.
- Every button click and successful OpMode selection logs a distinctive
  line under tag `FakeDriverStation` (`INIT_CLICKED`, `START_CLICKED`,
  `STOP_CLICKED`, `OPMODE_SELECTED:<name>`), which is how the harness
  verifies outcomes without needing UI inspection for most assertions.

## Test cases and what each proves

Every case below fires the debug-only `DEBUG_COMMAND` broadcast described
above, which reaches `RobotResetService.handleCommand(...)` — the identical
method a real `Ctrl+Alt+Fn` chord's `onKeyEvent` calls. The chord decoding
and key-consumption step upstream of `handleCommand` (`ChordDecoder`'s
exact-modifier matching, the down/up/repeat filtering in `onKeyEvent`) is
exercised manually via `KeyMonitorActivity`/`ConsumeToggle`, not by this
harness — see "What this harness does NOT prove" below. (`ChordDecoder` has
no dedicated JVM unit test yet; it's a plain, dependency-free class and
would be a good candidate for one, but that's outside this lane's scope.)

| # | Case | Trigger | Proves |
| - | ---- | ------- | ------ |
| 1 | INIT while disabled | `cmd=INIT` | Fail-safe / Gotcha 3: clicking a disabled node is a no-op, not a retry loop. Asserts `INIT_CLICKED` does **not** appear. |
| 2 | OpMode slot 0 (on-screen) | `cmd=OPMODE_SLOT slot=0` | Basic element resolution + click works, and selecting an OpMode enables INIT. Asserts `OPMODE_SELECTED:OpMode Slot 0` appears, plus a live `uiautomator` check that the INIT node's `enabled` attribute flips to `true`. |
| 3 | OpMode slot 3 (off-screen) | `cmd=OPMODE_SLOT slot=3` | The scroll-and-match path, not just trivial first-screen matching. Given a longer wait budget to cover the list's populate delay plus however many bounded scroll/rescan cycles the resolver needs. Asserts `OPMODE_SELECTED:OpMode Slot 3` eventually appears. |
| 4 | INIT again, now enabled | `cmd=INIT` | The other half of Gotcha 3: once enabled, the same trigger now *does* click. Asserts `INIT_CLICKED` appears. |
| 5 | START | `cmd=START` | START resolves and clicks. Asserts `START_CLICKED`. |
| 6 | STOP | `cmd=STOP` | STOP resolves and clicks. Asserts `STOP_CLICKED`. |
| 7 | Any trigger while FakeDriverStation is not foregrounded (Home pressed first) | `cmd=START` | The purest form of the fail-safe property: absent target, nothing happens — no click markers appear at all. This is the harness's most valuable case together with #1: both are negative controls proving nothing happens when the target is absent or disabled, which is the entire justification for element-resolution over the reverted coordinate-tap approach (`docs/design-accessibility-tap.md`). |

Test 2 and test 3 deliberately select different slots (0 vs 3) that differ in
whether they're on-screen, so together they cover both the "found
immediately" and "found only after scrolling" branches of the resolver's
OpMode selection sequence described in `docs/design-accessibility-tap.md`.

## What this harness does NOT prove

- **That a real USB HID keyboard chord reaches `onKeyEvent` at all.** See "Why
  the harness fires a broadcast instead of a real key-event chord" above.
  `adb shell input` cannot reach the accessibility `KeyboardInterceptor`
  stage on an emulator or real hardware, so nothing in this repo's tooling
  can exercise that leg. It is verified only by the brief's real-hardware
  Step 0 bench check (plug in a USB keyboard, press `Ctrl+Alt+F1`, confirm
  `onKeyEvent` fires) — a manual, one-time, per-phone check, not something
  this script runs.
- **That returning `true` from `onKeyEvent` actually hides the chord from the
  real Driver Station app.** That also requires a real key event delivered
  through the real input stack, for the same reason as above, and is
  likewise a real-hardware-only check.
- Everything else in the chord's life cycle *before* `handleCommand` —
  `ChordDecoder`'s exact-modifier-match logic and the down/up/repeat
  filtering in `onKeyEvent` — lives entirely upstream of the seam this
  harness drives, so it isn't exercised here either. It's currently checked
  only by manual `KeyMonitorActivity`/`ConsumeToggle` inspection at the
  bench, not by an automated test of any kind.

## Known limitations

- **Fixed sleeps between firing a trigger and reading logcat/UI state are
  used deliberately in this script**, per the task brief's own guidance: this
  is polling an external emulator process from an outside script, not the
  in-app event-driven logic the design docs are strict about (that
  discipline — "don't sleep blindly, subscribe to the accessibility event" —
  applies to the app itself, i.e. `RobotResetService`'s own OpMode-selection
  state machine, not to this bash/PowerShell-level test driver). The waits
  are generous (2s for simple clicks, 3s for an on-screen OpMode select, 6s
  for the off-screen scroll-and-match case) but are still fixed sleeps, not
  a "wait until settled" primitive, so a sufficiently slow device/CI runner
  could in principle need the numbers bumped.
- **Test 2's "INIT becomes enabled" assertion uses a live `uiautomator dump`**
  (via `Get-NodeEnabled` in `tools/e2e.ps1`) rather than a logcat marker,
  since `FakeDriverStation` doesn't log an "INIT enabled" event of its own.
  This is slower (a full UI dump round-trip) than the logcat-marker
  assertions used elsewhere, which is why it's only used once rather than
  for every state check.
- **The off-screen slot (test 3) is currently fixed to slot 3** (see
  `MainActivity.OPMODE_ENTRIES` — slots 2 and 3 are both off-screen on a
  typical emulator display; slot 3 was picked arbitrarily as "further
  down"). If `OPMODE_ENTRIES` changes, re-check which slots are actually
  off-screen at the AVD's resolution/density before assuming this still
  holds.
- **`local.properties`** (machine-local Android SDK path) is gitignored, as
  it already was in this repo before this lane's changes — nothing new here,
  noted only because `./gradlew` needs it to exist locally to build.

## Related scripts

- `tools/uiautomator-dump.ps1` — thin wrapper for the Phase-4 real-hardware
  discovery step (`adb shell uiautomator dump` + `adb pull`). Also handy
  standalone when debugging why a `TargetSpec` isn't matching.
- `tools/logcat-robotreset.ps1` — tails logcat filtered to the RobotReset
  app's process (or a given tag list), for bench debugging outside this
  harness.
