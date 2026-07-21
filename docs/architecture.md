# Architecture

This project sends FTC-style gamepad input over LoRa. The Android Driver Station phone stays on the robot and connects locally to the REV Control Hub over the normal short-range Wi-Fi AP. Long-range control is provided by a custom gamepad transport from the driver base to the robot.

FTC legality is not relevant for this project.

## High-Level Flow

```text
PS4/DS4 controller
  -> MacBook Python app
  -> USB serial
  -> Arduino Uno
  -> RFM95W LoRa transmitter
  -> LoRa radio link
  -> Robot-side Feather RFM9x receiver
  -> USB HID gamepad
  -> Android FTC Driver Station app
  -> REV Control Hub
```

## Driver Base

The driver base contains:

- MacBook
- PS4/DS4 controller connected to the Mac over Bluetooth
- Arduino Uno connected to the Mac over USB serial
- Adafruit RFM95W LoRa breakout wired to the Uno

The Python app, [gamepad_lora_tx.py](../gamepad_lora_tx.py), reads the controller with `pygame`, converts the state into a compact 19-byte binary frame, and writes frames to the Uno at 115200 baud.

The app also hosts a local dashboard at:

```text
http://127.0.0.1:8765
```

The dashboard shows live stick positions, triggers, buttons, serial status, gamepad status, sequence numbers, sent count, and a transmit log.

## Robot Side

The robot side contains:

- REV Control Hub
- Android phone running the FTC Driver Station app
- Adafruit Feather M0 RFM9x connected to the phone with USB OTG on Flash and Fable
- Adafruit Feather 32u4 RFM95 connected to the phone with USB OTG on Sol

The Android phone still connects to the REV Control Hub over the normal local Wi-Fi path. The Feather acts only as a USB HID gamepad for the Driver Station phone.

The Feather receives LoRa packets, validates the frame, decodes the controller state, and sends a USB HID gamepad report to Android. Flash and Fable use TinyUSB on Feather M0 boards; Sol uses the ATmega32u4's native USB HID support. The Driver Station app sees each Feather as a controller, so robot code can read normal fields like:

```java
gamepad1.left_stick_x
gamepad1.left_stick_y
gamepad1.right_stick_x
gamepad1.right_stick_y
gamepad1.left_trigger
gamepad1.right_trigger
gamepad1.left_bumper
gamepad1.right_bumper
```

## Radio Bridge

The Uno is intentionally simple. It is a serial-to-LoRa bridge:

1. Read bytes from USB serial.
2. Synchronize on frame magic `0xA5 0x5A`.
3. Buffer exactly 19 bytes.
4. Validate the XOR checksum.
5. Transmit the frame unchanged over LoRa using RadioHead `RH_RF95`.

Most controller feature changes should not require Uno changes as long as the frame stays 19 bytes and the checksum rules stay the same.

## HID Bridge

The Feather is where Android/FTC gamepad behavior is defined. The sketches use a custom gamepad HID descriptor rather than a stock generic descriptor. Flash and Fable publish it through TinyUSB; Sol publishes the equivalent descriptor through the ATmega32u4 AVR HID core.

The descriptor exposes:

- 16 buttons
- X/Y axes for the left stick
- Z/Rz axes for the right stick
- Brake/Accelerator analog axes for triggers

The Brake/Accelerator trigger mapping matters because FTC robot code reads `left_trigger` and `right_trigger` as analog values, not button booleans.

## Safety Behavior

The Feather neutralizes controls if no valid LoRa frame is received for more than 250 ms.

When stale:

- Sticks return to neutral.
- Triggers return to zero.
- Buttons are released.
- LED turns off.

This prevents a lost radio link from leaving the robot driving on the last command.
