# LoRa FTC-Style Robot Control

This repository contains a personal robotics control path that replaces the usual short-range Driver Station control link with a LoRa gamepad bridge.

The project is not intended for official FTC competition use. FTC legality is not a design constraint here.

## Documents

- [Architecture](docs/architecture.md): end-to-end system design and data flow.
- [Procedures](docs/procedures.md): how to start, connect, and test the whole system.
- [Protocol Reference](docs/protocol.md): 19-byte controller frame, checksum, button bits, and HID mapping.
- [Troubleshooting](docs/troubleshooting.md): known symptoms and likely causes.

## Main Code

- [gamepad_lora_tx.py](gamepad_lora_tx.py): Mac-side Python transmitter and local dashboard.
- [uno/uno.ino](uno/uno.ino): Arduino Uno serial-to-LoRa bridge.
- [feather/feather.ino](feather/feather.ino): Feather M0 RFM9x LoRa receiver and USB HID gamepad.

