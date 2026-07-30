# Fable Point-to-Point Autonomous Mode

This guide describes the drivetrain implementation that switches between normal LoRa HID TeleOp and GPS/IMU point-to-point navigation inside the `Fable` OpMode.

Selecting a target never starts the motors. The driver explicitly enables autonomous navigation with the gamepad and can immediately take control back over the existing LoRa HID path.

`ObstacleAvoidanceController` can override the drive command produced below during `AUTO_NAVIGATING` and `GOONING`; see the Obstacle Avoidance section for the state machine, sign-convention reasoning, and tuning order. It never activates during plain `TELEOP` manual driving, so driver stick input is never overridden.

## System Behavior

The `Fable` OpMode always starts in `TELEOP`. It does not switch to a separate FTC autonomous OpMode.

```text
TELEOP
  | Cross/A with valid navigation data
  v
AUTO_NAVIGATING
  |-- arrival confirmed ----------------> GOONING
  |-- Circle/B or drive-stick movement -> TELEOP
  |-- invalid/stale GPS or target ------> TELEOP
  |-- target changes -------------------> TELEOP (re-arm required)
  |-- obstacle avoidance safety timeout -> TELEOP

TELEOP or AUTO_NAVIGATING
  |-- Square/X --------------------------> GOONING

GOONING
  |-- any non-Square driver input ------> TELEOP
```

Intake and candy-cane controls remain active in every drivetrain mode.

## Driver Controls

| Control | Behavior |
| --- | --- |
| Triangle/Y during INIT | Zero IMU yaw while Fable points toward field north |
| Cross/A rising edge | Enter autonomous navigation if all required data is ready |
| Square/X rising edge | Start gooning, beginning in the forward direction |
| Circle/B | Cancel autonomous navigation and stop the drivetrain |
| Left drive stick or right turn stick | Immediate manual override above a 0.18 deadband |
| Any non-Square input while gooning | Stop gooning and return to `TELEOP` |
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
12. When Fable reaches the arrival region, it immediately begins gooning. Use any other gamepad input to return to normal TeleOp.

The Flask dashboard currently uses the label `autonomous` when a target is loaded. That is not authoritative robot mode feedback. Driver Station telemetry is the source of truth for `TELEOP`, `AUTO_NAVIGATING`, and `GOONING`.

## Gooning Mode

Gooning continuously alternates straight forward and backward movement. It starts when Square/X is pressed or immediately after point-to-point arrival is confirmed. It does not require GPS, I2C, ESP-NOW, or a current target once active.

The tunable values are near the top of `FableTeleOp.java`:

| Constant | Default | Purpose |
| --- | --- | --- |
| `GOON_DIRECTION_SECONDS` | 1.25 seconds | Time spent moving in each direction before reversing |
| `GOON_DRIVE_POWER` | 0.75 | Straight drivetrain command magnitude |
| `GOON_INPUT_DEADBAND` | 0.15 | Analog input threshold used to interrupt the mode |

Every transition into gooning begins by moving forward. After `GOON_DIRECTION_SECONDS`, Fable reverses; it then changes direction at the same interval until interrupted. Square/X itself is excluded from the interruption check. Pressing it again restarts the sequence in the forward direction.

The interruption check includes Cross/A, Circle/B, Triangle/Y, the D-pad, Start/Back, either bumper, either trigger, and movement on any stick axis. Mechanism inputs therefore stop gooning and still reach their normal mechanism handlers in that loop.

`ObstacleAvoidanceController` is also active while gooning: it can override the alternating forward/backward drive command with a veer or reverse arc using the same forward ultrasonic reading it uses during `AUTO_NAVIGATING`. See the Obstacle Avoidance section below.

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
| Pico polling in `TELEOP` or `GOONING` | 1 Hz |
| IMU heading and motor command calculation | Every OpMode loop |
| Driver Station telemetry transmission | Up to 10 Hz |
| `FableSonar` ultrasonic polling (`UltrasonicSubsystem.pollDistance()`) | Every OpMode loop, not rate-limited, in every drive mode |
| `ObstacleAvoidanceController.update()` | Every OpMode loop while `AUTO_NAVIGATING` or `GOONING` |

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
- Declare arrival after three distinct GPS sequence updates remain inside that radius.
- Transfer directly into gooning after arrival is confirmed.

All speed and steering values are centralized in `PointToPointController` for field tuning.

## Obstacle Avoidance

