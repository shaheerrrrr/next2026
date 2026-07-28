# Fable Navigation

## Purpose

Fable can receive a GPS target from the driver dashboard and travel toward it
using robot-side GPS position, Control Hub IMU heading, and local drivetrain
control. The camera shown in the overall hardware concept is not part of the
current navigation algorithm, and obstacle avoidance is not implemented.

The navigation system augments the existing LoRa HID tele-op pipeline. It does
not replace it.

## Component Chain

```text
Browser map
  -> driver_station_flask.py
  -> USB serial
  -> driver-side M5Stamp C3
  -> ESP-NOW channel 1
  -> Fable robot ESP32-C3
  -> UART FNAV snapshot
  -> Raspberry Pi Pico
  -> I2C 0x42
  -> Fable REV Control Hub
  -> NavigationSubsystem + FableTeleOp
  -> drivetrain
```

The reverse telemetry chain is:

```text
GPS -> robot ESP32-C3 -> ESP-NOW telemetry -> driver C3
    -> newline JSON over USB serial -> Flask -> browser map and status
```

Every component and wire is listed in
[Deployment Reference](deployment.md#deployment-matrix). Exact binary layouts
are in [Protocol Reference](protocol.md#fable-esp-now-protocol).

## Responsibilities

| Component | Responsibility | Explicitly does not do |
| --- | --- | --- |
| Browser/Flask | Visualize position, calibrate field, select target, send clear/target, show inferred mode | Command motors directly |
| Driver C3 | Convert serial commands to ESP-NOW, return validated telemetry JSON, show LED status | Decide navigation safety |
| Robot C3 | Parse GPS, own target state, publish telemetry and FNAV snapshots | Drive the robot |
| Pico | Validate and expose the latest snapshot over I2C | Interpret GPS or control motors |
| NavigationSubsystem | Read I2C, fuse GPS with IMU, check readiness, calculate geometry | Own drivetrain mode |
| PointToPointController | Convert distance and heading error into drive/turn requests | Read hardware |
| FableTeleOp | Own drive modes, arming, overrides, stop conditions, and motor output | Render the web map |

This separation is important for debugging. A dot moving on the web map proves
the robot-to-driver telemetry path works. It does not prove the Pico, I2C
configuration, Control Hub readiness, or drivetrain mode works.

## Position And Fix Quality

The robot ESP32-C3 reads GPS at 9600 baud and publishes a new navigation
sequence whenever TinyGPSPlus reports an updated location.

| Fix value | Dashboard label | Robot C3 rule |
| --- | --- | --- |
| `0` | None | Location invalid or older than 2000 ms |
| `1` | Weak | Recent valid location without a valid satellite count of at least four |
| `2` | Good | Recent valid location with at least four satellites |

The C3 also reports satellite count, HDOP, GPS age, characters processed, and
uptime. Fable's Control Hub requires `GOOD`, not merely a nonzero location.

The Control Hub applies a stricter freshness rule: GPS data and the advancing
navigation sequence must each remain within 1500 ms after accounting for time
since the I2C read.

## Target Lifecycle

### Selecting

Clicking the main Fable map creates a local selected target. The UI draws a
target marker and a dashed line from the most recent current position. At this
stage, nothing has been sent to the robot.

### Sending

Clicking **Send Target** posts decimal coordinates to the local Flask API. The
server converts them to signed E7 integers and writes:

```text
TARGET <lat_e7> <lon_e7>
```

to the driver C3. That board assigns a command sequence and broadcasts a
CRC-protected ESP-NOW target packet.

The robot C3 stores the coordinates, copies the command sequence to
`targetSeq`, and marks the target valid. Returned telemetry lets the dashboard
confirm the stored target.

### Arming Motion

A valid target does not start the drivetrain. With Fable selected over LoRa,
the driver presses PS4 Cross. The Feather exposes Cross as FTC `gamepad1.a`.
On that rising edge, `FableTeleOp` forces a fresh navigation read and enters
`AUTO_NAVIGATING` only if all readiness checks pass.

This deliberate two-stage process prevents an accidental map click or HTTP
request from immediately moving the robot.

### Changing

A new target receives a new target sequence. If Fable is already navigating and
the Control Hub observes a different target sequence, it stops and returns to
tele-op. The driver must press Cross again to arm navigation toward the new
target.

### Clearing

Clicking **Cancel Auto**, or triggering the desktop's Fable navigation-exit
handling, sends:

```text
CLEAR
```

The robot C3 clears target validity and sends that state back in telemetry. A
Control Hub currently navigating sees the invalid target through I2C and stops
unless the same HID frame deliberately requests another drivetrain mode. In
particular, Square clears the GPS target on the desktop side while also telling
`FableTeleOp` to enter `GOONING`.

The desktop tracks a pending clear until returned telemetry shows the target is
invalid. This avoids immediately re-entering the yellow autonomous UI state
from one last target-valid telemetry packet.

## Control Hub Drive Modes

Fable runs one FTC TeleOp OpMode with three internal drivetrain modes:

```text
TELEOP
  | Cross/A and navigation ready
  v
AUTO_NAVIGATING
  | arrival confirmed                 -> GOONING
  | Circle/B or manual drive          -> TELEOP
  | target invalid/stale/data invalid -> TELEOP
  | target sequence changes           -> TELEOP, re-arm required

TELEOP or AUTO_NAVIGATING
  | Square/X rising edge
  v
GOONING
  | any non-Square driver input       -> TELEOP
```

### Driver Controls

| Control | Current Fable behavior |
| --- | --- |
| Triangle/Y during INIT | Reset IMU yaw while Fable points toward the field direction defined as north |
| Cross/A rising edge | Enter `AUTO_NAVIGATING` if navigation is ready |
| Circle/B rising edge | Cancel active navigation and stop drivetrain ownership |
| Left stick Y or right stick X above `0.18` | Immediate manual override |
| Square/X rising edge | Start `GOONING` |
| Any non-Square input during gooning | Stop gooning and return to tele-op |

Cross is latched; it does not need to remain held. Temporary LoRa HID loss does
not cancel local autonomy. When LoRa returns, manual drive or Circle can take
control back.

Mechanism controls remain available during `AUTO_NAVIGATING`: triggers control
the candy cane and bumpers control the current roller-only intake subsystem.
Those mechanism inputs are deliberately not treated as desktop navigation
override inputs.

## Readiness Rules

The current Fable Control Hub requires every item below before entering or
continuing point-to-point navigation:

- Complete 56-byte I2C snapshot with valid magic, version, length, and CRC
- GPS location-valid flag
- Fix quality `GOOD`
- Effective GPS age no greater than 1500 ms
- Target-valid flag
- Navigation sequence that continues advancing, with a 1500 ms stale limit

The ESP-NOW driver-link flag and command age are informational only. Once a
target has reached the robot and remains valid, an expired heartbeat does not
currently stop autonomous navigation.

This is why the safe shutdown procedure requires stopping autonomy or the
OpMode before shutting down the desktop.

## Navigation Algorithm

The Control Hub calculates:

```text
distance = geographic distance(current coordinate, target coordinate)
bearing = initial compass bearing(current coordinate, target coordinate)
heading error = normalize(bearing - IMU compass heading) to -180..180 degrees
```

The current point-to-point controller:

- Drives forward while steering along an arc
- Does not intentionally point-turn or reverse during navigation
- Uses proportional steering at `0.020` power per degree
- Limits absolute turn to `0.70`
- Limits turn to 78 percent of current forward power
- Uses minimum turn `0.15` outside a 2-degree heading deadband
- Uses drive power from `0.50` to `0.90`
- Begins slowing inside 35 meters
- Uses a 3-meter arrival radius
- Requires three distinct GPS sequence updates inside that radius

After arrival confirmation, Fable enters `GOONING`, alternating forward and
backward every 1.25 seconds at power 0.75 until interrupted.

These are current software values, not universal field-safe settings. Outdoor
tuning must account for GPS scatter, stopping distance, terrain, and drivetrain
response.

## Poll And Update Rates

| Layer | Current rate |
| --- | --- |
| Robot C3 GPS snapshot production | 10 Hz |
| Robot C3 ESP-NOW telemetry | 10 Hz |
| Driver C3 heartbeat commands | 2 Hz |
| Control Hub I2C polling during navigation | 5 Hz |
| Control Hub I2C polling in tele-op/gooning | 1 Hz |
| Tele-op LoRa gamepad frames | Normally 20 Hz |
| FTC telemetry display | Up to 10 Hz |

Polling the Pico does not cause a new GPS fix. It retrieves the latest snapshot
already published by the robot C3.

## Map And Calibration

### Main Map

The dashboard uses Leaflet with two selectable tile layers:

| Layer | Provider |
| --- | --- |
| Street | OpenStreetMap |
| Satellite | Esri World Imagery |

Both layers require internet access to load tiles. Fable GPS and ESP-NOW do not
depend on either provider.

If four calibrated field corners exist, the map draws that polygon and fits it
with a small buffer. The map is not rotated or perspective-warped; north remains
map north.

Without calibration, the dashboard centers on the current received coordinate
and draws a faint square around it based on the configured fallback field size.
The fallback is only a visual selection area.

### Calibration Popup

The calibration popup opens only when Fable is selected and the operator clicks
**Calibrate Field**. Each of four corners can be supplied by:

- Typing latitude and longitude
- Choosing the active corner and clicking the calibration map
- Copying Fable's current coordinate when it is available

Calibration is saved in browser `localStorage` under
`fableFieldCalibration`. The selected street/satellite layer is saved under
`fableTileMode`.

Calibration data remains on the driver browser. It is not included in ESP-NOW,
FNAV, or I2C messages. It does not limit targets and is not a geofence.

## Operator Workflow

1. Start all hardware and the dashboard using
   [Runtime Procedures](procedures.md#start-the-driver-station).
2. Initialize the `Fable` OpMode without pressing START.
3. Point Fable toward the field direction defined as north.
4. Press Triangle once to reset IMU yaw.
5. Confirm a valid I2C packet, `GOOD` fix, fresh GPS age, and advancing
   navigation sequence.
6. Press START. Fable remains in tele-op.
7. Select or verify the field calibration.
8. Click a target on the main map and inspect the coordinate.
9. Click **Send Target**.
10. Confirm the dashboard and Android Driver Station show the intended valid
    target and plausible distance/bearing.
11. Select Fable in the LoRa robot selector.
12. Press Cross once to arm navigation.
13. Keep the controller ready and watch actual robot motion continuously.
14. Move a drive stick or press Circle to override.

After Fable is navigating, the driver may select Flash or Sol. Fable can
continue because the motor controller is local to its Control Hub.

## Status Semantics

### Top Fable Nav Indicator

This primarily reports the desktop serial connection to the driver-side C3 and
whether navigation messages are being processed. It is separate from the
tele-op Uno Serial indicator.

### ESP-NOW Link Card

The card combines two directions of evidence:

- Receiving telemetry proves the robot C3 to driver C3 path is working.
- `driver heartbeat alive` means the robot C3 reports that it heard a valid
  target, clear, or heartbeat within 2000 ms.

`telemetry received, waiting for driver heartbeat` is therefore not a claim
that no telemetry exists. It means the return path is active but the latest
robot-reported driver-link flag is false. A fresh command/heartbeat should make
it live once the robot receives and reports it.

### GPS Quality Card

Read fix label, satellite count, HDOP, and age together. A plausible coordinate
with `WEAK` quality is useful for diagnostics but does not satisfy Control Hub
autonomous readiness.

### Yellow Autonomous Badge

The badge means the desktop currently believes a Fable target/autonomous session
is active. It is based on sent commands, returned target validity, and local
override handling. It is not the Control Hub's drivetrain mode.

### Android Driver Station Telemetry

This is the authority for:

- `TELEOP`, `AUTO_NAVIGATING`, or `GOONING`
- Readiness and refusal reason
- I2C packet status
- GPS and sequence age as seen by the Control Hub
- Target sequence
- Distance, bearing, heading error, and motor request
- Arrival confirmation progress

## Desktop Tele-Op Override Behavior

The dashboard exits its Fable autonomous session when Fable is selected and it
transmits one of these meaningful exit inputs:

- Absolute left stick Y greater than `180` transport units, equivalent to 0.18
- Absolute right stick X greater than `180`
- Circle/B
- Square/X

On detection it switches the UI out of its autonomous-target state, marks a
clear pending, and sends `CLEAR` through the driver C3. It retries while the
navigation serial link is available until telemetry reports the target invalid.
For stick or Circle input, the Control Hub returns to tele-op. Square is a
special case: it exits point-to-point navigation but starts the Control Hub's
gooning mode.

Cross/A is excluded because it arms navigation. Bumpers and triggers are
excluded because they remain mechanism controls during autonomous driving.

The Control Hub independently implements its own manual override and stop state
machine. The desktop behavior supplements that logic and keeps the UI/target
state from remaining stale.

## Failure Interpretation

| Observation | Proven working | Still unproven or likely fault area |
| --- | --- | --- |
| Controller moves in dashboard | DS4, Pygame, desktop normalization | Uno, LoRa, Feather, Android, Control Hub |
| Selected robot log advances | Desktop frame construction and loop | Actual serial/radio reception unless Serial is healthy |
| Phone sees Feather gamepad | Feather USB HID and OTG | Correct FTC mapping or robot OpMode behavior |
| Fable current dot updates | GPS through both ESP-NOW directions into Flask | Pico/I2C/Control Hub navigation readiness |
| ESP-NOW Link says heartbeat alive | Robot recently heard driver command/heartbeat and returned telemetry | Control Hub mode and drivetrain |
| Control Hub target is valid | ESP-NOW, C3 UART, Pico, I2C, and decode | Cross arming, readiness, drive signs, mechanical motion |
| Dashboard badge says autonomous | Desktop target state | Actual `AUTO_NAVIGATING` mode |

## Stop And Failure Conditions

| Condition | Current Control Hub result |
| --- | --- |
| One or more ESP-NOW packets lost after target storage | Continue if GPS and I2C data remain ready |
| Driver heartbeat expires | Continue; link flag is informational |
| LoRa HID briefly stops | Continue autonomous navigation; Feather HID becomes neutral |
| GPS fix invalid or stale | Stop navigation and return to tele-op |
| Navigation sequence stops advancing | Stop navigation and return to tele-op |
| Pico/I2C packet invalid | Stop navigation and return to tele-op |
| Target cleared | Stop navigation and return to tele-op |
| Target sequence changes | Stop and require Cross to re-arm |
| Circle or manual drive input | Stop autonomous ownership and return to tele-op |
| Arrival confirmed | Enter gooning |
| Non-Square input during gooning | Stop gooning and return to tele-op |

## Safety Notes

- Navigation has no obstacle avoidance.
- Field calibration is not a motion boundary.
- Satellite imagery can be old or spatially offset and must not be treated as a
  precise obstacle map.
- A stopped browser or desktop process is not an autonomous emergency stop.
- Keep a driver ready to override and an operator able to stop the FTC OpMode.
- Verify heading sign with wheels raised before the first ground test after any
  IMU, drivetrain, or navigation-controller change.
- Use outdoor low-speed tests before increasing range or power.

## Current Limitations

- Only Fable has this navigation side channel.
- The dashboard does not receive authoritative Control Hub mode feedback.
- ESP-NOW uses unencrypted broadcast packets on fixed channel 1.
- Target transmission is not application-level acknowledged; confirmation is
  inferred from returned target state.
- The controller follows a direct GPS arc and has no obstacle planner.
- GPS alone cannot guarantee centimeter-level arrival accuracy.
