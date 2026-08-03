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
| Fable robot | Raspberry Pi Pico | `fable_navigation_bridge/` | Pico SDK C/C++ firmware | UART snapshot to I2C slave bridge |
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

`--ui-commands <path>` optionally overrides where the Driver Station command
chord config (INIT/START/STOP/OPMODE) is read from and saved to. It defaults
to `ui_commands.json` beside `driver_station_flask.py`, is not required, and
is created automatically on first save.

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

### Driver Station Command Chords (verified)

Flash's firmware also decodes `BTN_UI_CMD` via a second, genuinely
independent USB HID interface (the same TinyUSB approach as Fable's, ported
directly since both boards share the Feather M0 + Adafruit TinyUSB stack).
**Fully verified on real hardware**, same as Fable: the board enumerates both
`Adafruit Feather M0` HID interfaces (`KEYBOARD | GAMEPAD | JOYSTICK` and
`KEYBOARD | ALPHAKEY`), and OpMode-select/INIT/START/STOP chords from the
dashboard all produce real clicks against Flash's own Driver Station app,
with a live Robot Controller connection (Flash's control hub currently has a
single registered TeleOp OpMode, named "Flash", configured on slot 0). The
real DS app resource-ids matched Fable's exactly (same shared Qualcomm/REV
APK), confirming those ids are stable across at least these two phones'
installs.

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

Fable presents **two** USB HID interfaces: the gamepad shared with Flash, plus
a second boot-layout keyboard interface used only to emit Driver Station
command chords (INIT/START/STOP/OpMode-select) into the phone. This has been
verified end-to-end on real hardware. For the chords to have any effect, the
phone must have the `RobotReset` AccessibilityService app (a separate
app/repo, branch `robot-reset-app`) installed, its accessibility service
enabled, and its `Config` screen pointed at that Driver Station app build's
real button ids (see `robot-reset-app:docs/bring-up.md` for the discovery
process and this team's discovered ids). Flash's firmware has the same second
interface as of a recent update and has been fully verified end-to-end on
real hardware, same as Fable: real chords produce real clicks against
Flash's own Driver Station app, with a live Robot Controller connection.
Sol's
firmware has a different-in-kind implementation (a second Report ID on its
single AVR HID interface, since its USB stack has no equivalent to a second
independent interface). It is now also **fully verified end-to-end** on
real hardware, same as Fable and Flash: real chords produce real clicks
against Sol's own Driver Station app, with a live Robot Controller
connection, even though Android merges the keyboard usage into the same
logical input device as the gamepad rather than splitting it out the way
Fable/Flash's second interface does. Getting there needed a firmware fix
(AVR's blocking HID send, see the Sol section below) and an Android-app-side
resolver fix, both detailed in `robot-reset-app:docs/bring-up.md`
("Multi-robot findings: Flash and Sol").

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

### Driver Station Command Chords (fully verified)

Sol's firmware also decodes `BTN_UI_CMD`, appending a second Report-ID HID
collection (keyboard) onto the same single AVR HID interface the gamepad
uses. This is architecturally different from Fable/Flash's approach of a
second, genuinely independent USB interface. Unlike Fable/Flash, the second
Report ID does **not** produce a separate logical input device —
`dumpsys input` shows one merged `Adafruit Feather 32u4` device whose class
bitmask includes `KEYBOARD` alongside `JOYSTICK`/`DPAD` — but a real chord
still reaches `onKeyEvent` correctly, so the merged-device shape does not
break delivery in practice. OpMode-select, INIT, START, and STOP are all now
confirmed producing real clicks against Sol's actual Driver Station app,
with a live Robot Controller connection. Two things were needed to get from
"chord reaches `onKeyEvent`" to "chord actually works": a firmware fix
(`sol.ino`'s `loop()` now skips its routine gamepad send while a chord press
is outstanding, since AVR's `HID_::SendReport()` is a blocking call — up to
~500ms — with no non-blocking readiness check on this USB stack, unlike
TinyUSB's `usb_hid.ready()` on the M0 boards), and an Android-app-side
resolver fix (`RobotResetService` now sets `ACTION_ACCESSIBILITY_FOCUS`
before every click, and gained a new opt-in `TargetSpec.Kind.TEXT_SIBLING`
capability for Sol's Driver Station app skin, whose STOP control is an
icon with no accessible label sharing its screen bounds with a
non-functional labeled decoy). Full detail in
`robot-reset-app:docs/bring-up.md` ("Multi-robot findings: Flash and Sol").

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
| `main.c` | UART parser, CRC validation, snapshot publication, and I2C slave |
| `CMakeLists.txt` | Pico SDK build definition |

### Pinout

| Function | Pico pin |
| --- | --- |
| UART0 TX | GP `0` |
| UART0 RX | GP `1` |
| I2C0 SDA | GP `4` |
| I2C0 SCL | GP `5` |
| Ground | GND shared with C3 and Control Hub interface |

### I2C Configuration

- Seven-bit address: `0x42`
- Bus speed: `100 kHz`
- Snapshot length: `56` bytes
- Control Hub reads: four chunks of `14` bytes

The Pico acts as the I2C peripheral/slave. The REV Control Hub is the I2C
controller/master. Use appropriate 3.3 V-compatible pull-ups and the deployed
REV I2C cabling/interface.

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
