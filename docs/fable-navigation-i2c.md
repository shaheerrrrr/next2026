# Fable Navigation I2C Interface

This document defines the first robot-side data layer for Fable's point-to-point navigation. The Control Hub explicitly polls a robot-side ESP32-C3 for GPS and target data, reads heading from its own integrated IMU, and calculates target distance, bearing, and heading error.

This phase never commands the drivetrain. The working tele-op OpMode remains unchanged.

## Data Ownership

- The robot ESP32-C3 owns GPS parsing, the latest target received over ESP-NOW, and link-health timestamps.
- The Control Hub is the I2C controller and explicitly requests one fixed-size snapshot.
- The ESP32-C3 is the I2C peripheral at 7-bit address `0x42`.
- The Control Hub owns IMU heading. Heading is not sent over I2C in protocol version 1.
- `NavigationSubsystem.poll()` is the only operation that performs an I2C read. Future combined robot code should call it only while point-to-point mode is active.

## Wiring

Use a REV JST-PH 4-pin sensor cable connected to I2C Bus 1, 2, or 3. Avoid Bus 0 for this first integration because the Control Hub's internal IMU is already on Bus 0.

| REV I2C cable | Function | ESP32-C3 |
| --- | --- | --- |
| Black | Ground | GND |
| White | SDA | A free GPIO configured as I2C SDA |
| Blue | SCL | A free GPIO configured as I2C SCL |
| Red | 3.3 V | Leave disconnected when powering the ESP separately |

The working GPS sketch already uses GPIO 4 for GPS RX and GPIO 5 for GPS TX. Do not reuse those pins for I2C. Select two other GPIOs supported by the exact ESP32-C3 board.

Power the ESP32-C3 through its `5V`, `VIN`, or USB power input from a regulated 5 V source such as a Control Hub auxiliary 5 V output. Confirm the board's exact input pin before connecting it. Do not put 5 V on SDA, SCL, or a 3.3 V pin. The Control Hub and ESP must share ground.

REV Hub I2C uses 3.3 V signaling. The I2C cable color convention is black ground, red power, blue SCL, and white SDA. See the [REV I2C documentation](https://docs.revrobotics.com/duo-control/sensors/i2c).

## Robot Configuration

1. Build and install TeamCode once so the custom device type is available.
2. Open the active Robot Configuration from the Driver Station.
3. Select the chosen Control Hub I2C bus.
4. Add `Fable ESP Navigation` and name it exactly `FableNav`.
5. Confirm the integrated IMU is configured as `imu`.
6. Save and activate the configuration.

## Protocol Version 1

The Control Hub reads 56 bytes beginning at register `0x00`. All multi-byte values are little-endian. Coordinates are signed degrees multiplied by `10,000,000` so the wire representation is deterministic across C++ and Java.

| Offset | Size | Type | Field |
| ---: | ---: | --- | --- |
| 0 | 4 | bytes | ASCII magic `FNAV` |
| 4 | 1 | uint8 | Protocol version, currently `1` |
| 5 | 1 | uint8 | Packet length, currently `56` |
| 6 | 1 | uint8 | Validity/status flags |
| 7 | 1 | uint8 | Fix quality: 0 none, 1 weak, 2 good |
| 8 | 4 | uint32 | Navigation/GPS sequence |
| 12 | 4 | uint32 | Target sequence |
| 16 | 4 | int32 | Current latitude degrees x 1e7 |
| 20 | 4 | int32 | Current longitude degrees x 1e7 |
| 24 | 4 | int32 | Target latitude degrees x 1e7 |
| 28 | 4 | int32 | Target longitude degrees x 1e7 |
| 32 | 4 | uint32 | GPS location age in milliseconds |
| 36 | 4 | uint32 | Age of last valid driver ESP-NOW command in milliseconds |
| 40 | 2 | uint16 | HDOP x 100 |
| 42 | 1 | uint8 | Satellite count, clamped to 255 |
| 43 | 1 | uint8 | Reserved, write zero |
| 44 | 4 | uint32 | GPS characters processed |
| 48 | 4 | uint32 | ESP uptime in milliseconds |
| 52 | 2 | uint16 | Reserved, write zero |
| 54 | 2 | uint16 | CRC-16/CCITT-FALSE over bytes 0 through 53 |

Flag byte at offset 6:

| Bit | Hex | Meaning |
| ---: | --- | --- |
| 0 | `0x01` | Current GPS location is valid |
| 1 | `0x02` | Target coordinate is valid |
| 2 | `0x04` | Satellite count is valid |
| 3 | `0x08` | HDOP is valid |
| 4 | `0x10` | Driver ESP-NOW link is alive |

CRC parameters are polynomial `0x1021`, initial value `0xFFFF`, no reflection, and no final XOR. The ESP must build the next packet in a separate buffer and atomically publish the complete buffer so an I2C read cannot observe half-updated coordinates.

## IMU Heading Frame

GPS bearing uses compass convention: north is 0 degrees and east is 90 degrees. FTC IMU yaw is counterclockwise-positive, so robot compass heading is calculated as normalized `-yaw`.

Before testing navigation geometry:

1. Point Fable's forward direction toward the field direction defined as north.
2. Press Y/Triangle in the test OpMode to reset IMU yaw.
3. Confirm compass heading is near 0 degrees.
4. Turn Fable clockwise/right and confirm compass heading increases toward 90 degrees.

`NavigationSubsystem` currently assumes the Control Hub logo faces up and its USB port faces forward. Change `HUB_LOGO_FACING` and `HUB_USB_FACING` if Fable's physical installation differs.

## Test OpMode

Run `Fable: Navigation Data Test` from the `Test` group. It polls at approximately 10 Hz and displays packet validation, GPS quality, link age, coordinates, sequences, IMU heading, target distance, target bearing, and heading error.

The OpMode reports ready only when the packet and checksum are valid, the GPS fix is good and recent, a target exists, the driver ESP-NOW link is recent, and the GPS sequence continues to advance. It does not initialize the drivetrain or move any motor.

Until the ESP I2C firmware is implemented, `BAD_MAGIC` or an I2C error is expected.
