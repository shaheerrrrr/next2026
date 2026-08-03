# Build Brief: `robot-reset-app` — Android AccessibilityService for FTC Driver Station Control

> **Status: built and verified on real hardware.** This brief is kept as-written for
> historical/design context — it was the planning document, not a description of
> current state. For what's actually implemented, what's been confirmed working on
> real hardware, and how to set this up yourself, read `docs/bring-up.md` instead.
> `docs/design-accessibility-tap.md` has the design rationale, still accurate.

You are building a small Android app on branch `robot-reset-app` in the repo
`C:\Users\khans\OneDrive\Documents\GitHub\next2026` (Windows, PowerShell primary,
Bash also available). The branch is created off `master`.

Read this whole brief before writing code. It contains decisions that were
already made after investigation, and re-litigating them wastes your time.

---

## 1. What the overall system is

This repo controls three FTC robots — **Flash**, **Fable**, **Sol** — over a
long-range LoRa radio link. You are not touching most of it, but you need the
shape of it to understand your piece.

```
PS4/DS4 controller
  -> desktop Python app (driver_station_flask.py, Flask dashboard on :8765)
  -> USB serial 115200
  -> Arduino Uno + RFM95W          (dumb validated forwarder)
  -> LoRa 915 MHz broadcast
  -> Feather board on each robot   (Flash/Fable = Feather M0, Sol = Feather 32u4)
  -> USB OTG into that robot's Android phone
  -> phone runs the stock FTC Driver Station app
  -> phone Wi-Fi -> REV Control Hub -> motors
```

The Feather **impersonates a USB HID gamepad**. The Driver Station app thinks a
normal controller is plugged in. That is how remote driving works today, and it
already works.

Wire format (do not change): 21-byte frame, protocol version 3, magic
`0xA5 0x5A`, little-endian, XOR checksum over bytes 2..19, byte 3 is the target
robot ID (Flash=1, Fable=2, Sol=3, broadcast=255). Full spec is in
`docs/protocol.md` on the `ftc-lora` / `remote-adb` branches.

Relevant repo docs live on branch `remote-adb`, **not** on `master`. If you are
working in a worktree checked out from `master`, they will not be on disk. Read
them with `git show`, for example:

```powershell
git show remote-adb:docs/protocol.md
git show remote-adb:docs/design-accessibility-tap.md
git show remote-adb:AGENTS.md
```

The set worth reading: `AGENTS.md`, `docs/protocol.md`,
`docs/architecture.md`, `docs/deployment.md`, `docs/procedures.md`,
`docs/multi-robot.md`, and `docs/design-accessibility-tap.md` — the design
document this brief implements, which carries more rationale than is repeated
here.

This brief itself is `docs/robot-reset-app-brief.md` on `remote-adb`.

`AGENTS.md` is a binding operating contract for this repo. Read it.

---

## 2. The problem you are solving

The driver is far from the robot. Nobody is standing at the Driver Station phone.
So nobody can press **INIT**, **START**, or **STOP** in the DS app, or **select
which OpMode to run**. Those are touchscreen actions, and the gamepad cannot
reach them.

Your app makes those actions triggerable remotely.

---

## 3. Two approaches already tried and rejected — do not re-propose these

**Rejected: `adb shell input tap` over the LoRa link.**
Impossible with this hardware. The Feather is a USB *device* and the phone is the
USB *host*. ADB requires the inverse — the phone must be the device and something
else the host. Two USB devices cannot talk to each other. There is exactly one USB
port on the phone and the Feather occupies it as the HID gamepad. This is a
physical role conflict, not a software gap. Do not suggest a USB hub; it is a role
problem, not a port-count problem.

**Rejected and already reverted: HID digitizer touch at screen coordinates.**
This was fully implemented (Feather digitizer descriptor, per-mille coordinates in
the LoRa frame, dashboard buttons) and then deliberately removed. It worked, but a
coordinate tap is **stateless and blind**: it does not press INIT, it presses
whatever rectangle is at that position at that instant. If the OpMode dropdown is
open, a dialog is showing, the app is on another screen, or the layout shifted, the
tap still fires and presses something else. The radio link is one-way so the driver
gets no confirmation. The failure mode is "something wrong happens", not "nothing
happens".

Also: selecting an OpMode by name is not expressible as a coordinate at all,
because list contents, ordering, and scroll position are unknown in advance.

None of that code exists any more. It was never committed. Do not try to recover
or reuse it.

---

## 4. The approach you are implementing

An **AccessibilityService** on the Driver Station phone resolves targets by **UI
element**, not position, and clicks them.

```
driver station -> LoRa frame with a command opcode
              -> Feather emits a HID keyboard chord
              -> Android delivers it as a key event
              -> YOUR AccessibilityService intercepts it
              -> finds the node by view-id / text
              -> checks it exists and is enabled
              -> performs ACTION_CLICK
```