`ObstacleAvoidanceController` (`org.firstinspires.ftc.teamcode.navigation.ObstacleAvoidanceController`) is a pure-logic, Roomba-style controller that consumes the forward-facing HC-SR04 reading (`UltrasonicReading`, described in `docs/fable-navigation-i2c.md`) and can take over the drive command ahead of `PointToPointController` when something is close: it veers right until clear, then hands back to GPS navigation. It is active during `AUTO_NAVIGATING` and `GOONING`. It is never active during plain `TELEOP` manual driving, so driver stick input is never overridden.

### State Machine

```text
INACTIVE ──[d < CLOSE_RANGE_METERS]────────> REVERSING_LEFT
INACTIVE ──[d < TRIGGER_DISTANCE_METERS]───> VEERING_RIGHT

VEERING_RIGHT ──[d >= CLEAR_DISTANCE_METERS]──> INACTIVE  (hand back to GPS navigation)
VEERING_RIGHT ──[d < CLOSE_RANGE_METERS]──────> REVERSING_LEFT
VEERING_RIGHT ──[sensor read fails / no reading]──> INACTIVE  (fails open)

REVERSING_LEFT ──[d >= CLOSE_RANGE_CLEAR_METERS]──> VEERING_RIGHT
REVERSING_LEFT ──[sensor read fails / no reading]──> INACTIVE  (fails open)

Any active state, continuous duration > MAX_AVOIDANCE_MS ──> INACTIVE, timedOut=true
```

A sensor read failure or a valid-but-empty reading (see `UltrasonicReading.hasDistance()` vs. `packetValid()` in `docs/fable-navigation-i2c.md`) always fails the controller *open*: it drops straight to `INACTIVE` and lets `PointToPointController` drive again rather than freezing the robot on a flaky bus. A flaky `FableSonar` can therefore degrade Fable back to GPS-only navigation with no obstacle protection, but it can never leave Fable stuck fighting a phantom obstacle.

`CLOSE_RANGE_METERS` exists because a forward-right arc needs room to swing; once an obstacle is inside that radius there isn't space left to clear it by veering, so the controller reverses instead. `CLEAR_DISTANCE_METERS` and `CLOSE_RANGE_CLEAR_METERS` are separate, farther-out exit thresholds from `TRIGGER_DISTANCE_METERS` and `CLOSE_RANGE_METERS` respectively — that hysteresis gap is what stops the controller from sawing in and out of avoidance at the boundary of a single obstacle.

If an obstacle boxes Fable in long enough that neither arc clears it, `MAX_AVOIDANCE_MS` bails the whole thing out: the controller reports `timedOut=true` and returns `INACTIVE`, and `FableTeleOp` treats that the same as any other autonomous stop condition — it exits `AUTO_NAVIGATING` back to `TELEOP` so the driver retakes control. Setting `MAX_AVOIDANCE_MS` to `0` disables the timeout.

| Constant | Default | Purpose |
| --- | --- | --- |
| `TRIGGER_DISTANCE_METERS` | 1.25 m | Enter avoidance from `INACTIVE` |
| `CLEAR_DISTANCE_METERS` | 1.50 m | Must clear past here, not just back past the trigger, to leave `VEERING_RIGHT` |
| `CLOSE_RANGE_METERS` | 0.45 m | Below this a forward arc can't clear the obstacle; reverse instead |
| `CLOSE_RANGE_CLEAR_METERS` | 0.60 m | Separate exit threshold for the close-range sub-state |
| `VEER_DRIVE_POWER` / `VEER_TURN_POWER` | 0.55 / 0.42 | Forward-right arc; ratio ~0.76 |
| `REVERSE_DRIVE_POWER` / `REVERSE_TURN_POWER` | 0.50 / 0.35 | Reverse-left arc; ratio 0.70 |
| `REVERSE_TURN_SIGN` | 1.0 | Flip this single constant if a wheels-raised test shows the reverse arc spinning backwards |
| `MAX_AVOIDANCE_MS` | 6,000 ms | Safety timeout; `0` disables it |

### Sign Convention: Why the Reverse Arc Still Swings Right

Fable's drivetrain cannot turn in place (`Drivetrain.drive()`: `left = drive - turn`, `right = drive + turn`). Whenever `|turn|` approaches or exceeds `|drive|`, one wheel reverses relative to the other — a real mechanical concern on this two-motor differential drive, not a style nicety. Both `PointToPointController` and `ObstacleAvoidanceController` only ever describe forward or reverse *arcs*, and both keep `|turn| < |drive| * 0.78` (`PointToPointController.MAX_TURN_TO_DRIVE_RATIO`; the veer and reverse ratios above are 0.76 and 0.70) so neither wheel ever reverses relative to the other.

