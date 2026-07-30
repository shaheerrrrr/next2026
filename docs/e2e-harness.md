# E2E harness — `tools/e2e.ps1`

This is the automated emulator gate for `robot-reset-app`. It boots the
`RobotResetTest` AVD, builds and installs `RobotReset` and
`FakeDriverStation`, enables the accessibility service, fires each trigger
chord via `adb`, and asserts on the resulting `FakeDriverStation` logcat
markers (and, for one case, on the live UI tree via `uiautomator`).

This document explains how to run it, what each test case proves, and known
limitations. The design rationale it's built to prove out — "fails safe"
resolution by UI element, never a coordinate tap — is in
`docs/robot-reset-app-brief.md` and `docs/design-accessibility-tap.md`. Those
are the read-only spec; this file is just the harness runbook.

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

| # | Case | Proves |
| - | ---- | ------ |
| 1 | `Ctrl+Alt+F1` while INIT is disabled | Fail-safe / Gotcha 3: clicking a disabled node is a no-op, not a retry loop. Asserts `INIT_CLICKED` does **not** appear. |
| 2 | `Ctrl+Alt+F5` (slot 0, on-screen) | Basic element resolution + click works, and selecting an OpMode enables INIT. Asserts `OPMODE_SELECTED:OpMode Slot 0` appears, plus a live `uiautomator` check that the INIT node's `enabled` attribute flips to `true`. |
| 3 | `Ctrl+Alt+F8` (slot 3, off-screen) | The scroll-and-match path, not just trivial first-screen matching. Given a longer wait budget to cover the list's populate delay plus however many bounded scroll/rescan cycles the resolver needs. Asserts `OPMODE_SELECTED:OpMode Slot 3` eventually appears. |
| 4 | `Ctrl+Alt+F1` again, now enabled | The other half of Gotcha 3: once enabled, the same chord now *does* click. Asserts `INIT_CLICKED` appears. |
| 5 | `Ctrl+Alt+F2` | START resolves and clicks. Asserts `START_CLICKED`. |
| 6 | `Ctrl+Alt+F3` | STOP resolves and clicks. Asserts `STOP_CLICKED`. |
| 7 | Any chord while FakeDriverStation is not foregrounded (Home pressed first) | The purest form of the fail-safe property: absent target, nothing happens — no click markers appear at all. |

Test 2 and test 3 deliberately select different slots (0 vs 3) that differ in
whether they're on-screen, so together they cover both the "found
immediately" and "found only after scrolling" branches of the resolver's
OpMode selection sequence described in `docs/design-accessibility-tap.md`.

## Known limitations

- **Fixed sleeps between firing a chord and reading logcat/UI state are used
  deliberately in this script**, per the task brief's own guidance: this is
  polling an external emulator process from an outside script, not the
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
- **Integration status when this harness was built:** `RobotResetService`'s
  `onKeyEvent` (Phase 1 scope, see its doc comment) currently only decodes
  and logs matched chords to an in-process ring buffer
  (`RobotReset/.../ui/KeyEventLog.java`, surfaced in `KeyMonitorActivity`)
  — it does **not** call any `Resolver` to actually click anything yet.
  That wiring is a pending integration step the orchestrator does once all
  four lanes (including the resolver lane and the OpMode-selection state
  machine lane) land. Until that lands:
  - Test cases 1 and 7 (both asserting **absence** of a marker) pass
    regardless, since nothing ever clicks yet.
  - Test cases 2, 3, 4, 5, 6 (all asserting **presence** of a marker, or the
    INIT-enabled UI check) are expected to **fail** — not because the
    harness is wrong, but because there is nothing downstream of chord
    decoding to click anything yet. This is the expected, reported state;
    see the run log in this lane's report for confirmation that the
    chord-decode/consume plumbing itself works (verified via
    `KeyMonitorActivity` and `ConsumeToggle`) even though no click follows.
  - Re-run this script after the orchestrator's integration step lands. At
    that point all seven cases are expected to pass with no changes to this
    script. If a case still fails, it's now telling you something real about
    the wiring, not about missing integration.
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
