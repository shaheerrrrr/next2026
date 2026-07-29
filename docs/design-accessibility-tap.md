# Design: Element-Based Driver Station Control

**This is a design document, not a description of the deployed system.** Nothing
here is implemented. Current deployed behavior lives in
[Protocol Reference](protocol.md) and [Runtime Procedures](procedures.md).

## Decisions

| Decision | Value |
| --- | --- |
| Coordinate / HID digitizer approach | **Removed.** Not a fallback. |
| First target phone | **Fable only.** Flash and Sol deferred. |
| Select OpMode by name | **In scope for v1.** |
| Android app location | Branch `robot-reset-app`, created off `master` |

`master` is the upstream FtcRobotController v11.1 tree and already carries the
Gradle wrapper, `settings.gradle`, and Android plugin configuration, so the app
should be a new module alongside `:FtcRobotController` and `:TeamCode` rather
than a fresh standalone project. It builds as its own APK; it is not part of the
Robot Controller or Driver Station app.

Because only Fable is in scope, `sol/sol.ino` and `flash/flash.ino` are not
touched by v1 at all. Sol's 32u4 flash pressure is therefore not a v1 concern.

## Problem

The driver needs to press INIT, START, and STOP in the Android FTC Driver
Station app from the driver station, with nobody at the phone, and to select an
OpMode by name.

A coordinate-based approach was implemented and then removed. The reason it was
removed is the constraint this design exists to satisfy: a coordinate tap is
**stateless and blind**. It does not press INIT, it presses whatever rectangle is
at that position at that instant. If the OpMode dropdown is open, a dialog is up,
the app is on a different screen, or the layout shifted, the tap still fires and
presses something else. The link has no acknowledgement, so the driver cannot
tell.

The failure mode is "something wrong happens", not "nothing happens". Selecting
an OpMode by name is also simply not expressible as a coordinate, since the list
contents and ordering are not known in advance.

## Approach

Resolve the target by **UI element** on the phone instead of by position.

An Android AccessibilityService runs on each Driver Station phone. It receives a
trigger, looks up the node by view-id or visible text, checks it is present and
enabled, and performs `ACTION_CLICK`. If the node is not there, nothing happens.

```
driver station (Python/Flask)
  -> 21-byte v3 LoRa frame carrying a command opcode
  -> Arduino Uno            (unchanged, forwards validated frames)
  -> Feather                (emits a HID keyboard chord)
  -> Android phone
  -> AccessibilityService   (intercepts chord, resolves element, clicks)
```

Key property: **fails safe.** `findAccessibilityNodeInfosByText("INIT")` returns
empty when INIT is not on screen, so the action is skipped. Compare a blind tap,
which always does something.

## Why a HID keyboard chord as the trigger

The Feather must signal the phone. Options considered:

| Transport | Verdict |
| --- | --- |
| HID keyboard chord | **Chosen.** Works on all three boards, no extra cable, no CDC. |
| USB CDC serial + app reads port | Rejected. Sol's 32u4 cannot do composite HID+CDC, and it adds a USB permission dance. |
| Reuse existing gamepad buttons | Rejected. Those are consumed by the DS app as robot controls; overloading them causes real robot input. |

An AccessibilityService can intercept key events globally, which no ordinary app
can do. This is the only sanctioned global key hook on Android.

Proposed chords, chosen to be implausible as genuine driver input:

| Chord | Action |
| --- | --- |
| `Ctrl+Alt+F1` | INIT |
| `Ctrl+Alt+F2` | START |
| `Ctrl+Alt+F3` | STOP |

Android's `KeyEvent` has no F13-F24, so a modifier chord is used instead of an
exotic single key. Modifiers live in the HID keyboard report's modifier byte, so
this is trivial to emit. The service consumes the event (`onKeyEvent` returns
`true`) so the DS app never sees it.

## Protocol impact

Frame length (21 bytes), version (`3`), magic, checksum, and addressing stay
unchanged. The Uno stays unchanged. Only the button bitmask gains a meaning,
exactly as the existing tap feature did.

