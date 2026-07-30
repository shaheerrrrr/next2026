# Fable Navigation Sidecar

This document defines Fable's GPS and target-coordinate transport. The drivetrain mode, point-to-point controller, and obstacle avoidance built on this data are documented in `docs/fable-autonomous-mode.md`.

The proven LoRa USB HID tele-op path is independent and remains unchanged.

## Architecture

```text
Adafruit Ultimate GPS
  | 9600-baud UART
  v
Robot ESP32-C3
  |-- ESP-NOW <--------------------------> Driver ESP32-C3 / laptop
  |                                         telemetry out, target commands back
  |
  | 115200-baud UART, 56-byte FNAV snapshots
  v
Raspberry Pi Pico WH
  | 100 kHz I2C peripheral, address 0x42
  v
REV Control Hub
  |-- custom FTC hardware driver
  |-- integrated IMU heading
  v
NavigationSubsystem / FableTeleOp
```

### Data ownership

- The robot ESP32-C3 parses GPS data and owns the latest target received over ESP-NOW.
- The ESP32-C3 builds the canonical 56-byte `FNAV` snapshot and sends it to the Pico at 10 Hz.
- The Pico validates magic, version, length, and CRC before publishing a snapshot. A bad or partial UART frame never replaces the last good one.
- The Pico is an I2C peripheral at 7-bit address `0x42`. The Control Hub is the I2C controller.
- The Control Hub owns IMU heading. Heading is not part of protocol version 1.
- `NavigationSubsystem.pollNavigationData()` reads and caches the Pico packet. `snapshot()` combines that packet with a current IMU heading without another I2C transaction.

The Pico serves a valid startup packet with no GPS or target flags before the first C3 packet arrives. Therefore, a powered Pico with no C3 connection should report `I2C: OK` and `GPS location is invalid`, not `BAD_MAGIC` or `BAD_CHECKSUM`.

## Firmware

| Device | Source |
| --- | --- |
| Robot ESP32-C3 | `fable_robot_nav_esp32c3/fable_robot_nav_esp32c3.ino` on branch `ftc-lora` |
| Raspberry Pi Pico WH | `fable_navigation_bridge/` on branch `ftc-lora` |
| Control Hub integration | `TeamCode/src/main/java/org/firstinspires/ftc/teamcode/FableTeleOp.java` |

The driver-side ESP firmware and laptop application do not change for this bridge. Their ESP-NOW packet formats remain the deployed formats used by the robot ESP sketch.

## Wiring

Disconnect robot battery power and USB power before changing signal wiring. All three robot-side boards must share ground.

### GPS to robot ESP32-C3

| Adafruit Ultimate GPS | ESP32-C3 | Notes |
| --- | --- | --- |
| VIN | 3V3 | The breakout accepts 3-5 V. |
| GND | GND | Common signal ground. |
| TX | GPIO 4 | GPS output to ESP RX. |
| RX | GPIO 5 | ESP TX to GPS input. |

The GPS defaults to 9600 baud. TX and RX are crossed. `FIX`, `PPS`, `VBAT`, and `EN` are not required.

### Robot ESP32-C3 to Pico WH

| ESP32-C3 | Pico WH | Pico physical pin | Function |
| --- | --- | ---: | --- |
| GPIO 6 | GP1 | 2 | C3 TX to Pico UART0 RX |
| GPIO 7 | GP0 | 1 | C3 RX from Pico UART0 TX; reserved for future use |
| GND | GND | 3 | Common ground |

The current protocol is one-way from C3 to Pico, but wire both UART directions now. Both boards use 3.3 V logic, so no level shifter is required. Do not connect either UART signal to 5 V.

### Pico WH to REV Control Hub

Use a REV JST-PH 4-pin sensor cable on I2C Bus 1, 2, or 3. The existing configuration uses Bus 1, Port 0.

| REV I2C wire | Pico WH | Pico physical pin | Function |
| --- | --- | ---: | --- |
| Black | GND | 8 | Ground |
| White | GP4 | 6 | I2C0 SDA |
| Blue | GP5 | 7 | I2C0 SCL |
| Red | Not connected | - | Do not use for bridge power |

