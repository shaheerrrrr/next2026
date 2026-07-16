# Fable Point-to-Point Autonomous Mode

This guide describes the drivetrain implementation that switches between normal LoRa HID TeleOp and GPS/IMU point-to-point navigation inside the `Fable` OpMode.

Selecting a target never starts the motors. The driver explicitly enables autonomous navigation with the gamepad and can immediately take control back over the existing LoRa HID path.

Obstacle avoidance is not part of this version.

## System Behavior

The `Fable` OpMode always starts in `TELEOP`. It does not switch to a separate FTC autonomous OpMode.

```text
TELEOP
  | Cross/A with valid navigation data
  v
AUTO_NAVIGATING
  |-- arrival confirmed ----------------> AUTO_ARRIVED
  |-- Circle/B or drive-stick movement -> TELEOP
  |-- invalid/stale GPS or target ------> TELEOP
  |-- target changes -------------------> TELEOP (re-arm required)

AUTO_ARRIVED
  |-- drive-stick movement or Circle/B -> TELEOP
  |-- target changes or clears ---------> TELEOP
```

Intake and candy-cane controls remain active in every drivetrain mode.

## Driver Controls

| Control | Behavior |
| --- | --- |
| Triangle/Y during INIT | Zero IMU yaw while Fable points toward field north |
| Cross/A rising edge | Enter autonomous navigation if all required data is ready |
| Circle/B | Cancel autonomous navigation and stop the drivetrain |
| Left drive stick or right turn stick | Immediate manual override above a 0.18 deadband |
| Flask target Clear | Invalidates the target and stops autonomous navigation |

Cross/A latches autonomous mode; it does not need to remain held. A transient loss of LoRa HID therefore does not cancel autonomous motion. Once LoRa returns, Circle/B or either drive stick can override it.

## Driver Procedure

1. Power the Control Hub, Pico, robot ESP32-C3, GPS, phone, and existing LoRa HID receiver.
2. Place Fable outdoors with a clear view of the sky.
3. Select and initialize `Fable`, but do not press START yet.
4. Point Fable's forward direction toward the field direction defined as north.
5. Press Triangle/Y once. Confirm the telemetry log says `IMU heading zeroed to field north` and heading is close to 0 degrees.
6. Confirm GPS fix is `GOOD`, the packet is `OK`, and the navigation sequence advances.
7. Press START. Fable remains in `TELEOP`.
8. In the Flask map, select and send a target. This loads coordinates but does not move Fable.
9. Confirm Driver Station telemetry shows the expected target and a plausible distance and bearing.
10. Press Cross/A once to begin navigation.
11. Keep the LoRa controls available. Move a drive stick or press Circle/B to take control back immediately.
12. When Fable reaches the arrival region, it stops in `AUTO_ARRIVED`. Move a drive stick or press Circle/B to return to normal TeleOp.

The Flask dashboard currently uses the label `autonomous` when a target is loaded. That is not authoritative robot mode feedback. Driver Station telemetry is the source of truth for `TELEOP`, `AUTO_NAVIGATING`, and `AUTO_ARRIVED`.

## Required Data

Autonomous entry and continued movement require:

- A valid `FNAV` packet and checksum
- A `GOOD` GPS fix
- GPS age no greater than 1.5 seconds, including time since the Hub read it
- A valid target coordinate
- A navigation sequence that continues advancing, with a 1.5-second stale timeout

ESP-NOW link-alive status and command age are informational only. Once a target has been received and remains valid, intermittent or extended ESP-NOW packet loss does not stop autonomous navigation.

A new target changes `targetSequence`. For this first version, Fable stops and requires another Cross/A press instead of redirecting while already moving.

## Update Rates

| Operation | Rate |
| --- | --- |
| GPS/C3 snapshot production | 10 Hz |
| Pico polling during `AUTO_NAVIGATING` | 5 Hz |
| Pico polling in `TELEOP` or `AUTO_ARRIVED` | 1 Hz |
| IMU heading and motor command calculation | Every OpMode loop |
| Driver Station telemetry transmission | Up to 10 Hz |

Reading the Pico does not cause the GPS module to produce a fix. It retrieves the newest snapshot already supplied by the C3.

## Navigation Algorithm

`NavigationSubsystem` calculates:

```text
distance = geographic distance(current position, target position)
bearing = compass bearing(current position, target position)
heading error = normalized(bearing - IMU compass heading)
```

Positive heading error means the target is clockwise/right of Fable's current heading. `PointToPointController` produces conceptual positive-forward and positive-right commands. `FableTeleOp` applies the deployed drivetrain sign conventions at the drivetrain boundary.