`ObstacleAvoidanceController` produces `drive`/`turn` in the same conceptual frame as `PointToPointController.Output`: positive `drive` means forward, positive `turn` means the nose swings right. `FableTeleOp` applies `AUTO_DRIVE_SIGN = -1.0` and `AUTO_TURN_SIGN = 1.0` at the drivetrain boundary before calling `Drivetrain.drive()` — the same conversion already applied to `PointToPointController`'s output. `AUTO_DRIVE_SIGN` is `-1.0` because the deployed drivetrain treats a negative raw command as physically forward, matching the native gamepad convention where pushing the left stick forward already reports a negative `left_stick_y`.

`VEERING_RIGHT` is the intuitive case: conceptually `drive=+0.55, turn=+0.42`. Push that through the sign flip and `Drivetrain`'s mixer and the left wheel ends up spinning forward faster than the right, so Fable arcs forward and to the right — the same physical turn direction as pushing the manual turn stick right while driving forward.

`REVERSING_LEFT` is the subtler case, and it exists precisely because a forward-right arc cannot clear an obstacle already inside `CLOSE_RANGE_METERS`: there isn't room to swing around it, so Fable backs away first. Conceptually `drive=-0.50, turn=+0.35 x REVERSE_TURN_SIGN`. Run the same conversion: the negative conceptual drive becomes a *positive* raw command (physically backward), and the mixer still adds `turn` to the right side and subtracts it from the left. The right wheel's raw command ends up more positive — more physically backward — than the left wheel's, so the right side reverses harder than the left. With the right side retreating faster, the back of the robot swings left, and because the nose and the back of a rigid body swing opposite ways, the nose swings right — the same direction `VEERING_RIGHT` will continue in once there's room. It is the reverse half of a three-point turn: the wheel bias that arcs the nose right going forward arcs it right again in reverse, because the drive sign and the effective wheel bias flip together. `REVERSE_TURN_SIGN` is a single constant to flip if a wheels-raised test shows this arc spinning the wrong way; a wrong-way arc is a sign-flip fix, not a reason to restructure the state machine.

### Telemetry

Two new `showTelemetry()` lines report the controller and the sensor it depends on:

```java
telemetry.addData("Avoidance", "%s drive=%.2f turn=%.2f (%s)", state, drive, turn, phase);
telemetry.addData("Sonar health", "faults=%d status=%s trigger=%.0fcm clear=%.0fcm", consecutiveFaults, ultrasonicStatus, triggerCm, clearCm);
```

`Avoidance` reports the current `State` (`INACTIVE`, `VEERING_RIGHT`, or `REVERSING_LEFT`), the conceptual drive/turn command the controller wants applied, and a short phase string, mirroring how `PointToPointController.Output.phase` already reads on the `Auto command` line. `Sonar health` reports `consecutiveFaults` (a running count of back-to-back failed or empty reads, for spotting a degrading sensor before it starts affecting navigation), the raw `UltrasonicReading.status`, and the configured `TRIGGER_DISTANCE_METERS`/`CLEAR_DISTANCE_METERS` thresholds in centimeters, so the trigger/clear gap is visible on the Driver Station without cross-referencing source.

## Telemetry

The live Driver Station display includes:

- Current drivetrain mode and mode detail
- Navigation readiness and refusal/stop reason
- GPS fix quality, validity, age, satellites, and HDOP
- Current and target coordinates
- Distance, bearing, compass heading, and heading error
- Generated drive and turn commands
- Arrival confirmation count
- Gooning mode, current direction, and direction duration
- Packet status, navigation sequence, and sequence age
- ESP-NOW link state and command age, explicitly labeled informational
- Obstacle avoidance state, override drive/turn command, and phase (see Obstacle Avoidance)
- Ultrasonic sensor health: consecutive faults and the configured trigger/clear thresholds

Mode transitions and important events are also added to the scrolling telemetry log. Examples include heading zeroing, rejected autonomous entry, driver cancellation, manual override, stale navigation data, target changes, and arrival.

## Developer Structure

| File | Responsibility |
| --- | --- |
| `FableTeleOp.java` | Mode ownership, controls, polling schedule, motor safety, and telemetry |
| `NavigationSubsystem.java` | Cached Pico reads, IMU heading, geographic geometry, and readiness |
| `PointToPointController.java` | Pure distance/heading-to-drive calculation and arrival confirmation |
| `FableNavigationI2cDevice.java` | Four-chunk `FNAV` register transport and packet validation |
| `GeoNavigation.java` | Distance, bearing, and angle normalization |
| `ObstacleAvoidanceController.java` | Pure ultrasonic-to-drive-override calculation and its own veer/reverse state machine |
| `UltrasonicSubsystem.java` / `UltrasonicI2cDevice.java` | Explicitly-polled `FSON` transport and packet validation for the forward sonar |