The REV bus is 3.3 V and already has pull-up resistors. The Pico firmware also enables its weak internal pull-ups as a wiring fail-safe.

### HC-SR04 ultrasonic rangefinder

Fable's HC-SR04 provides short-range (up to about 4 m) forward proximity for front-bumper cutoff and docking confirmation. It is a scalar distance with roughly a 15-degree beam cone and no object classification.

The HC-SR04 is served by the same Pico WH, but on a second, fully independent I2C peripheral bus. The Pico presents `i2c1` at 7-bit address `0x43`, entirely separate from the `i2c0` navigation bridge at `0x42`. The two buses share no wiring and no register map.

| REV I2C wire | Pico WH | Function |
| --- | --- | --- |
| Black | GND | Ground |
| White | I2C1 SDA | I2C1 SDA |
| Blue | I2C1 SCL | I2C1 SCL |
| Red | Not connected | Do not use for bridge power |

Use a second REV JST-PH 4-pin sensor cable on a bus distinct from the one carrying `FableNav`. The deployed configuration uses **I2C Bus 2, Port 0**, leaving the existing Bus 1, Port 0 untouched.

The HC-SR04 trigger and echo pin assignments on the Pico are owned by the Pico firmware and are documented with that firmware. The Control Hub never sees them; it only reads the published `FSON` frame described below.

The HC-SR04 echo pin idles at 5 V and must not be wired directly to a 3.3 V Pico GPIO. Level shifting or a divider on the echo line is a firmware-side wiring requirement.

### Power

For installed robot operation, use one Control Hub `+5V Power` auxiliary output:

| Control Hub auxiliary output | Destination |
| --- | --- |
| +5 V | Pico `VSYS`, physical pin 39 |
| +5 V | ESP32-C3 board `5V` or `VIN` input |
| +5 V | HC-SR04 `VCC` |
| Ground | Pico GND, ESP32-C3 GND, and HC-SR04 GND |

Only use the pin on the exact C3 board documented as a regulated 5 V input. Do not apply 5 V to a C3 `3V3` pin. The GPS can be powered from the C3's 3V3 output as shown above.

The HC-SR04 takes its 5 V from this same auxiliary rail, the one already feeding the Pico `VSYS`. Do not power it from the Pico's `VBUS` pin. `VBUS` is only live while USB is connected, and USB is disconnected during normal deployed operation.

During bench flashing, power each board by USB and leave the Control Hub auxiliary 5 V disconnected. Avoid powering the Pico from USB and external VSYS at the same time during initial bring-up.

## Flashing the Pico WH

The Pico bridge uses the official Raspberry Pi Pico C/C++ SDK, including its interrupt-driven `pico_i2c_slave` implementation.

### Recommended: Raspberry Pi Pico VS Code extension

1. Install Visual Studio Code and the official `Raspberry Pi Pico` extension.
2. On branch `ftc-lora`, import `fable_navigation_bridge` as an existing Pico project.
3. Select board `Pico W` (`pico_w`). Pico W and Pico WH use the same board target.
4. Build the `fable_navigation_bridge` target.
5. Hold the Pico's `BOOTSEL` button while connecting its USB cable.
6. Release `BOOTSEL` after the `RPI-RP2` drive appears.
7. Copy `fable_navigation_bridge.uf2` from the build directory to `RPI-RP2`, or use the extension's Run command.
8. The Pico reboots automatically. Its USB serial output appears at 115200 baud.

Expected USB serial output:

```text
Fable Pico bridge ready: UART0 GP1(RX)/GP0(TX), I2C0 GP4/GP5 @ 0x42
uart_ok=10 uart_bad=0 i2c_transactions=0
```

`uart_ok` should increase by about 10 per second after the robot ESP is connected. `uart_bad` should remain zero. `i2c_transactions` increases when the Control Hub test reads the device.

### Command-line build

After installing the Pico SDK and Arm toolchain:

```bash
export PICO_SDK_PATH=/absolute/path/to/pico-sdk
cmake -S fable_navigation_bridge \
  -B fable_navigation_bridge/build \
  -DPICO_BOARD=pico_w
cmake --build fable_navigation_bridge/build
```

Flash `fable_navigation_bridge/build/fable_navigation_bridge.uf2` with `BOOTSEL` as described above.