The critical property is that it **fails safe**. If the target node is not on
screen, `findAccessibilityNodeInfosByText` returns empty and nothing happens.
Compare a blind coordinate tap, which always does something. That inversion —
nothing, versus something wrong — is the entire justification for this design.

An AccessibilityService is the only sanctioned global key hook on Android. No
ordinary app can intercept key events system-wide. It also **survives reboot**
once enabled, unlike Shizuku or `adb tcpip 5555`, which need re-arming.

No root required. No network required. Do not request `INTERNET`.

### Trigger chords

| Chord | Action |
| --- | --- |
| `Ctrl+Alt+F1` | INIT |
| `Ctrl+Alt+F2` | START |
| `Ctrl+Alt+F3` | STOP |
| `Ctrl+Alt+F5..Fn` | Select OpMode slot 0..n |

Android's `KeyEvent` has no F13-F24 keycodes, which is why this uses a modifier
chord rather than an exotic single key. Modifiers live in the HID keyboard
report's modifier byte, so they are trivial for the Feather to emit. Chords were
chosen to be implausible as genuine driver input.

Your service must **consume** these events (`onKeyEvent` returns `true`) so the DS
app never sees them.

---

## 5. Scope for v1 — hold this line

**In scope:**
- INIT, START, STOP by element resolution
- Select OpMode by **preconfigured slot index**
- **Fable's phone only** (robot ID 2)

**Out of scope:**
- Flash and Sol phones. Do not touch `flash/flash.ino` or `sol/sol.ino`.
- Arbitrary OpMode names sent over the radio. A name does not fit in the frame's
  `lx` field, and sending strings would require frame fragmentation. The slot
  names are configured **in your app**, and the radio carries only a slot index.
- Reading state back to the driver station. There is no return path; the LoRa link
  is transmit-only from the driver side. Recorded as future work only.
- Any change to the 21-byte frame size, version, checksum, or the Arduino Uno
  firmware. The Uno is a dumb forwarder and must not be reflashed.

---

## 6. Android implementation specifics

These are the details that most implementations get wrong. They are not optional.

**Manifest / config**
- Service declares `android.permission.BIND_ACCESSIBILITY_SERVICE`.
- Intent filter `android.accessibilityservice.AccessibilityService`.
- `meta-data` pointing at a config XML.
- Config XML needs `android:canRetrieveWindowContent="true"` and
  `android:canRequestFilterKeyEvents="true"`.

**GOTCHA 1 — the most common silent failure.** You must ALSO set the flag
programmatically in `onServiceConnected()`:

```java
AccessibilityServiceInfo info = getServiceInfo();
info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
setServiceInfo(info);
```

Declaring `canRequestFilterKeyEvents` in XML alone is **not sufficient**.
`onKeyEvent` will simply never fire and you will waste hours.

**GOTCHA 2 — a matched node is frequently not the clickable one.** After
`findAccessibilityNodeInfosByText(...)` or `findAccessibilityNodeInfosByViewId(...)`,
walk up the parent chain until you find a node where `isClickable()` is true, and
click that. Clicking the matched `TextView` itself usually does nothing.

**GOTCHA 3 — check `isEnabled()` before clicking.** INIT is disabled until an
OpMode is selected. Clicking a disabled node must be a logged no-op, not a retry
loop.

**Resolution order:** prefer `findAccessibilityNodeInfosByViewId` if the DS app's
resource-ids turn out to be stable, and fall back to text matching. Discover the
real ids and texts yourself with `uiautomator` (see section 8) — do not trust any
id you find in a tutorial. The DS app package is believed to be
`com.qualcomm.ftcdriverstation`; verify it, don't assume it.

### OpMode slot selection — the genuinely hard part

1. Click the OpMode selection control to open the list.
2. **Do not sleep blindly waiting for it.** Subscribe to
   `TYPE_WINDOW_CONTENT_CHANGED` / `TYPE_WINDOW_STATE_CHANGED` and act on the
   event. Dropdown inflation time is not predictable.
3. Match the target entry by visible text.
4. **If the entry is not in the current node tree, the list is scrolled.** Perform
   `ACTION_SCROLL_FORWARD` on the scrollable container and re-scan. Bound this with
   a maximum attempt count so a name that isn't there terminates instead of
   looping forever.
5. Click the matched entry, walking up to a clickable ancestor per Gotcha 2.
6. If never found, close the list and do nothing. Log it.

Step 4 is where most of the complexity lives. Budget accordingly.

---

## 7. Build sequence — Step 0 is a hard gate

**STEP 0 — spike the trigger. Do this before anything else.**

Build only: a stub AccessibilityService whose `onKeyEvent` does nothing but
`Log.d(...)` the keycode and modifiers. Install on Fable's phone, enable it in
Settings → Accessibility, plug in an ordinary USB keyboard, and press
`Ctrl+Alt+F1`.

Confirm two things:
1. The event reaches `onKeyEvent` at all.
2. Returning `true` prevents the DS app from seeing the keystroke.

