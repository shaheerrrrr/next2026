# Multi-Robot Operation

## Fleet Model

One driver station controls Flash, Fable, and Sol through one LoRa transmitter.
The radio transmission is physically broadcast, but every 21-byte gamepad frame
contains a numeric target robot ID. Each robot hears the frame and only the
matching Feather turns it into USB HID gamepad state.

| Robot | ID | Tele-op profile | Independent background behavior |
| --- | --- | --- | --- |
| Flash | `1` | Standard sticks, triggers, face buttons, and bumpers | None in this control system |
| Fable | `2` | Same standard HID profile as Flash | GPS point-to-point navigation on its Control Hub |
| Sol | `3` | Sticks, D-pad, right trigger, Cross, Circle, Square, and Options | None in this control system |

The reserved address `255` means all robots. Normal dashboard operation uses an
individual ID.

## Why Selection Works

The selected robot's name is not transmitted. The desktop converts the name to
one byte before building the frame:

```text
Flash -> 1
Fable -> 2
Sol   -> 3
```

All Feather receivers validate the same magic, version, length, and checksum.
Then each compares byte 3 with its compiled `ROBOT_ID`.

When a receiver sees a valid frame addressed to another robot, it immediately
neutralizes its local state and clears its fresh-frame marker. It continues to
send neutral USB HID reports to the phone. This makes switching deterministic:
the newly selected robot receives live controls while the previous robot is
actively released.

The 250 ms timeout is a second protection for cases where frames stop arriving
entirely.

## Selecting A Robot

There are two equivalent methods:

- Click Flash, Fable, or Sol in the top robot selector.
- Press Space while the dashboard page has normal keyboard focus.

Space cycles in this order:

```text
Flash -> Fable -> Sol -> Flash
```

The sliding highlight and selected card are desktop UI state. The next outgoing
frame carries the corresponding ID. At the normal 20 Hz rate, control transfer
begins on the next frame.

The selector does not start or stop a REV Control Hub OpMode. It changes which
Feather receives non-neutral HID state.

## Driver Registration

Each Android FTC Driver Station phone has its own USB controller registration.
Registration therefore has to be performed per robot:

1. Select the robot.
2. Click **Register Driver 1**.
3. Confirm that robot's phone shows the USB controller assigned to Driver 1.
4. Repeat for every other robot used in the session.

The desktop temporarily adds Cross and Options to frames for the selected ID.
The Feather maps Options to HID button bit 11, which is the empirically verified
registration mapping for these phones.

Changing the selected robot later does not erase registration.

