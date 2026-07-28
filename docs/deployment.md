# Deployment Reference

This document answers two maintenance questions:

1. What software belongs on each computer or board?
2. How are those components connected?

Normal operators do not need to flash anything. They should use
[Runtime Procedures](procedures.md).

## Deployment Matrix

| Location | Hardware | Source | Target/runtime | Purpose |
| --- | --- | --- | --- | --- |
| Driver station | Mac or Windows computer | `driver_station_flask.py` | Python 3 virtual environment | Controller input, LoRa serial frames, dashboard, map, and Fable navigation serial link |
| Driver station | Arduino Uno R3 + Adafruit RFM95W breakout | `driverstation/driverstation.ino` | Arduino Uno | Validated USB serial to LoRa bridge |
| Flash | Adafruit Feather M0 RFM9x | `flash/flash.ino` | Adafruit Feather M0 with TinyUSB | Robot ID 1 LoRa receiver and USB HID gamepad |
| Fable | Adafruit Feather M0 RFM9x | `fable/fable.ino` | Adafruit Feather M0 with TinyUSB | Robot ID 2 LoRa receiver and USB HID gamepad |
| Sol | Adafruit Feather 32u4 RFM95 | `sol/sol.ino` | Adafruit Feather 32u4 | Robot ID 3 LoRa receiver and USB HID gamepad |
| Driver station, Fable navigation | M5Stamp C3 / ESP32-C3 | `fable_driver_nav_esp32c3/fable_driver_nav_esp32c3.ino` | M5Stamp C3 | USB serial to ESP-NOW bridge and visible status LED |
| Fable robot | ESP32-C3 | `fable_robot_nav_esp32c3/fable_robot_nav_esp32c3.ino` | Installed ESP32-C3 board target | GPS reader, ESP-NOW command/telemetry endpoint, UART snapshot producer |
| Fable robot | Raspberry Pi Pico | `fable_navigation_bridge/` | Pico SDK C/C++ firmware | UART snapshot to I2C slave bridge and HC-SR04 ultrasonic I2C slave |
| Flash Control Hub | REV Control Hub | Branch `flash` | FTC Robot Controller project | Flash mechanisms and tele-op behavior |
| Fable Control Hub | REV Control Hub | Branch `fable` | FTC Robot Controller project | Fable mechanisms, navigation consumer, and autonomous drive logic |
| Sol Control Hub | REV Control Hub | Branch `sol` | FTC Robot Controller project | Sol drive, turret, and shooter behavior |

The `ftc-lora` branch owns all files in the main worktree listed above. The
robot Control Hub projects are intentionally maintained on separate branches.

## Desktop Driver Station

### Files

- `driver_station_flask.py`
- `requirements.txt`

### Runtime Dependencies

The requirements file pins the deployed Python packages, including:

- Flask
- pygame
- pyserial

