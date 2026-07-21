# LoRa FTC-Style Robot Control

This repository contains a personal robotics control path that replaces the usual short-range Driver Station control link with a LoRa gamepad bridge.

The project is not intended for official FTC competition use. FTC legality is not a design constraint here.

## Documents

- [Architecture](docs/architecture.md): end-to-end system design and data flow.
- [Procedures](docs/procedures.md): how to start, connect, and test the whole system.
- [Protocol Reference](docs/protocol.md): v2/v3 controller frames, checksum, button bits, and HID mapping.
- [Multi-Robot Control](docs/multi-robot.md): v3 addressed tele-op stack for Flash, Fable, and Sol.
- [Fable Navigation Link](docs/fable-navigation.md): ESP32-C3 GPS/I2C/ESP-NOW side channel for Fable.
- [Driver Navigation LED](docs/driver-navigation-led.md): M5Stamp C3 ambient link and packet activity color guide.
- [Troubleshooting](docs/troubleshooting.md): known symptoms and likely causes.

## Main Code

- [gamepad_lora_tx.py](gamepad_lora_tx.py): Mac-side Python transmitter and local dashboard.
- [uno/uno.ino](uno/uno.ino): Arduino Uno serial-to-LoRa bridge.
- [feather/feather.ino](feather/feather.ino): Feather M0 RFM9x LoRa receiver and USB HID gamepad.

## Multi-Robot Code

- [driver_station_flask.py](driver_station_flask.py): Flask fleet driver station for Flash, Fable, and Sol.
- [driverstation/driverstation.ino](driverstation/driverstation.ino): driver-side Uno bridge for v3 addressed packets.
- [flash/flash.ino](flash/flash.ino): Flash Feather receiver, robot id `1`.
- [fable/fable.ino](fable/fable.ino): Fable Feather receiver, robot id `2`.
- [sol/sol.ino](sol/sol.ino): Sol Feather 32u4 RFM95 receiver and native USB HID gamepad, robot id `3`.
- [fable_robot_nav_esp32c3/fable_robot_nav_esp32c3.ino](fable_robot_nav_esp32c3/fable_robot_nav_esp32c3.ino): Fable robot-side GPS/I2C/ESP-NOW firmware.
- [fable_driver_nav_esp32c3/fable_driver_nav_esp32c3.ino](fable_driver_nav_esp32c3/fable_driver_nav_esp32c3.ino): driver-side ESP-NOW/USB serial bridge for Fable navigation.