- New bit `BTN_UI_CMD` (next free bit after `BTN_TAP`).
- When set, `lx` carries a **command enum**, not a coordinate. `ly`, `rx`, `ry`,
  `lt`, `rt` are zero.
- Same rising-edge plus cooldown discipline the tap path already uses, for the
  same reason: the host holds the bit across several frames for redundancy, and
  the receiver must act exactly once.
- Receivers force gamepad output neutral while the bit is set.

An enum in an `int16` leaves room for far more than three actions, which matters
for the roadmap below.

## Android component

Minimal app, no network, no root.

- `AccessibilityService` subclass.
- Manifest: `BIND_ACCESSIBILITY_SERVICE` permission, intent-filter
  `android.accessibilityservice.AccessibilityService`, meta-data pointing at a
  config XML.
- Config XML: `canRetrieveWindowContent="true"`,
  `canRequestFilterKeyEvents="true"`.
- `onServiceConnected()` must set `FLAG_REQUEST_FILTER_KEY_EVENTS` on the
  `serviceInfo`, or `onKeyEvent` never fires. Declaring it in XML alone is not
  sufficient.
- A small status activity so an operator can confirm the service is live and see
  a log of recent resolutions.

Resolution logic, in order:

1. Prefer `findAccessibilityNodeInfosByViewId` if the DS app's resource-ids are
   stable across versions. Fall back to `findAccessibilityNodeInfosByText`.
2. **A matched node is frequently not the clickable one.** Walk up parents until
   `isClickable()` is true. This is the most common way naive implementations
   fail.
3. Check `isEnabled()` before clicking. INIT is disabled until an OpMode is
   selected, and clicking a disabled node should be a no-op, not a retry loop.
4. On no match, log and do nothing.

## Selecting An OpMode By Name

In scope for v1, and the clearest justification for the whole approach: this is
not expressible as a coordinate at all, because the list contents, ordering, and
scroll position are unknown in advance.

Sequence the service performs:

1. Click the OpMode selection control to open the list.
2. Wait for the list window. Do not sleep blindly — subscribe to
   `TYPE_WINDOW_CONTENT_CHANGED` / `TYPE_WINDOW_STATE_CHANGED` and act on the
   event, because dropdown inflation time is not predictable.
3. Match the target entry by visible text.
4. If the entry is not in the current node tree, the list is scrolled. Perform
   `ACTION_SCROLL_FORWARD` on the scrollable container and re-scan, bounded by a
   maximum attempt count so a missing name terminates instead of looping.
5. Click the matched entry, walking up to a clickable ancestor as below.
6. If the name is never found, close the list and do nothing.

This needs the OpMode name transported, not just an enum. A name does not fit in
the `lx` field, so v1 restricts LoRa-side OpMode selection to a **preconfigured
slot index** (`OPMODE_SLOT_0..n`) whose names are configured in the phone app,
rather than sending arbitrary text over a 21-byte frame. Sending full strings
would require frame fragmentation and is deliberately out of scope.

Step 4 is where most of the real complexity lives. Budget for it.

## Relationship to the removed coordinate work

The coordinate implementation was reverted before being committed, so it is not
recoverable from history and **nothing is inherited as code.** The transport
design carries over, the implementation does not.

| Layer | Status for this design |
| --- | --- |
| LoRa transport, 21-byte v3 frame | Unchanged, nothing to build |
| `driverstation/driverstation.ino` | Unchanged, no reflash |
| Feather rising-edge + cooldown state machine | Pattern is known and proven to compile, but must be rewritten |
| Host pulse mechanism, HTTP route, dashboard buttons | Must be rewritten, carrying a command enum |
| Digitizer descriptor, `TAP_PRESETS` | Not wanted |

This is not purely a loss. A clean implementation of a command path is simpler
than converting a coordinate path would have been, and the descriptor-variant
hedging and per-mille scaling are no longer needed at all.

## Further capability this unlocks

Not in v1 scope, recorded so the command enum is sized for it:

- **Read state back.** The service can inspect the DS app's own telemetry,
  connection status, and error text. This is the only route to genuine feedback in
  this system, since the LoRa link is one-way. It needs a return path to be useful
  remotely, but the information becomes reachable.
- **Dismiss dialogs**, restart the robot, and similar recovery actions.