## Flashing the Robot ESP32-C3

1. On branch `ftc-lora`, open `fable_robot_nav_esp32c3/fable_robot_nav_esp32c3.ino` in Arduino IDE.
2. Select the exact ESP32-C3 board and port previously used for the working GPS/ESP-NOW sketch.
3. Install `TinyGPSPlus` through Library Manager if it is not already installed.
4. Use the same ESP32 Arduino core version as the deployed working sketch.
5. If the board menu offers `USB CDC On Boot`, enable it so USB logging does not consume UART0. Logging is not required by this sketch.
6. Upload the sketch.

UART0 is now dedicated to GPIO 6/7 for the Pico. GPS remains on UART1 at GPIO 4/5. ESP-NOW channel, command packets, and telemetry packets are unchanged from the supplied working firmware.

## Control Hub Setup

The bridge remains a read-only navigation data device from the Control Hub's perspective.

1. Build and install the Robot Controller app from this repository.
2. Open the active Robot Configuration.
3. On I2C Bus 1, Port 0, add `Fable ESP Navigation`.
4. Name it exactly `FableNav`.
5. On I2C Bus 2, Port 0, add `Fable Ultrasonic`.
6. Name it exactly `FableSonar`.
7. Confirm the integrated IMU is named `imu`.
8. Save and activate the configuration.
9. Initialize the `Fable` OpMode and inspect its navigation telemetry.

The `Fable` OpMode polls at 1 Hz in TeleOp and 5 Hz while navigating.

`FableSonar` and `FableNav` are independent devices on independent buses. Registering one does not require the other, and a missing or misconfigured `FableSonar` does not affect navigation. `FableTeleOp` polls `UltrasonicSubsystem.pollDistance()` once per loop iteration, unconditionally and not rate-limited, and feeds each reading to `ObstacleAvoidanceController`, which can override the drive command produced by `PointToPointController` during `AUTO_NAVIGATING` and `GOONING`. See `docs/fable-autonomous-mode.md` for the avoidance state machine, telemetry, and tuning order; this document only covers the wire protocol the reading comes from.

## Bring-Up Order

Test one boundary at a time:

1. Flash and USB-power only the Pico. Connect it to the Hub I2C port and initialize `Fable`. Expect packet status `OK` and invalid GPS.
2. Connect C3 ground and UART to the Pico, then power the C3. Confirm Pico USB serial `uart_ok` increases and `uart_bad` stays zero.
3. Connect the GPS. Indoors, packet status should remain `OK`; only GPS validity may remain false.
4. Move outdoors with a clear sky view. Confirm sequence, coordinates, satellite count, and GPS age update.
5. Start the driver-side ESP/laptop process. Confirm driver link status and target data propagate through ESP-NOW, UART, I2C, and telemetry.

Stop and isolate the indicated boundary if a check fails:

| Symptom | Likely boundary |
| --- | --- |
| `BAD_MAGIC`, `BAD_CHECKSUM`, or I2C warning with Pico alone | Pico-to-Hub wiring, firmware, or Robot Configuration |
| `I2C: OK`, but `uart_ok` stays zero | C3-to-Pico UART wiring, pin mapping, or C3 firmware |
| `uart_bad` increases | UART noise, mismatched baud, ground, or corrupted C3 packets |
| Packet `OK`, GPS invalid, character count zero | GPS power or GPS TX-to-GPIO4 wiring |
| GPS valid, target invalid | Driver ESP-NOW command path |

## I2C Protocol Version 1

The packet is a 56-byte read-only register map beginning at register `0x00`. The Control Hub reads offsets `0x00`, `0x0E`, `0x1C`, and `0x2A`, requesting 14 bytes each. The Pico latches the latest complete UART snapshot when register `0x00` is selected and serves all four chunks from that same copy.

All multi-byte values are little-endian. Coordinates are signed degrees multiplied by `10,000,000`.

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
| 36 | 4 | uint32 | Age of last valid driver command in milliseconds |
| 40 | 2 | uint16 | HDOP x 100 |
| 42 | 1 | uint8 | Satellite count, clamped to 255 |
| 43 | 1 | uint8 | Reserved, zero |
| 44 | 4 | uint32 | GPS characters processed |
| 48 | 4 | uint32 | ESP uptime in milliseconds |
| 52 | 2 | uint16 | Reserved, zero |
| 54 | 2 | uint16 | CRC-16/CCITT-FALSE over bytes 0-53 |