Keep target transport, navigation geometry, controller policy, and drivetrain ownership in these separate layers. `ObstacleAvoidanceController` follows this same contract: it modifies or vetoes `PointToPointController`'s desired command before it reaches `Drivetrain`, and it does not replace `FableTeleOp`'s mode and safety state machine — it only reports `timedOut` and lets `FableTeleOp` decide to fall back to `TELEOP`.

## Test Procedure

### Wheels Raised

1. Send a target with a bearing clearly to Fable's right.
2. Enter autonomous mode and verify Fable moves from its defined front and positive heading error produces the same physical turn direction as pushing the manual turn stick right.
3. Repeat with a target to the left.
4. Rotate Fable by hand and confirm turn power decreases as heading approaches target bearing.
5. Move a drive stick and confirm the mode immediately becomes `TELEOP`.
6. Press Circle/B and confirm both drivetrain motors stop.

If positive heading error turns Fable left, stop testing and invert the autonomous turn command before any ground test.

### Obstacle Avoidance Wheels Raised Check

1. With wheels still raised, enter `AUTO_NAVIGATING` (or `GOONING`) and hand-wave an object into the forward ultrasonic beam.
2. Confirm telemetry's `Avoidance` line shows `VEERING_RIGHT` and the wheels spin in the arc-right direction: left wheel faster forward, right wheel slower or reversed, per the sign table above.
3. Bring the object closer than `CLOSE_RANGE_METERS` (45 cm) and confirm `Avoidance` shows `REVERSING_LEFT`, with the nose-swing direction still rightward.
4. Pull the object away and confirm `Avoidance` returns to `INACTIVE` and drive authority returns to GPS navigation.

If either arc spins backwards, flip the single `REVERSE_TURN_SIGN` constant (or double-check `AUTO_TURN_SIGN` in `FableTeleOp`) — do not restructure the state machine to fix a reversed arc.

### Outdoor Low-Speed Test

1. Use a target substantially farther away than the 3-meter arrival radius.
2. Keep a clear test area and a driver ready to override.
3. Confirm Fable drives forward immediately and bends toward the target in a continuous arc.
4. Watch GPS age, navigation sequence age, heading error, and generated motor commands.
5. Test manual override, Flask Clear, GPS disconnection, and a changed target before increasing power.
6. Approach the target and confirm the drivetrain enters `GOONING` after collecting three arrival confirmations.
7. Use a non-Square input and confirm gooning ends immediately.

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
| Arrival confirmed | Leave autonomous navigation and immediately begin gooning |
| Any non-Square input while gooning | Stop gooning and return to `TELEOP` |
| Obstacle avoidance active longer than `MAX_AVOIDANCE_MS` continuously | Avoidance controller reports `timedOut`; stop and return to `TELEOP` |

## Tuning Order

Change one category at a time:

1. Verify heading sign and north calibration.
2. Tune `MIN_TURN_POWER` so Fable can reliably begin rotating.
3. Tune `HEADING_KP`, `MAX_TURN_POWER`, and `MAX_TURN_TO_DRIVE_RATIO` to control arc sharpness without reversing the inside wheel.
4. Tune drive powers and slowdown radius.
5. Reduce the arrival radius only after recording real GPS scatter at rest and while moving.

Do not reduce the 3-meter radius merely because one test stops accurately. Measure repeated fixes and choose a radius larger than the observed GPS uncertainty plus stopping distance.

### Obstacle Avoidance Tuning Order

Change one category at a time, most impactful first:

1. `TRIGGER_DISTANCE_METERS` — how early Fable reacts to an obstacle.
2. `CLEAR_DISTANCE_METERS` — how much hysteresis margin is required before trusting GPS navigation again. Widen this if you observe sawing or chattering in and out of avoidance at an obstacle's edge.
3. `CLOSE_RANGE_METERS` / `CLOSE_RANGE_CLEAR_METERS` — only matters for obstacles that get very close, where a forward arc no longer has room to clear them.
4. `VEER_DRIVE_POWER` / `VEER_TURN_POWER` / `REVERSE_DRIVE_POWER` / `REVERSE_TURN_POWER` — only touch these if the arc radius itself is wrong; they are already capped well under the no-wheel-reversal ratio.
5. `MAX_AVOIDANCE_MS` — only if Fable is getting stuck fighting a corner too long before bailing out to manual control.