## Risks and unknowns

Ordered by how likely they are to sink the approach.

1. **Does `onKeyEvent` fire for a USB HID keyboard?** It should — these arrive as
   ordinary key events — but this is the load-bearing assumption and must be
   verified on real hardware before anything else is built.
2. **Android 13+ blocks sideloaded apps from being granted accessibility access**
   until the user clears a "Restricted setting" in App info. Real friction. Needs
   confirming against the actual Android version on these phones. Prior
   investigation of build artifacts suggested the FTC project targets API 28, but
   that says nothing about the phones' OS version.
3. **DS app resource-id stability** across FTC SDK releases is unknown. Text
   matching is more robust to renames but breaks on localisation. Probably want
   view-id with text fallback.
4. **Service enablement is not remotely observable.** If someone disables the
   service, or the OS kills it, the driver has no way to know. It survives reboot
   once enabled, which is the main advantage over Shizuku or `adb tcpip`, but
   pre-session verification on the phone is still required.
5. **Still no remote acknowledgement.** Failure becomes safe rather than
   dangerous, which is the point, but the driver still cannot confirm success. The
   existing independent 5.8 GHz FPV camera path is the practical mitigation if a
   camera can see the phone screen.

FTC competition legality is explicitly not a constraint for this project per
[Architecture](architecture.md).

## Effort

| Piece | Size | Notes |
| --- | --- | --- |
| Android app | Largest unknown | New component and toolchain for this repo |
| OpMode list scroll-and-match | Moderate, underestimated by default | The genuinely fiddly part; see step 4 above |
| Feather HID keyboard interface | Moderate | Second interface on Fable's M0. Sol and Flash untouched in v1. |
| Host command enum | Small | Route, pulse, and dashboard buttons all need rewriting |
| Bench verification | The real cost | Risk 1 gates everything else |

## Build Sequence

Ordered so the load-bearing unknown is settled before anything expensive is
built. Step 0 is not optional.

**Step 0 — spike the trigger.** A throwaway Feather sketch that emits
`Ctrl+Alt+F1` on a timer, plus a stub AccessibilityService whose `onKeyEvent`
does nothing but log. Install on Fable's phone. Confirm the chord arrives and
that returning `true` prevents the DS app from seeing it.

If this fails, the trigger mechanism changes and most of the rest of this
document is void. Nothing else should be built until it passes.

**Step 1 — element resolution, on-phone only.** With the service confirmed
receiving keys, wire the chords to INIT / START / STOP resolution and clicking.
Trigger from a USB keyboard plugged into the phone, not over LoRa. This isolates
resolution bugs from radio bugs.

**Step 2 — OpMode slot selection.** Add the open, wait-for-window, scroll,
match, click sequence. Still keyboard-triggered.

**Step 3 — LoRa path.** Add `BTN_UI_CMD` to the host and to `fable/fable.ino`
only, with the rising-edge and cooldown discipline. Now the dashboard drives it.

**Step 4 — docs.** Fold the result into `protocol.md`, `procedures.md`,
`deployment.md`, and `multi-robot.md`, and retire this design document or mark it
implemented. Per `AGENTS.md`, current-system docs describe deployed behavior in
present tense, so they should not be touched until steps 0-3 actually work.

## Repository Placement

The app lives on branch `robot-reset-app`, created off `master` at `203c2d3`.

Two things to resolve before writing code there:

- `master`'s `.gitignore` and the `ftc-lora` `.gitignore` differ. The working
  branch currently ignores `FtcRobotController/` and `TeamCode/`, which is why
  the FTC project is untracked in the LoRa worktree. Confirm the new module's
  build output is ignored but its source is tracked.
- Use a separate `git worktree` for `robot-reset-app` rather than switching
  branches in place. `AGENTS.md` forbids switching branches in a dirty worktree,
  and the repo already follows the worktree pattern elsewhere.

## Still Open

- The exact OpMode slot count and how names are configured in the phone app
  (hardcoded, `SharedPreferences`, or a config activity).
- Whether the status activity is worth building in v1 or whether `adb logcat` at
  the bench is sufficient during bring-up.