Flag byte at offset 6:

| Bit | Hex | Meaning |
| ---: | --- | --- |
| 0 | `0x01` | Current GPS location is valid |
| 1 | `0x02` | Target coordinate is valid |
| 2 | `0x04` | Satellite count is valid |
| 3 | `0x08` | HDOP is valid |
| 4 | `0x10` | Driver ESP-NOW link is alive |

CRC parameters are polynomial `0x1021`, initial value `0xFFFF`, no reflection, and no final XOR.

## I2C Protocol — FSON Frame

The HC-SR04 rangefinder is a separate device on a separate bus. The Pico serves it from `i2c1` at 7-bit address `0x43`, with its own 12-byte read-only register map beginning at register `0x00`. The Control Hub reads offset `0x00` and requests all 12 bytes in a single transaction; the frame is small enough that no chunking is needed. The Pico latches its published frame when register `0x00` is selected, the same way it does for `FNAV`.

The magic is `FSON`, not `FNAV`, specifically so the Control Hub can tell which device it is talking to if a cable is on the wrong port.

All multi-byte values are little-endian.

| Offset | Size | Type | Field |
| ---: | ---: | --- | --- |
| 0 | 4 | bytes | ASCII magic `FSON` |
| 4 | 1 | uint8 | Protocol version, currently `1` |
| 5 | 1 | uint8 | Frame length, currently `12` |
| 6 | 2 | uint16 | Distance in millimetres; `0xFFFF` means no valid reading |
| 8 | 2 | uint16 | Sample sequence, increments once per completed ping cycle |
| 10 | 2 | uint16 | CRC-16/CCITT-FALSE over bytes 0-9 |

CRC parameters are identical to `FNAV`: polynomial `0x1021`, initial value `0xFFFF`, no reflection, and no final XOR. The Java driver reuses `FableNavigationI2cDevice.crc16Ccitt` rather than carrying a second copy of the algorithm.

`0xFFFF` at offset 6 is a normal sentinel, not an error. A timeout, a target beyond about 4 m, or a weak echo off an angled surface all produce a live device with nothing to report. `UltrasonicReading.packetValid()` reports whether the I2C transaction and CRC succeeded; `UltrasonicReading.hasDistance()` reports whether there is an actual usable number. Those are different conditions and callers must distinguish them. `distanceMeters()` returns `NaN` when there is no reading, so an absent measurement can never be mistaken for zero distance.

The sample sequence at offset 8 increments by one per completed ping cycle, roughly every 60 ms, and wraps naturally at 65536. It plays the same role for this device that the navigation sequence plays for `FNAV`: it distinguishes a device that is alive and cycling but has nothing to report from a device or bridge that is dead.

## IMU Heading Frame

GPS bearing uses compass convention: north is 0 degrees and east is 90 degrees. FTC IMU yaw is counterclockwise-positive, so robot compass heading is normalized `-yaw`.

1. Point Fable forward toward the field direction defined as north.
2. Press Y/Triangle during `Fable` initialization to reset IMU yaw.
3. Confirm compass heading is near 0 degrees.
4. Turn Fable clockwise and confirm compass heading increases toward 90 degrees.

`NavigationSubsystem` is configured for Fable's installed Control Hub orientation: logo facing up and USB port facing backward.

## Hardware References

- [Raspberry Pi Pico C/C++ SDK setup](https://www.raspberrypi.com/documentation/microcontrollers/c_sdk.html)
- [Official Pico I2C peripheral memory example](https://github.com/raspberrypi/pico-examples/tree/master/i2c/slave_mem_i2c)
- [Raspberry Pi Pico pinout and hardware documentation](https://www.raspberrypi.com/documentation/microcontrollers/pico-series.html)
- [REV Control Hub electrical specifications](https://docs.revrobotics.com/duo-control/control-system-overview/control-hub-basics)
- [Adafruit Ultimate GPS pinout](https://learn.adafruit.com/adafruit-ultimate-gps/pinouts)
