# Fable Intake Bottom Limit Switches

Fable uses two REV Touch Sensors as lower limit switches for the rack-and-pinion intake lift. Each continuous-rotation lift servo stops independently when its side reaches the bottom. This removes the previous timed downward overdrive that could make the servo horns cam out.

The top endpoint remains timed. Reaching a bottom switch establishes a known full-travel position for that side, so upward timing always begins from a repeatable reference during a normal full deployment.

## Hardware Configuration

The code expects these exact, case-sensitive Robot Configuration names:

| Device | Configuration type | Recommended digital channel | Name |
| --- | --- | ---: | --- |
| Left bottom switch | REV Touch Sensor | 1 | `IntakeBottomLeft` |
| Right bottom switch | REV Touch Sensor | 3 | `IntakeBottomRight` |

REV Touch Sensors use the white `n+1` signal wire and only work on odd-numbered digital channels `1`, `3`, `5`, or `7`.

## Wiring

Use the standard REV JST-PH 4-pin sensor cable supplied with each Touch Sensor.

1. Connect the left sensor to the Control Hub physical digital connector labeled `0-1`.
2. Configure that sensor as `REV Touch Sensor` on digital port `1`.
3. Connect the right sensor to the physical connector labeled `2-3`.
4. Configure that sensor as `REV Touch Sensor` on digital port `3`.
5. Name the sensors exactly `IntakeBottomLeft` and `IntakeBottomRight`.
6. Save and activate the Robot Configuration.

Each cable carries:

| Wire | Purpose |
| --- | --- |
| Black | Ground |
| Red | 3.3 V power |
| White | Touch Sensor signal (`n+1`, odd channel) |
| Blue | Unused by the REV Touch Sensor |

Do not configure either sensor on an even-numbered channel. Using separate physical connectors is simplest because both sensors require the white/odd signal channel.

## Mechanical Placement

Mount one switch under each rack side:

- The moving mechanism should press the switch just before reaching its destructive lower hard stop.
- The switch must be reliably contacted despite rack play or twisting.
- Do not use the switch itself as the structural hard stop.
- Leave a small amount of mechanical travel between switch activation and any collision.
- Confirm cables cannot be pinched by the rack, gears, or intake.

The REV Touch Sensor button is off-center. Align the actual button, not merely the center of the sensor housing, with the contact point.

## Driver Behavior

The existing bumper controls are unchanged.

While either intake command is held:

1. The intake motor runs in the requested direction.
2. Both lift servos move downward.
3. When the left switch is pressed, the left lift servo stops.
4. When the right switch is pressed, the right lift servo stops.
5. Once both switches are pressed, the intake motor continues while the lift stays down.

When the command is released:

1. The intake motor stops.
2. Each lift servo moves upward for its estimated remaining travel.
3. A side that reached its bottom switch receives the full calibrated retract time.
4. Each side stops independently when its upward timer reaches zero.

The OpMode assumes the intake starts physically all the way up. A successful full deployment re-homes each side at the bottom.

## Tuning

The primary endpoint tuning value is:

```java
public static final double RETRACT_SECONDS = 1.85;
```

It is located in:

```text
TeamCode/src/main/java/org/firstinspires/ftc/teamcode/subsystems/Intake.java
```

Tune it as follows:

1. Raise the robot so the intake can move safely.
2. Start with the existing `1.85` seconds.
3. Hold an intake bumper until both bottom switches activate.
4. Release the bumper.
5. Increase `RETRACT_SECONDS` only if the mechanism consistently stops below the required top position.
6. Decrease it if either side continues driving after reaching the top.
7. Repeat with a normal robot battery at several charge levels.

Do not add an extra overdrive duration. The value should be just long enough to reach the usable top position consistently.

## Safety Behavior

The lift stops and reports a fault if:

- Both bottom switches are not reached within 3 seconds.
- One switch activates and the other does not activate within 0.75 seconds.

During a deployment fault, the intake motor and both lift servos stop. Releasing the bumper commands a timed retraction based on each side's estimated travel. Starting another deployment clears the previous fault message.

`Fable` telemetry reports:

- Intake state
- Raw left and right switch state
- Estimated left and right lift position
- Any deployment fault

Possible states are:

| State | Meaning |
| --- | --- |
| `RETRACTED` | Lift is assumed at the timed top position |
| `DEPLOYING` | One or both sides are moving down |
| `INTAKING` | Both bottom switches are pressed and lift motion is stopped |
| `RETRACTING` | One or both sides are moving toward the timed top |
| `DEPLOY_FAULT` | Deployment safety timeout stopped the mechanism |

## Initial Test

Test with the robot supported and hands clear:

1. Select `Fable`, but leave it in INIT.
2. Before moving anything, press each switch by hand and verify the matching `Intake lift` telemetry value changes to `true`.
3. Confirm pressing the left physical switch changes only `left`.
4. Confirm pressing the right physical switch changes only `right`.
5. Press START only after both sensor readings are correct. Normal drivetrain, autonomous, intake, and candy-cane controls remain available.
6. Briefly command intake deployment and verify both racks move downward.
7. Press one switch by hand. Only its matching servo should stop.
8. Press the second switch. Both lift servos should be stopped while the intake motor continues.
9. Release the bumper and verify both sides retract upward.
10. Tune `RETRACT_SECONDS` before allowing the mechanism to reach the upper hard stop at full power.

If the switches are swapped in telemetry, swap their configuration names or physical cables. If a side moves upward during deployment, correct that lift servo's configured/code direction before testing the switches.

## Upper Endpoint Limitations

With no upper switches, the Control Hub cannot directly detect the top. Upward travel remains an estimate affected by CR-servo speed, battery voltage, friction, and load.

The bottom switches make this substantially more repeatable because a full deployment provides a known starting point. If timed retraction still cams out at the top across battery conditions, the correct final solution is adding upper limit switches or replacing the CR-servo lift with position-feedback actuators.

## References

- [REV digital sensor wiring and configuration](https://docs.revrobotics.com/duo-control/sensors/digital)
- [REV Touch Sensor application guidance](https://docs.revrobotics.com/rev-crossover-products/sensors/touch-sensor/application-examples)
- [REV Touch Sensor specifications and pinout](https://docs.revrobotics.com/rev-crossover-products/sensors/touch-sensor/specs)