Use the commands in [Runtime Procedures](procedures.md#one-time-python-setup)
instead of installing packages ad hoc.

### USB Responsibilities

The desktop normally has two independent serial devices:

| CLI option | Device | Default baud |
| --- | --- | --- |
| `--port` | Arduino Uno tele-op bridge | `115200` |
| `--fable-nav-port` | Driver-side M5Stamp C3 | `115200` |

The Uno port is required as a command-line value. The application still starts
and retries if that named device is temporarily absent. The navigation port is
optional to the parser, but it should be supplied in normal fleet operation so
Fable navigation is available.

## Driver-Side Uno And RFM95W

### Firmware

Flash:

```text
driverstation/driverstation.ino
```

### Board And Library

- Board target: Arduino Uno
- Radio library: RadioHead, using `RH_RF95`
- Serial baud: `115200`
- LoRa frequency: `915.0 MHz`
- Transmit power configured by the sketch: `20 dBm`

### Wiring

| RFM95W breakout | Arduino Uno |
| --- | --- |
| `VIN` | `5V` |
| `GND` | `GND` |
| `SCK` / `SCLK` | `D13` |
| `MISO` | `D12` |
| `MOSI` | `D11` |
| `CS` | `D10` |
| `RST` | `D9` |
| `G0` / `DIO0` / `IRQ` | `D2` |

The RFM95W breakout accepts the documented supply connection, but its radio
logic is not a generic 5 V SPI peripheral. Use the Adafruit breakout as wired
and do not substitute an unprotected bare module without checking levels and
power requirements.

The Uno must have a suitable 915 MHz antenna attached before transmitting.

### Behavior To Preserve

The Uno validates magic, protocol version, frame length, and XOR checksum. It
then forwards the 21-byte frame unchanged. It does not know the meaning of
buttons, sticks, triggers, or robot-specific controls.

## Flash Feather M0

### Firmware

```text
flash/flash.ino
```

### Board And Libraries

- Hardware: Adafruit Feather M0 RFM9x
- Compiled robot ID: `1`
- Radio: integrated RFM95/RFM9x at `915.0 MHz`
- Radio library: RadioHead `RH_RF95`
- USB stack: Adafruit TinyUSB
- Integrated radio pins: `CS 8`, `RST 4`, `IRQ 3`

### Connections

- Feather USB to Flash Android phone through USB OTG
- Phone Wi-Fi to Flash REV Control Hub
- 915 MHz antenna attached to Feather radio

The Feather's USB data connection and power path must be compatible with the
phone/OTG arrangement used in the deployed robot.

## Fable Feather M0

### Firmware

```text
fable/fable.ino
```

### Board And Libraries

- Hardware: Adafruit Feather M0 RFM9x
- Compiled robot ID: `2`
- Radio: integrated RFM95/RFM9x at `915.0 MHz`
- Radio library: RadioHead `RH_RF95`
- USB stack: Adafruit TinyUSB
- Integrated radio pins: `CS 8`, `RST 4`, `IRQ 3`

### Connections

- Feather USB to Fable Android phone through USB OTG
- Phone Wi-Fi to Fable REV Control Hub
- 915 MHz antenna attached to Feather radio

The Fable Feather carries only tele-op HID state. GPS targets and telemetry do
not pass through it.

## Sol Feather 32u4

### Firmware

```text
sol/sol.ino
```

### Board And Libraries

- Hardware: Adafruit Feather 32u4 RFM95 LoRa Radio
- Compiled robot ID: `3`
- Radio library: RadioHead `RH_RF95`
- USB implementation: native AVR HID APIs used by the sketch
- Integrated radio pins: `CS 8`, `RST 4`, `IRQ 7`
- LoRa frequency: `915.0 MHz`

Do not compile Sol's current firmware as a Feather M0 sketch. The 32u4 USB and
interrupt architecture is different, and the deployed source intentionally has
a separate HID implementation.

### Connections

- Feather USB to Sol Android phone through USB OTG
- Phone Wi-Fi to Sol REV Control Hub
- 915 MHz antenna attached to Feather radio

## Driver-Side Fable Navigation C3

### Firmware

```text
fable_driver_nav_esp32c3/fable_driver_nav_esp32c3.ino
```

### Board And Libraries

- Hardware: M5Stamp C3 / ESP32-C3
- Arduino ESP32 core with ESP-NOW and Wi-Fi support
- FastLED for the onboard SK6812
- USB serial: `115200 baud`
- ESP-NOW channel: `1`
- ESP-NOW peer: unencrypted broadcast
- Onboard status LED: one SK6812 on GPIO `2`, GRB order

The board sends a navigation heartbeat every 500 ms, accepts newline serial
commands from Python, validates robot telemetry, and emits newline JSON to the
desktop. LED behavior is documented in
[Driver Navigation LED](driver-navigation-led.md).

## Robot-Side Fable Navigation C3

### Firmware

```text
fable_robot_nav_esp32c3/fable_robot_nav_esp32c3.ino
```

### Board And Libraries

- ESP32-C3 Arduino target matching the deployed board
- TinyGPSPlus
- ESP-NOW and Wi-Fi from the Arduino ESP32 core
- ESP-NOW channel: `1`
- GPS baud: `9600`
- Pico UART baud: `115200`

### GPS Wiring

The sketch defines the ESP32-C3 UART pins from the C3's point of view:

| Signal | ESP32-C3 pin |
| --- | --- |
| GPS data into C3 | GPIO `4` (`GPS_RX_PIN`) |
| Optional C3 data toward GPS | GPIO `5` (`GPS_TX_PIN`) |

Connect transmitter to receiver and share ground. Confirm the exact GPS
module's voltage and pin labels before wiring.

### Pico UART Wiring

| ESP32-C3 | Raspberry Pi Pico |
| --- | --- |
| GPIO `6` C3 TX | GP `1` Pico UART0 RX |
| GPIO `7` C3 RX | GP `0` Pico UART0 TX |
| GND | GND |

The current data path primarily requires C3 TX to Pico RX, but the deployed
wiring supports both directions. Both sides use 3.3 V logic.

### Behavior To Preserve

- Reads GPS continuously
- Accepts target, clear, and heartbeat commands over ESP-NOW
- Sends navigation telemetry at 10 Hz
- Sends a 56-byte CRC-protected `FNAV` snapshot to the Pico at 10 Hz
- Classifies a recent fix with at least four satellites as `GOOD`
- Marks the driver link alive for two seconds after a valid command or heartbeat

## Fable Raspberry Pi Pico Bridge

### Source

```text
fable_navigation_bridge/
```

Important files:

| File | Purpose |
| --- | --- |
| `main.c` | UART parser, CRC validation, snapshot publication, both I2C slaves, and HC-SR04 ranging |
| `CMakeLists.txt` | Pico SDK build definition |

The Pico runs two independent jobs on two cores. Core0 owns the UART parser and
the `0x42` navigation slave. Core1 owns the HC-SR04 and the `0x43` ultrasonic
slave. Neither core reads or writes the other's buffers.

### Pinout

| Function | Pico pin |
| --- | --- |
| UART0 TX | GP `0` |
| UART0 RX | GP `1` |
| I2C0 SDA | GP `4` |
| I2C0 SCL | GP `5` |
| I2C1 SDA | GP `6` |
| I2C1 SCL | GP `7` |
| HC-SR04 trigger output | GP `14` |
| HC-SR04 echo input | GP `15` |
| Ground | GND shared with C3, HC-SR04, and Control Hub interface |

### I2C Configuration

Navigation snapshot slave:

- Seven-bit address: `0x42`
- Bus: I2C0, `100 kHz`
- Snapshot length: `56` bytes
- Control Hub reads: four chunks of `14` bytes

Ultrasonic slave:

- Seven-bit address: `0x43`
- Bus: I2C1, `100 kHz`
- Frame length: `12` bytes
- Control Hub reads: one transaction of `12` bytes

The Pico acts as the I2C peripheral/slave on both buses. The REV Control Hub is
the I2C controller/master. Use appropriate 3.3 V-compatible pull-ups and the
deployed REV I2C cabling/interface.

The REV I2C ports supply their own pull-ups, which is why the existing
`FableNav` connection on I2C0 needs none added. The same expectation applies to
whichever REV port carries I2C1. If I2C1 is instead wired to a port or breakout
that does not supply them, fit external `2.2k` to `4.7k` pull-ups to 3.3 V on
both SDA and SCL.

### HC-SR04 Wiring

| HC-SR04 pin | Connection |
| --- | --- |
| `VCC` | Control Hub `+5V` auxiliary output |
| `TRIG` | Pico GP `14` directly |
| `ECHO` | Pico GP `15` through a resistor divider |
| `GND` | Shared ground with the Pico and the Control Hub |

Two electrical requirements are mandatory. Neither is optional and neither is
enforced by firmware.

**Power the sensor from the Control Hub `+5V` auxiliary output**, the same rail
that supplies the Pico's `VSYS`. Do not power it from the Pico's `VBUS` pin.
`VBUS` is only live while USB is connected, and the deployed Pico runs with USB
disconnected during normal robot operation, so a `VBUS`-powered sensor would go
dead the moment the robot left the bench.

**Fit a resistor divider between `ECHO` and GP `15`.** The HC-SR04 drives `ECHO`
at 5 V and RP2040 GPIO is not 5 V tolerant; its absolute maximum is roughly
`VIO + 0.3 V`, about `3.6 V`. Connecting `ECHO` straight to GP `15` can damage
the Pico. A `1k` series resistor from `ECHO` to GP `15` with a `2k` resistor
from GP `15` to ground brings the pulse to about `3.3 V`. Installing this
divider is the physical wiring installer's responsibility.

`TRIG` needs no level shifting. The HC-SR04 accepts the Pico's 3.3 V trigger
pulse.

### SDK Libraries

The checked-in `CMakeLists.txt` links:

- `hardware_i2c`
- `hardware_uart`
- `pico_i2c_slave`
- `pico_multicore`
- `pico_stdlib`

`pico_multicore` is required by the ultrasonic bridge, which runs on core1.
`pico_i2c_slave` already supports two simultaneous slave instances, so serving
both `0x42` and `0x43` needs no additional library.

### Build Outline

A working Raspberry Pi Pico SDK is required. The checked-in `CMakeLists.txt`
imports the SDK from the `PICO_SDK_PATH` environment variable.

macOS or Linux shell:

```bash
export PICO_SDK_PATH=/absolute/path/to/pico-sdk
cmake -S fable_navigation_bridge -B fable_navigation_bridge/build
cmake --build fable_navigation_bridge/build
```

Windows PowerShell:

```powershell
$env:PICO_SDK_PATH = "C:\absolute\path\to\pico-sdk"
cmake -S .\fable_navigation_bridge -B .\fable_navigation_bridge\build
cmake --build .\fable_navigation_bridge\build
```

The generated firmware is:

```text
fable_navigation_bridge/build/fable_navigation_bridge.uf2
```

Copy that UF2 to the Pico while it is mounted in BOOTSEL mode.

Do not commit generated build directories unless repository policy changes.

## REV Control Hub Projects

The Control Hub Java sources are not duplicated into the `ftc-lora` worktree.
Use the robot branch that owns each deployed project.

| Robot | Branch | Notes |
| --- | --- | --- |
| Flash | `flash` | Reads ordinary FTC gamepad fields supplied through the Flash Feather |
| Fable | `fable` | Reads ordinary FTC gamepad fields and the `FableNav` I2C device |
| Sol | `sol` | Reads Sol's HID profile for drive, turret, and shooter behavior |

Current Fable navigation assumptions include:

- Hardware configuration name: `FableNav`
- I2C address: `0x42`
- IMU hardware name: `imu`
- Navigation arming from PS4 Cross / FTC `gamepad1.a`
- Manual override and exit logic implemented on the Control Hub

The ultrasonic frame at I2C address `0x43` is a second, separate I2C device on
the same Pico. Its Control Hub driver is being developed on branch `fable` and
its hardware configuration name is set there, not by the Pico firmware.

The current Fable branch also contains the temporary roller-only `ChudIntake`
used by `FableTeleOp`. It assumes the intake begins physically down. The normal
`Intake.java` remains in the branch. This is a robot-code condition, not a
change to the long-range transport.

## Compatible Deployment Sets

### Tele-Op Protocol Change

Deploy all changed protocol endpoints as one set:

- Desktop Python
- Uno bridge when framing/version/length changes
- Flash Feather
- Fable Feather
- Sol Feather
- Matching Control Hub code when HID meaning changes

A version or length mismatch causes the bridge or receiver to reject frames.
A button-bit change can transmit successfully while silently controlling the
wrong HID usage if only one endpoint is updated.

### Fable Navigation Protocol Change

Deploy every affected navigation endpoint as one set:

- Desktop Python when serial JSON/commands or UI fields change
- Driver navigation C3
- Robot navigation C3
- Pico bridge when FNAV/I2C changes
- Fable Control Hub project when I2C decoding or readiness changes

Packed structures, CRC coverage, and freshness semantics must agree exactly.

### Fable Ultrasonic Protocol Change

The `FSON` frame and the `0x43` register protocol are independent of the
navigation set above. Deploy these together:

- Pico bridge
- Fable Control Hub ultrasonic driver on branch `fable`

A frame length change also changes the Control Hub's single-transaction read
length. Do not ship one without the other.

## Post-Deployment Validation

Perform validation from the nearest boundary outward.

### Tele-Op

1. Confirm the Uno initializes the radio.
2. Confirm each Feather initializes radio and USB.
3. Register each phone as Driver 1.
4. Verify every dashboard control before motion.
5. Verify Flash and Fable analog triggers in FTC telemetry or a safe test OpMode.
6. Verify Sol D-pad and right trigger.
7. Select each robot and confirm non-selected robots remain neutral.
8. Interrupt LoRa and confirm HID neutralization within 250 ms.

### Fable Navigation

1. Confirm the driver C3 startup status and LED.
2. Confirm the robot C3 parses GPS data.
3. Confirm telemetry reaches the dashboard and sequence numbers advance.
4. Confirm the Pico reports valid FNAV snapshots over USB diagnostics.
5. Confirm the Control Hub reads `FableNav` with a valid CRC and fresh sequence.
6. Send and clear a target without enabling motors.
7. Perform the first motion test with the robot restrained or at low power.
8. Perform GPS navigation tests outdoors with an operator ready to stop the
   OpMode.

### Fable Ultrasonic

1. Confirm the `ECHO` resistor divider is installed and measures at or below
   3.3 V at GP `15` before the Pico is connected to the sensor.
2. Confirm the HC-SR04 is powered from the Control Hub `+5V` auxiliary output
   and not from Pico `VBUS`, then confirm it still reads with USB unplugged.
3. Confirm the Control Hub finds an I2C device at `0x43` on the I2C1 port.
4. Confirm a `12`-byte read from register `0` returns magic `FSON`, version `1`,
   length `12`, and a valid CRC.
5. Confirm `sampleSeq` advances on repeated reads. A frozen `sampleSeq` means
   the Pico's ultrasonic core stopped, not that the sensor sees nothing.
6. Place a target at a known distance and confirm `distanceMm` is plausible.
7. Aim the sensor at open space and confirm `distanceMm` reads `0xFFFF` while
   `sampleSeq` keeps advancing.
8. Confirm the `0x42` navigation snapshot still reads with a valid CRC and that
   the Pico's USB status line still shows `uart_bad` staying flat. Ultrasonic
   ranging must not disturb the UART parser.

## Hardware Safety

- Attach antennas before LoRa transmission.
- Do not power radios or microcontrollers from unverified voltage rails.
- Share ground for UART and I2C interfaces.
- Cross UART TX and RX.
- Confirm board selection before uploading, especially Feather M0 versus 32u4.
- Stop Fable autonomy before unplugging driver hardware.
- Treat successful compilation as necessary but not sufficient; USB HID,
  Android mappings, radio range, GPS quality, and motor behavior require
  physical verification.
