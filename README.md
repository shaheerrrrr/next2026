# LoRa Fleet Control System

This repository contains the deployed long-range driver station and embedded
firmware for three robots: Flash, Fable, and Sol. A PS4/DS4 controller is read
by a desktop Python application, converted into addressed binary gamepad
frames, transmitted over LoRa, and exposed to each robot's Android FTC Driver
Station phone as a USB HID gamepad.

Fable also has a separate GPS navigation channel. Two ESP32-C3 boards exchange
targets and telemetry over ESP-NOW, and a Raspberry Pi Pico exposes Fable's
navigation snapshot to the REV Control Hub over I2C.

The current deployed scope is intentional:

| Robot | Long-range tele-op | GPS navigation |
| --- | --- | --- |
| Flash | Yes | No |
| Fable | Yes | Yes |
| Sol | Yes | No |

## Quick Start

All boards are assumed to be flashed already. To operate the system, follow
[Runtime Procedures](docs/procedures.md). The normal command includes both the
driver-side Uno port and the driver-side Fable navigation ESP32-C3 port.

The dashboard is served at:

```text
http://127.0.0.1:8765
```

The most important operational warning is that stopping the desktop process
neutralizes LoRa HID output, but it is not an authoritative stop command for a
Fable autonomous routine already running on the Control Hub. Cancel autonomy
or stop the OpMode before shutting down the driver station.

## Documentation

| Document | What it contains |
| --- | --- |
| [Architecture](docs/architecture.md) | End-to-end component model, data planes, ownership boundaries, and failure behavior. |
| [Runtime Procedures](docs/procedures.md) | Mac and Windows commands and the normal operator workflow, assuming all firmware is deployed. |
| [Deployment Reference](docs/deployment.md) | Which source is flashed to every board, wiring, board targets, libraries, and Control Hub branch ownership. |
| [Protocol Reference](docs/protocol.md) | Current tele-op, HID, serial, ESP-NOW, UART, and I2C wire contracts. No retired protocol versions. |
| [Multi-Robot Operation](docs/multi-robot.md) | Robot addressing, selection, background Fable autonomy, registration, and fleet safety behavior. |
| [Fable Navigation](docs/fable-navigation.md) | Fable GPS target lifecycle, map behavior, readiness rules, operator controls, and status semantics. |
| [Driver Navigation LED](docs/driver-navigation-led.md) | Meaning of the M5Stamp C3 status LED colors, breathing states, and packet pulses. |
| [Agent Guide](AGENTS.md) | Repository rules for Codex and other coding agents, documentation coupling, validation, branches, and handoff expectations. |

## Quick Find

| Need | Start here |
| --- | --- |
| Run the driver station on macOS | [Runtime Procedures: macOS](docs/procedures.md#macos-command) |
| Run the driver station on Windows | [Runtime Procedures: Windows](docs/procedures.md#windows-powershell-command) |
| Identify serial ports | [Runtime Procedures: Find Serial Ports](docs/procedures.md#find-serial-ports) |
| Register a robot as Driver 1 | [Multi-Robot Operation: Driver Registration](docs/multi-robot.md#driver-registration) |
| Send Fable to a GPS point | [Fable Navigation: Operator Workflow](docs/fable-navigation.md#operator-workflow) |
| Understand a dashboard status | [Fable Navigation: Status Semantics](docs/fable-navigation.md#status-semantics) |
| Find what is flashed to a board | [Deployment Reference](docs/deployment.md#deployment-matrix) |
| Change a packet or message | [Protocol Reference](docs/protocol.md) and [Agent Guide: Coupled Changes](AGENTS.md#coupled-changes) |
| Understand robot selection | [Multi-Robot Operation](docs/multi-robot.md) |
| Interpret the navigation LED | [Driver Navigation LED](docs/driver-navigation-led.md) |

## Source Map

| Path | Responsibility |
| --- | --- |
| `driver_station_flask.py` | Controller input, addressed frame generation, serial reconnects, Flask dashboard, map UI, and Fable navigation serial bridge. |
| `driverstation/driverstation.ino` | Driver-side Uno serial-to-LoRa bridge. |
| `flash/flash.ino` | Flash's Feather M0 LoRa receiver and USB HID gamepad. |
| `fable/fable.ino` | Fable's Feather M0 LoRa receiver and USB HID gamepad. |
| `sol/sol.ino` | Sol's Feather 32u4 RFM95 LoRa receiver and USB HID gamepad. |
| `fable_driver_nav_esp32c3/fable_driver_nav_esp32c3.ino` | Driver-side Fable ESP-NOW bridge and status LED. |
| `fable_robot_nav_esp32c3/fable_robot_nav_esp32c3.ino` | Robot-side GPS reader, ESP-NOW endpoint, telemetry source, and Pico UART producer. |
| `fable_navigation_bridge/` | Pico UART-to-I2C navigation snapshot bridge and HC-SR04 ultrasonic I2C bridge. |
| `docs/` | Human and agent-facing system documentation. |

The FTC robot projects are maintained on the `flash`, `fable`, and `sol`
branches. The host application, radio firmware, navigation sidecar firmware,
and shared documentation are maintained on `ftc-lora`.

## Current Invariants

- Tele-op protocol version: `3`
- Tele-op frame length: `21` bytes
- LoRa frequency: `915.0 MHz`
- Desktop-to-Uno serial: `115200 baud`
- Desktop-to-driver-navigation-C3 serial: `115200 baud`
- Normal transmit rate: `20 Hz`
- Robot IDs: Flash `1`, Fable `2`, Sol `3`, broadcast `255`
- Robot HID stale timeout: `250 ms`
- Fable navigation I2C address: `0x42`
- Fable ultrasonic I2C address: `0x43`
- Fable ESP-NOW channel: `1`

Treat deployed source code as ground truth when documentation and behavior ever
disagree, then update the affected documentation in the same change.