**If a USB HID keyboard chord does not reach `onKeyEvent` on this phone, the entire
trigger mechanism is wrong and most of this design is void.** Stop and report that
rather than building on top of an unverified assumption. Do not proceed to Step 1
until Step 0 passes on real hardware.

**STEP 1** — wire the chords to INIT / START / STOP element resolution and
clicking. Trigger from a real USB keyboard, not over the radio. This isolates
resolution bugs from radio bugs.

**STEP 2** — add the OpMode slot open/wait/scroll/match/click sequence. Still
keyboard-triggered.

**STEP 3** — the LoRa path. Only after 0-2 work. This adds a new button bit to the
desktop app and to `fable/fable.ino` (a HID keyboard interface alongside the
existing gamepad interface). Coordinate with whoever owns those files; it is not
part of your app.

**STEP 4** — documentation, per `AGENTS.md` ownership rules. Not before 0-3 work,
because those docs describe deployed behavior in present tense.

---

## 8. Environment — verified state of this machine

| Thing | Status |
| --- | --- |
| Android SDK | **Present** at `C:\Users\khans\AppData\Local\Android\Sdk` |
| `adb` | **Present** at `<sdk>\platform-tools\adb.exe` (v1.0.41 / 37.0.0). NOT on PATH. |
| Installed platform | **only `android-34`** |
| build-tools | `34.0.0`, `37.0.0` |
| JDK | **MISSING.** No `java`/`javac` on PATH, `JAVA_HOME` unset, `C:\Program Files\Android` is empty. |
| `ANDROID_HOME` / `ANDROID_SDK_ROOT` | unset |
| Gradle | not on PATH; `master` ships a Gradle wrapper (`gradlew.bat`) |

**You cannot compile anything until a JDK is installed.** Flag this immediately
and confirm how the user wants it resolved rather than silently installing a JDK
or guessing a version. Recent Android Gradle Plugin versions need JDK 17+.

You may need to install an additional SDK platform depending on what `compileSdk`
you target; only `android-34` is present.

`uiautomator` for discovering element ids, with the DS app in the foreground:

```powershell
$adb = "C:\Users\khans\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb shell uiautomator dump /sdcard/window_dump.xml
& $adb pull /sdcard/window_dump.xml
```

Note the irony worth understanding: `adb` is genuinely useful here for
*development and discovery* at the bench, over a cable. It is not part of the
runtime path and cannot be, per section 3.

---

## 9. Repository placement

- Branch: `robot-reset-app`, off `master`.
- `master` is the upstream **FtcRobotController v11.1** tree (tip `203c2d3`). It
  already has `gradlew`, `settings.gradle`, `build.gradle`, and Android plugin
  config.
- Prefer adding a **new Gradle module** alongside `:FtcRobotController` and
  `:TeamCode` over standing up a separate project. It builds as its own APK — it
  is NOT part of the Robot Controller or Driver Station app, and must not be
  merged into either.
- **`.gitignore` warning:** the `ftc-lora`/`remote-adb` branches ignore
  `FtcRobotController/` and `TeamCode/`, `master`'s does not. Confirm your new
  module's *source* is tracked and only its *build output* is ignored. Check
  `git status` after your first build.
- `AGENTS.md` forbids switching branches in a dirty worktree. Use
  `git worktree add` for this branch; the repo already follows that pattern.
- Do not commit or push unless the user asks.

---

## 10. Known risks — report on these, do not paper over them

1. **Does `onKeyEvent` fire for a USB HID keyboard?** The load-bearing assumption.
   Step 0 exists solely to answer it.
2. **Android 13+ blocks sideloaded apps from receiving accessibility access** until
   the user clears a "Restricted setting" in App info. Determine the phone's actual
   Android version early. The FTC project targets API 28 per its build artifacts,
   but that says nothing about the phone's OS.
3. **DS app resource-id stability** across FTC SDK releases is unknown. Text
   matching survives renames but breaks under localisation.
4. **Service enablement is not remotely observable.** If the service is disabled or
   killed, the driver cannot tell. Pre-session verification on the phone is
   required. Consider a visible status surface in the app.
5. **Still no remote acknowledgement.** Failure becomes *safe*, which is the point,
   but the driver still cannot confirm success. The user has an independent 5.8 GHz
   FPV camera path that can see the phone screen; that is the practical mitigation.

FTC competition legality is explicitly **not** a design constraint for this
project — it is a personal build. Do not add legality caveats.

---

## 11. How to report back

- State plainly what you verified **on real hardware** versus what only compiles.
  This system drives real robots; an untested claim is worse than no claim.
- If Step 0 fails, say so and stop. Do not build Steps 1-3 on a broken assumption.
- List every file you created or changed.
- Call out anything you guessed at, especially DS app resource-ids and texts.
- Do not claim the service works because it installed. Working means a chord
  produced a click on the intended element.
