# Procedures

This file describes the basic process for getting the full LoRa control system running.

## Hardware Setup

### Driver Base

Connect:

- PS4/DS4 controller to the MacBook over Bluetooth.
- Arduino Uno to the MacBook over USB.
- Adafruit RFM95W breakout to the Uno.

Known Uno wiring:

| RFM95W Breakout | Arduino Uno |
| --- | --- |
| VIN | 5V |
| GND | GND |
| SCK/SCLK | D13 |
| MISO | D12 |
| MOSI | D11 |
| CS | D10 |
| RST | D9 |
| G0/DIO0/IRQ | D2 |

Attach an antenna before transmitting.

### Robot Side

Connect:

- Android Driver Station phone to the REV Control Hub Wi-Fi AP.
- Feather M0 RFM9x to the Android phone using USB OTG.
- Antenna to the Feather radio.

The Feather should appear to Android as a USB gamepad.

## Software Setup

Upload/flash:

- [uno/uno.ino](../uno/uno.ino) to the Arduino Uno.
- [feather/feather.ino](../feather/feather.ino) to the Feather M0 RFM9x.

Both sketches use 915.0 MHz and 19-byte frames.

## Start the Python Transmitter

From the repository root:

```bash
cd ~/next2026
source .venv/bin/activate
python gamepad_lora_tx.py --port /dev/cu.usbmodem11301 --hz 20
```

Replace `/dev/cu.usbmodem11301` with the actual Uno serial port.

If the PS4 L1/R1 button indexes differ on the Mac, override them:

```bash
python gamepad_lora_tx.py --port /dev/cu.usbmodem11301 --hz 20 --l1-button 9 --r1-button 10
```

Open the dashboard:

```text
http://127.0.0.1:8765
```

## Start the Multi-Robot Flask Driver Station

For Flash/Fable/Sol control, flash:

- [driverstation/driverstation.ino](../driverstation/driverstation.ino) to the driver-side Uno.
- [flash/flash.ino](../flash/flash.ino) to Flash's Feather.
- [fable/fable.ino](../fable/fable.ino) to Fable's Feather.
- [sol/sol.ino](../sol/sol.ino) to Sol's Feather.

Install Python dependencies:

```bash
cd ~/next2026
source .venv/bin/activate
python -m pip install -r requirements.txt
```

Run:

```bash
python driver_station_flask.py --port /dev/cu.usbmodem11301 --hz 20
```

With Fable GPS navigation enabled, also connect the driver-side ESP32-C3 and pass its serial port:

```bash
python driver_station_flask.py --port /dev/cu.usbmodem11301 --fable-nav-port /dev/cu.usbmodemXXXXX --hz 20
```

Open:

```text
http://127.0.0.1:8765
```

Use the top robot selector to choose which robot receives live tele-op HID controls. Press Space in the dashboard to cycle Flash -> Fable -> Sol without clicking.

## Register the Controller in Driver Station

1. Make sure the Feather is connected to the Android phone over USB OTG.
2. Start the Python transmitter.
3. Open the dashboard.
4. Press the `Driver 1: Start + A` button.
5. Confirm the Driver Station registers the controller as driver 1.

The dashboard injects the protocol Options/Start bit plus Cross/A. The Feather maps the Options/Start protocol bit to the HID button that was empirically found to work with the Driver Station.

## Basic Bring-Up Checklist

Use this order when starting from cold hardware:

1. Attach antennas to both radios.
2. Power/connect the Uno and Feather.
3. Confirm both sketches are flashed.
4. Connect the Driver Station phone to the REV Control Hub Wi-Fi AP.
5. Connect the Feather to the phone with USB OTG.
6. Pair the DS4 controller to the Mac.
7. Start `gamepad_lora_tx.py`.
8. Open the dashboard and confirm live controller movement.
9. Register the controller in Driver Station.
10. Test sticks, triggers, buttons, and bumpers in robot code or a gamepad tester.

## Normal Shutdown

Stop the Python transmitter with `Ctrl-C`.

The Feather will neutralize controls automatically if LoRa frames stop arriving.
