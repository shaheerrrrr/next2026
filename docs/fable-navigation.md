# Fable Navigation Link

Fable now has an optional GPS/autonomy data path alongside the proven LoRa HID tele-op path.

## Data Flow

```text
Driver dashboard
  -> USB serial
  -> driver-side ESP32-C3
  -> ESP-NOW
  -> robot-side ESP32-C3
  -> I2C snapshot at 0x42
  -> REV Control Hub robot code
```

Telemetry flows back in the opposite direction:

```text
GPS module
  -> robot-side ESP32-C3
  -> ESP-NOW telemetry
  -> driver-side ESP32-C3
  -> USB serial JSON lines
  -> Flask dashboard
```

The LoRa HID system still handles tele-op robot selection and gamepad control. Fable navigation is an additional side channel.

## Firmware

- [fable_robot_nav_esp32c3/fable_robot_nav_esp32c3.ino](../fable_robot_nav_esp32c3/fable_robot_nav_esp32c3.ino): robot-side ESP32-C3 firmware.
- [fable_driver_nav_esp32c3/fable_driver_nav_esp32c3.ino](../fable_driver_nav_esp32c3/fable_driver_nav_esp32c3.ino): driver-station ESP32-C3 firmware.

The robot-side sketch:

- Parses GPS on GPIO 4 RX and GPIO 5 TX.
- Publishes the 56-byte `FNAV` I2C snapshot at address `0x42`.
- Receives target coordinates over ESP-NOW.
- Broadcasts GPS/target telemetry back over ESP-NOW.

The driver-side sketch:

- Receives ESP-NOW telemetry.
- Prints telemetry to USB serial as JSON lines.
- Accepts `TARGET <lat_e7> <lon_e7>` and `CLEAR` commands from Flask.
- Sends an ESP-NOW heartbeat every 500 ms so the robot-side `driver link alive` I2C flag can become true even before a target command is sent.

## Run Dashboard With Fable Navigation

Use the normal LoRa Uno port plus the driver ESP32-C3 serial port:

```bash
python driver_station_flask.py \
  --port /dev/cu.usbmodem11201 \
  --fable-nav-port /dev/cu.usbmodemXXXXX \
  --hz 20
```

If `--fable-nav-port` is omitted, tele-op still works and the dashboard shows Fable navigation as disconnected.

## Dashboard Behavior

When Fable is selected, the dashboard shows a navigation panel with:

- Current GPS position.
- GPS quality and ESP-NOW link state.
- A field rectangle, either calibrated from four corners or temporarily centered on the first GPS fix.
- A selected target dot and line from current position to target.
- A Calibrate Field popup for entering the four GPS corners.
- Send Target and Cancel Auto buttons.

Use `Calibrate Field` to enter the northwest, northeast, southeast, and southwest GPS coordinates. The dashboard saves these corners in browser localStorage and uses them to project current/target positions into the field rectangle.

If no calibration is saved, the dashboard falls back to a faint temporary 12 m local frame around the first GPS fix so the click-to-target UI still works.

When a target is sent, Fable visibly enters autonomous mode in the UI. The Fable robot selector tab keeps an `AUTO` badge even while the driver switches to Flash or Sol for tele-op.

## Arduino Libraries

Install these for the ESP32-C3 sketches:

- ESP32 Arduino core
- TinyGPSPlus