The first controller behaves as follows:

- Always move forward while steering; navigation never intentionally turns in place.
- Apply proportional steering with `HEADING_KP = 0.020` power per degree.
- Limit turn power to 0.70 and at most 78% of current forward power.
- Use at least 0.15 turn power outside the 2-degree heading deadband.
- Drive forward at up to 0.90 power.
- Slow from 0.90 toward 0.50 power inside 35 meters.
- Keep both drivetrain sides moving in the same forward direction throughout a turn.
- Never deliberately reverse.
- Stop immediately upon entering the 10-meter arrival radius.
- Declare arrival after three distinct GPS sequence updates remain inside that radius.

All speed and steering values are centralized in `PointToPointController` for field tuning.

## Telemetry

The live Driver Station display includes:

- Current drivetrain mode and mode detail
- Navigation readiness and refusal/stop reason
- GPS fix quality, validity, age, satellites, and HDOP
- Current and target coordinates
- Distance, bearing, compass heading, and heading error
- Generated drive and turn commands
- Arrival confirmation count
- Packet status, navigation sequence, and sequence age
- ESP-NOW link state and command age, explicitly labeled informational

Mode transitions and important events are also added to the scrolling telemetry log. Examples include heading zeroing, rejected autonomous entry, driver cancellation, manual override, stale navigation data, target changes, and arrival.

## Developer Structure

| File | Responsibility |
| --- | --- |
| `FableTeleOp.java` | Mode ownership, controls, polling schedule, motor safety, and telemetry |
| `NavigationSubsystem.java` | Cached Pico reads, IMU heading, geographic geometry, and readiness |
| `PointToPointController.java` | Pure distance/heading-to-drive calculation and arrival confirmation |
| `FableNavigationI2cDevice.java` | Four-chunk `FNAV` register transport and packet validation |
| `GeoNavigation.java` | Distance, bearing, and angle normalization |

Keep target transport, navigation geometry, controller policy, and drivetrain ownership in these separate layers. Future obstacle avoidance should modify or veto the controller's desired command before it reaches `Drivetrain`; it should not replace the mode and safety state machine.

## Test Procedure

### Wheels Raised

1. Send a target with a bearing clearly to Fable's right.
2. Enter autonomous mode and verify Fable moves from its defined front and positive heading error produces the same physical turn direction as pushing the manual turn stick right.
3. Repeat with a target to the left.
4. Rotate Fable by hand and confirm turn power decreases as heading approaches target bearing.
5. Move a drive stick and confirm the mode immediately becomes `TELEOP`.
6. Press Circle/B and confirm both drivetrain motors stop.

If positive heading error turns Fable left, stop testing and invert the autonomous turn command before any ground test.

### Outdoor Low-Speed Test

1. Use a target substantially farther away than the 10-meter arrival radius.
2. Keep a clear test area and a driver ready to override.
3. Confirm Fable drives forward immediately and bends toward the target in a continuous arc.
4. Watch GPS age, navigation sequence age, heading error, and generated motor commands.
5. Test manual override, Flask Clear, GPS disconnection, and a changed target before increasing power.
6. Approach the target and confirm the drivetrain stops while collecting three arrival confirmations.

### Expected Stop Conditions

| Condition | Expected result |
| --- | --- |
| One or more ESP-NOW packets lost | Continue toward the stored target |
| ESP-NOW link-alive flag expires | Continue; telemetry reports the link as unavailable |
| LoRa packet temporarily lost | Continue autonomous navigation; override resumes when HID returns |
| GPS fix invalid or stale | Stop and return to `TELEOP` |
| Pico/I2C packet invalid | Stop and return to `TELEOP` |
| Target cleared | Stop and return to `TELEOP` |
| Target changed | Stop and require Cross/A to re-arm |
| Circle/B or manual stick input | Stop autonomous ownership and return to driver control |

## Tuning Order

Change one category at a time:

1. Verify heading sign and north calibration.
2. Tune `MIN_TURN_POWER` so Fable can reliably begin rotating.
3. Tune `HEADING_KP`, `MAX_TURN_POWER`, and `MAX_TURN_TO_DRIVE_RATIO` to control arc sharpness without reversing the inside wheel.
4. Tune drive powers and slowdown radius.
5. Reduce the arrival radius only after recording real GPS scatter at rest and while moving.

Do not reduce the 10-meter radius merely because one test stops accurately. Measure repeated fixes and choose a radius larger than the observed GPS uncertainty plus stopping distance.