Driver Station command chords (INIT/START/STOP/OpMode-select, Fable and
Flash) are a distinct mechanism from controller registration: registration
injects gamepad button bits so the phone recognizes a USB controller, while a
command chord emits a separate USB HID **keyboard** event that an on-phone
AccessibilityService intercepts to click a specific Driver Station UI
element. See
[Runtime Procedures](procedures.md#driver-station-command-chords-fable-only).

## Control Profiles

### Flash And Fable

Flash and Fable receive the complete standard profile:

- Left and right sticks
- Left and right analog triggers
- Cross, Circle, Square, and Triangle
- L1 and R1
- Options when injected for registration

Their Feather M0 firmware and custom HID descriptors are functionally
equivalent for the gamepad interface, except for compiled robot ID and name.
Fable's and Flash's Feathers both also present a second USB HID interface (a
boot-layout keyboard) that emits Driver Station command chords (INIT/START/
STOP/OpMode-select) when `BTN_UI_CMD` is set — `flash.ino` gained this after
`fable.ino`, as a direct mechanical port of the same feature, and it has
since been reflashed onto a physical Flash Feather and is fully verified
end-to-end on real hardware, same as Fable: OpMode-select, INIT, START, and
STOP all produce real clicks against Flash's own Driver Station app, with a
live Robot Controller connection. Sol has a different-in-kind implementation
too (a second Report ID on its single AVR HID interface, since its USB stack
has no equivalent to a second independent interface), and it has passed its
own Step-0 hardware check: Android does not split the second Report ID into
a separate logical input device the way it does for Fable/Flash's second
interface (one merged `Adafruit Feather 32u4` device with `KEYBOARD` set
alongside `JOYSTICK`/`DPAD` in its class bitmask), but a real chord still
reaches `onKeyEvent` correctly despite that. Sol is now also **fully
verified end-to-end** on real hardware: OpMode-select, INIT, START, and
STOP all produce real clicks against Sol's own Driver Station app, with a
live Robot Controller connection. Getting there also surfaced two firmware
issues specific to the 32u4/AVR `HID.h` USB stack (fixed in `sol.ino`) and
a Driver Station app quirk unrelated to any robot's firmware — see
`robot-reset-app:docs/bring-up.md` ("Multi-robot findings: Flash and Sol")
for the full detail: AVR's `HID_::SendReport()` is a blocking call with no
non-blocking readiness check (unlike TinyUSB's `usb_hid.ready()` on the M0
boards), so Sol's `loop()` now skips its routine gamepad send while a chord
press is outstanding, to avoid the shared endpoint stalling the
chord-release timer. See
[Driver Station Command Injection](protocol.md#driver-station-command-injection).

### Sol

Sol receives the same 21-byte radio frame, but its Feather 32u4 only exposes the
controls required by Sol:

- Left and right sticks
- Four D-pad directions, including diagonals as a HID hat
- Right analog trigger
- Cross
- Circle
- Square
- Options for registration

The desktop still transmits the shared fields. Sol's firmware ignores controls
that are not part of its HID profile. This keeps one fleet packet format while
allowing robot-specific behavior at the HID boundary.

## Fable Autonomy While Driving Another Robot

Fable navigation is not a stream of remote motor commands. The driver station
sends a GPS target over the separate ESP-NOW channel. Fable's Control Hub reads
that target through the Pico/I2C bridge and, once armed by the driver, computes
motor commands locally.

This leads to an important separation:

| Action | Effect |
| --- | --- |
| Select Fable | Address LoRa HID frames to Fable |
| Send a Fable target | Store target coordinates through ESP-NOW |
| Press Cross with Fable selected | Arm Fable's current Control Hub navigation mode when data is ready |
| Select Flash or Sol afterward | Transfer live HID tele-op to that robot; Fable can continue locally |
| Return to Fable and provide manual drive input | Override Fable navigation and return it to tele-op |
| Click Cancel Auto | Send `CLEAR` over the Fable navigation side channel |

The dashboard keeps a visible Fable autonomous indicator even when another
robot is selected. This is an operator reminder. It is not direct mode feedback
from the REV Control Hub.

## Dashboard State Versus Robot State

The dashboard knows:

- Which robot ID it is sending over LoRa
- Which target it requested
- Whether returned ESP-NOW telemetry says a target is valid
- Whether the desktop has issued a clear
- Whether meaningful Fable tele-op override input was transmitted

The dashboard does not currently know the authoritative Fable drivetrain mode.
It does not receive `TELEOP`, `AUTO_NAVIGATING`, or `GOONING` status from the
Control Hub.

Current desktop behavior marks Fable autonomous when a target is accepted or
returned as valid. It exits that state and repeatedly sends `CLEAR` when a
meaningful Fable drive/navigation-exit input is detected. Cross is deliberately
excluded because Cross arms navigation. Triggers and bumpers are also excluded
because Fable mechanism controls remain available during navigation. Square is
included because it exits point-to-point navigation, but the Control Hub uses
that same input to begin gooning rather than ordinary tele-op.

Use the Android Driver Station telemetry as the authority for actual Fable
drive mode.

## Logs And Status

Transmit logs are grouped by the selected robot for readability. A line such as:

```text
seq=1234 target=Fable lx=0 ly=-600 rx=100 ry=0 lt=0 rt=0 ...
```

does not put the word `Fable` on the radio. The transmitted target is numeric
value `2`.

The global indicators report the desktop controller, Uno serial connection,
Fable navigation serial path, and browser/dashboard stream. They do not report
per-Feather LoRa acknowledgements; the current tele-op link is one-way.

## Safe Switching Procedure

1. Release the sticks, triggers, and buttons.
2. Select the new robot or press Space.
3. Verify the selector moved to the intended robot.
4. Confirm the selected robot's log advances.
5. Apply a small test input before full motion.

The software neutralizes the previous receiver, but beginning from a physically
neutral controller state makes transfer easier to observe and reduces surprises
from a held mechanism button.

## Failure Cases

| Failure | Expected behavior |
| --- | --- |
| Controller disconnects | Desktop remains running, Gamepad indicator reports the issue, outgoing state becomes unavailable/neutral |
| Uno serial disconnects | Desktop retries the specified port; no new LoRa tele-op frames until restored |
| LoRa transmission stops | Every Feather neutralizes HID after 250 ms |
| Frame targets another robot | Receiver neutralizes immediately |
| One Feather is powered off | Other addressed receivers continue; there is no per-robot acknowledgement |
| Fable ESP-NOW telemetry stops | Tele-op over LoRa can continue; map telemetry becomes stale |
| Driver switches away during Fable navigation | Fable can continue autonomous motion locally |
| Browser closes | Python and serial threads can continue; the UI is unavailable until reloaded |
| Python stops during Fable navigation | HID becomes neutral, but the Control Hub may continue autonomy |

## Present Limitations

- There is one active tele-op target at a time.
- Tele-op LoRa has no robot-to-driver acknowledgement or RSSI telemetry in the
  dashboard.
- Fable is the only robot with the ESP-NOW GPS navigation side channel.
- Fable's autonomous badge is inferred rather than sourced from Control Hub
  mode feedback.
- The broadcast address is implemented but normal UI flow does not use it for
  simultaneous driving.
- Fleet selection is not an emergency-stop system.

Exact bytes are in [Protocol Reference](protocol.md). Full Fable behavior is in
[Fable Navigation](fable-navigation.md). Runtime commands are in
[Runtime Procedures](procedures.md).
